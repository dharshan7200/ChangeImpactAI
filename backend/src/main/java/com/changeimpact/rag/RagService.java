package com.changeimpact.rag;

import com.changeimpact.ai.GeminiClient;
import com.changeimpact.common.logging.SecretRedactor;
import com.changeimpact.diff.service.GitDiffService;
import com.changeimpact.parser.model.SymbolType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Retrieval layer of the AI features: AST-aware code chunks of an analysed commit (one per method / constructor /
 * function, one header chunk per type / module), embeddings (reused by content hash), and hybrid search that fuses
 * PostgreSQL full-text ranking and pgvector cosine similarity with Reciprocal Rank Fusion (k = 60).
 * Code is redacted with {@link SecretRedactor} before it is stored or sent anywhere.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    public static final int MAX_CHUNK_LINES = 150, HEADER_LINES = 25, RRF_K = 60, CANDIDATES = 50;
    static final Set<String> STOPWORDS = Set.of("the", "a", "an", "is", "are", "of", "to", "in", "on", "for", "and", "or",
            "how", "what", "where", "which", "who", "does", "do", "with", "by", "be", "it", "this", "that", "when", "why",
            "can", "from", "as", "at", "i", "me", "my", "we", "our", "get", "set", "code", "class", "method", "function");
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9]+");

    private final JdbcTemplate jdbc;
    private final GitDiffService gitDiffService;
    private final GeminiClient gemini;

    public record Chunk(UUID symbolId, String qualifiedName, String symbolType, String filePath, int startLine, int endLine, String content) {}

    public record Hit(UUID chunkId, UUID symbolId, String qualifiedName, String symbolType, String filePath, int startLine,
                      int endLine, String content, Integer ftsRank, Integer vectorRank, Double similarity, double score) {}

    /**
     * Search outcome: {@code mode} is the retrieval actually used (fts, vector or hybrid); {@code note} explains a
     * fallback (e.g. no embeddings available), or is null.
     */
    public record SearchResult(List<Hit> hits, String mode, String note) {}

    /** Code-shaped tokens: {@code Type#member}, {@code a.b}, snake_case, lowerCamel with an inner capital, PascalCase with 2+ humps
     * (also after an acronym: {@code HTTPAdapter}; plural acronyms like {@code URLs} are words). */
    static final Pattern CODE_LIKE = Pattern.compile("[#`]|\\b[A-Za-z_]\\w*\\.[A-Za-z_]\\w*|\\b[a-z0-9]+_[a-z0-9_]+\\b"
            + "|\\b[a-z][a-z0-9]*[A-Z]\\w*|\\b[A-Z][a-z0-9]+[A-Z]\\w*|\\b[A-Z]{2,}[a-z]{2,}");

    public record IndexStats(int chunks, int created, int embedded, int embeddingsReused, long ms) {}

    // ---------------------------------------------------------------- chunking

    /** Chunks of the given symbols (or all callables and types of the commit when {@code symbolIds} is null). */
    public List<Chunk> chunks(Repository git, UUID repositoryId, String commit, Collection<UUID> symbolIds) throws Exception {
        String filter = symbolIds == null ? "" : " AND s.id = ANY (?)";
        List<Object[]> rows = new ArrayList<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT s.id, s.qualified_name, s.symbol_type, f.relative_path, s.start_line, s.end_line
                      FROM symbols s JOIN source_files f ON f.id = s.source_file_id
                     WHERE s.repository_id = ? AND s.commit_sha = ? AND s.start_line > 0
                       AND s.symbol_type IN ('METHOD', 'CONSTRUCTOR', 'FUNCTION', 'CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'MODULE')"""
                    + filter + " ORDER BY f.relative_path, s.start_line");
            ps.setObject(1, repositoryId);
            ps.setString(2, commit);
            if (symbolIds != null) ps.setArray(3, con.createArrayOf("uuid", symbolIds.toArray()));
            return ps;
        }, rs -> {
            rows.add(new Object[]{rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6)});
        });
        Map<String, List<String>> files = new HashMap<>();
        List<Chunk> out = new ArrayList<>();
        for (Object[] r : rows) {
            String path = (String) r[3];
            List<String> lines = files.computeIfAbsent(path, p -> {
                try {
                    return gitDiffService.readFile(git, commit, p).map(t -> t.lines().toList()).orElse(List.of());
                } catch (Exception e) {
                    return List.of();
                }
            });
            if (lines.isEmpty()) continue;
            SymbolType type = SymbolType.valueOf((String) r[2]);
            int start = Math.max(1, (Integer) r[4]);
            int end = Math.min(lines.size(), Math.max(start, (Integer) r[5]));
            if (type == SymbolType.MODULE) end = Math.min(end, start + HEADER_LINES - 1);
            else if (type.isTypeLike()) end = Math.min(end, start + HEADER_LINES - 1); // declaration + fields
            boolean truncated = false;
            if (end - start + 1 > MAX_CHUNK_LINES) { end = start + MAX_CHUNK_LINES - 1; truncated = true; }
            String text = String.join("\n", lines.subList(start - 1, end)) + (truncated ? "\n    // … (truncated)" : "");
            out.add(new Chunk((UUID) r[0], (String) r[1], (String) r[2], path, start, end, SecretRedactor.redact(text)));
        }
        return out;
    }

    // ---------------------------------------------------------------- indexing

    public int chunkCount(UUID repositoryId, String commit) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE repository_id = ? AND commit_sha = ?",
                Integer.class, repositoryId, commit);
        return n == null ? 0 : n;
    }

    public int candidateCount(UUID repositoryId, String commit) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM symbols WHERE repository_id = ? AND commit_sha = ? AND start_line > 0
                   AND symbol_type IN ('METHOD', 'CONSTRUCTOR', 'FUNCTION', 'CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'MODULE')""",
                Integer.class, repositoryId, commit);
        return n == null ? 0 : n;
    }

    /** Creates the commit's chunks with embeddings (missing ones only); embeddings of identical text are reused. */
    public IndexStats index(Repository git, UUID repositoryId, String commit) throws Exception {
        long t0 = System.currentTimeMillis();
        List<Chunk> chunks = chunks(git, repositoryId, commit, null);
        Set<String> existing = new HashSet<>(jdbc.queryForList(
                "SELECT qualified_name || '@' || start_line FROM code_chunks WHERE repository_id = ? AND commit_sha = ?",
                String.class, repositoryId, commit));
        List<Chunk> missing = chunks.stream().filter(c -> !existing.contains(c.qualifiedName() + "@" + c.startLine())).toList();
        Map<String, String> hashOf = new HashMap<>();
        for (Chunk c : missing) hashOf.put(c.qualifiedName() + "@" + c.startLine(), sha256(c.content()));

        // reuse embeddings of identical content (any commit or repository)
        Map<String, String> reuse = new HashMap<>();
        if (!hashOf.isEmpty()) {
            jdbc.query(con -> {
                var ps = con.prepareStatement("""
                        SELECT DISTINCT ON (content_hash) content_hash, embedding::text FROM code_chunks
                         WHERE content_hash = ANY (?) AND embedding_model = ? AND embedding IS NOT NULL""");
                ps.setArray(1, con.createArrayOf("varchar", new HashSet<>(hashOf.values()).toArray()));
                ps.setString(2, gemini.embeddingModel());
                return ps;
            }, rs -> { reuse.put(rs.getString(1), rs.getString(2)); });
        }
        List<Chunk> toEmbed = new ArrayList<>();
        Set<String> queued = new HashSet<>();
        for (Chunk c : missing) {
            String h = hashOf.get(c.qualifiedName() + "@" + c.startLine());
            if (!reuse.containsKey(h) && queued.add(h)) toEmbed.add(c);
        }
        if (!toEmbed.isEmpty()) {
            List<float[]> vectors = gemini.embed(toEmbed.stream().map(RagService::embeddingText).toList(), "RETRIEVAL_DOCUMENT");
            for (int i = 0; i < toEmbed.size(); i++) {
                Chunk c = toEmbed.get(i);
                reuse.put(hashOf.get(c.qualifiedName() + "@" + c.startLine()), GeminiClient.vectorLiteral(vectors.get(i)));
            }
        }
        List<Object[]> batch = new ArrayList<>();
        for (Chunk c : missing) {
            String h = hashOf.get(c.qualifiedName() + "@" + c.startLine());
            batch.add(new Object[]{repositoryId, commit, c.symbolId(), trim(c.qualifiedName(), 1024), c.symbolType(),
                    trim(c.filePath(), 1024), c.startLine(), c.endLine(), c.content(), h, reuse.get(h), gemini.embeddingModel()});
        }
        jdbc.batchUpdate("""
                INSERT INTO code_chunks (repository_id, commit_sha, symbol_id, qualified_name, symbol_type, file_path,
                                         start_line, end_line, content, content_hash, embedding, embedding_model)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?)
                ON CONFLICT (repository_id, commit_sha, qualified_name, start_line) DO NOTHING""", batch);
        IndexStats stats = new IndexStats(chunks.size(), missing.size(), toEmbed.size(), missing.size() - toEmbed.size(),
                System.currentTimeMillis() - t0);
        log.info("RAG index {} @ {}: {}", repositoryId, commit, stats);
        return stats;
    }

    /** Text sent to the embedding model: the symbol's name, file and code. */
    static String embeddingText(Chunk c) {
        return c.symbolType().toLowerCase(Locale.ROOT) + " " + c.qualifiedName() + "\nfile: " + c.filePath() + "\n" + c.content();
    }

    // ---------------------------------------------------------------- search

    /**
     * Search with fallback. {@code mode} = auto (default), hybrid, fts or vector. {@code auto} measured best on the
     * evaluation sets (docs/AI_REVIEW.md): a query that names code (see {@link #CODE_LIKE}) uses hybrid retrieval, where
     * exact-name matches help; a natural-language query uses vector retrieval alone, because full-text matches on
     * common words ("pet", "owner") push relevant code down. Without embeddings (AI off, daily quota used up) the search
     * falls back to full-text and says so in {@link SearchResult#note()}.
     */
    public SearchResult find(UUID repositoryId, String commit, String query, int k, String mode) {
        String requested = mode == null || mode.isBlank() ? "auto" : mode;
        String effective = "auto".equals(requested) ? (CODE_LIKE.matcher(query).find() ? "hybrid" : "vector") : requested;
        if ("fts".equals(effective)) return new SearchResult(search(repositoryId, commit, query, k, "fts"), "fts", null);
        if (!gemini.enabled()) {
            return new SearchResult(search(repositoryId, commit, query, k, "fts"), "fts",
                    "Vector search is off (no Gemini API key); showing full-text results only.");
        }
        try {
            return new SearchResult(search(repositoryId, commit, query, k, effective), effective, null);
        } catch (GeminiClient.AiUnavailableException e) {
            log.warn("Vector search unavailable, falling back to full-text: {}", e.getMessage());
            return new SearchResult(search(repositoryId, commit, query, k, "fts"), "fts",
                    "Vector search unavailable: " + e.getMessage() + " Showing full-text results only.");
        }
    }

    /**
     * Hybrid search: full-text (camelCase-split words, any word may match, name words weighted above body words, ranked by
     * ts_rank normalised by document length) and vector similarity,
     * each limited to {@value #CANDIDATES} candidates, fused by Reciprocal Rank Fusion. {@code mode}: hybrid, fts, vector.
     */
    public List<Hit> search(UUID repositoryId, String commit, String query, int k, String mode) {
        String tsquery = tsQuery(query);
        boolean useFts = !"vector".equals(mode) && !tsquery.isEmpty();
        boolean useVec = !"fts".equals(mode) && gemini.enabled();
        String vec = useVec ? GeminiClient.vectorLiteral(gemini.embed(List.of(query), "RETRIEVAL_QUERY").get(0)) : null;
        String sql = """
                WITH fts AS (
                    SELECT id, row_number() OVER (ORDER BY ts_rank(tsv, q, 1) DESC, id) AS rk
                      FROM code_chunks, to_tsquery('simple', ?) q
                     WHERE ? AND repository_id = ? AND commit_sha = ? AND tsv @@ q
                     ORDER BY ts_rank(tsv, q, 1) DESC, id LIMIT %1$d),
                vec AS (
                    SELECT id, 1 - (embedding <=> ?::vector) AS sim,
                           row_number() OVER (ORDER BY embedding <=> ?::vector, id) AS rk
                      FROM code_chunks
                     WHERE ? AND repository_id = ? AND commit_sha = ? AND embedding IS NOT NULL
                     ORDER BY embedding <=> ?::vector, id LIMIT %1$d)
                SELECT c.id, c.symbol_id, c.qualified_name, c.symbol_type, c.file_path, c.start_line, c.end_line, c.content,
                       fts.rk, vec.rk, vec.sim,
                       coalesce(1.0 / (%2$d + fts.rk), 0) + coalesce(1.0 / (%2$d + vec.rk), 0) AS score
                  FROM code_chunks c
                  LEFT JOIN fts ON fts.id = c.id
                  LEFT JOIN vec ON vec.id = c.id
                 WHERE fts.id IS NOT NULL OR vec.id IS NOT NULL
                 ORDER BY score DESC, c.qualified_name
                 LIMIT ?""".formatted(CANDIDATES, RRF_K);
        String q = useFts ? tsquery : "x";
        String v = useVec ? vec : "[" + "0,".repeat(767) + "1]";
        return jdbc.query(sql, (rs, i) -> new Hit(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getString(8),
                        intOrNull(rs.getObject(9)), intOrNull(rs.getObject(10)), rs.getObject(11) == null ? null : rs.getDouble(11),
                        rs.getDouble(12)),
                q, useFts, repositoryId, commit, v, v, useVec, repositoryId, commit, v, k);
    }

    /** "How is the owner validated?" → {@code owner | validated} (camelCase split, stop words dropped, OR semantics). */
    static String tsQuery(String query) {
        String split = query.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        LinkedHashSet<String> words = new LinkedHashSet<>();
        Matcher m = WORD.matcher(split);
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            if (w.length() > 1 && !STOPWORDS.contains(w)) words.add(w);
        }
        return String.join(" | ", words);
    }

    private static Integer intOrNull(Object o) {
        return o == null ? null : ((Number) o).intValue(); // row_number() is bigint
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
