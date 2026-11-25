package com.changeimpact.parser.language.ts;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.parser.language.LanguageParser;
import com.changeimpact.parser.model.*;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL scripts: schemas, migrations (Flyway, Liquibase SQL, Rails structure.sql), seed data and stored code, in the
 * common dialects (PostgreSQL, MySQL/MariaDB, SQL Server, Oracle, SQLite, H2). There is no maintained tree-sitter SQL
 * grammar on Maven Central, so statements are split and read with a small purpose-built reader: it understands quoting,
 * comments, dollar-quoted bodies, {@code DELIMITER} and {@code GO}, and the DDL statements below. Anything else is
 * read only for the tables it names.
 *
 * <p>Model: every script is a {@code MODULE} ({@code db/migration/V1__init.sql} → {@code db.migration.V1__init.sql}).
 * {@code CREATE TABLE} defines a table {@code db:owners} (a {@code CLASS} annotated {@code table}) with its columns
 * {@code db:owners#first_name} ({@code FIELD}); {@code ALTER TABLE … ADD COLUMN} adds columns; views are {@code CLASS}es
 * annotated {@code view}; functions, procedures and triggers are {@code FUNCTION}s {@code db:fn()}. Default schemas
 * ({@code public}, {@code dbo}, {@code main}) are dropped, names are lower case.
 *
 * <p>Links: foreign keys ({@code REFERENCES}) as {@code FIELD_TYPE}; tables read or written by views, functions, triggers,
 * DML and other DDL as {@code FIELD_ACCESS}; function calls and trigger functions as {@code METHOD_CALL}. Code in other
 * languages links to these symbols (JPA entities, Rails models, {@code [Table]}, SQL in strings).
 */
@Component
public class SqlLanguageParser implements LanguageParser {

    static final String NAME_PART = "(?:[\\w$#@]+|\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\])";
    static final String NAME = "(" + NAME_PART + "(?:\\s*\\.\\s*" + NAME_PART + ")*)";
    private static final Pattern CREATE_TABLE = Pattern.compile("(?is)^create\\s+(?:or\\s+replace\\s+)?(?:(?:global|local)\\s+)?(?:temp(?:orary)?\\s+|unlogged\\s+|virtual\\s+)?table\\s+(?:if\\s+not\\s+exists\\s+)?" + NAME + "\\s*\\(");
    private static final Pattern CREATE_VIEW = Pattern.compile("(?is)^create\\s+(?:or\\s+replace\\s+)?(?:or\\s+alter\\s+)?(?:temp(?:orary)?\\s+)?(?:materialized\\s+)?view\\s+(?:if\\s+not\\s+exists\\s+)?" + NAME + "(.*)$");
    private static final Pattern CREATE_ROUTINE = Pattern.compile("(?is)^create\\s+(?:or\\s+replace\\s+)?(?:or\\s+alter\\s+)?(?:definer\\s*=\\s*\\S+\\s+)?(function|procedure|proc)\\s+(?:if\\s+not\\s+exists\\s+)?" + NAME + "\\s*(.*)$");
    private static final Pattern CREATE_TRIGGER = Pattern.compile("(?is)^create\\s+(?:or\\s+replace\\s+)?(?:or\\s+alter\\s+)?(?:definer\\s*=\\s*\\S+\\s+)?(?:constraint\\s+)?trigger\\s+(?:if\\s+not\\s+exists\\s+)?" + NAME + "(.*)$");
    private static final Pattern CREATE_INDEX = Pattern.compile("(?is)^create\\s+(?:unique\\s+|fulltext\\s+|spatial\\s+|clustered\\s+|nonclustered\\s+)*index\\s+(?:concurrently\\s+)?(?:if\\s+not\\s+exists\\s+)?(?:" + NAME + "\\s+)?on\\s+(?:only\\s+)?" + NAME);
    private static final Pattern ALTER_TABLE = Pattern.compile("(?is)^alter\\s+table\\s+(?:if\\s+exists\\s+)?(?:only\\s+)?" + NAME + "\\s+(.*)$");
    private static final Pattern DROP = Pattern.compile("(?is)^(?:drop|truncate)\\s+(?:table\\s+)?(?:if\\s+exists\\s+)?" + NAME);
    private static final Pattern REFERENCES = Pattern.compile("(?is)\\breferences\\s+" + NAME + "\\s*(?:\\(([^)]*)\\))?");
    private static final Pattern FOREIGN_KEY = Pattern.compile("(?is)\\bforeign\\s+key\\s*\\(([^)]*)\\)");
    private static final Pattern ON_TABLE = Pattern.compile("(?is)\\bon\\s+" + NAME);
    private static final Pattern EXECUTE = Pattern.compile("(?is)\\bexecute\\s+(?:function|procedure)\\s+" + NAME);
    private static final Pattern TABLE_REF = Pattern.compile("(?i)\\b(?:from|join|into|update|table)\\s+(?:only\\s+)?" + NAME);
    private static final Pattern CALL = Pattern.compile("(?i)(?<![\\w$.])" + NAME + "\\s*\\(");
    private static final Pattern ADD_COLUMN = Pattern.compile("(?is)^add\\s+(?:column\\s+)?(?:if\\s+not\\s+exists\\s+)?(" + NAME_PART + ")\\s+(.*)$");
    private static final Pattern DOLLAR_TAG = Pattern.compile("\\$[A-Za-z_]?\\w*\\$");
    private static final Pattern DELIMITER = Pattern.compile("(?i)delimiter\\s+(\\S+)[ \\t]*");
    private static final Pattern GO = Pattern.compile("(?i)go[ \\t]*(\\r?\\n|$)");
    private static final Pattern FIRST_NAME = Pattern.compile("^\\s*(" + NAME_PART + ")");
    private static final Set<String> CONSTRAINT_START = Set.of("constraint", "primary", "foreign", "unique", "check", "index", "key",
            "fulltext", "spatial", "exclude", "like", "period", "unique_key");
    private static final Set<String> TYPE_END = Set.of("not", "null", "default", "primary", "references", "unique", "check", "constraint",
            "generated", "auto_increment", "autoincrement", "identity", "collate", "comment", "on", "as");
    private static final Set<String> NOT_TABLES = Set.of("select", "where", "set", "values", "lateral", "unnest", "dual", "only", "each", "row");

    private final AppProperties appProperties;

    public SqlLanguageParser(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override public String language() { return "sql"; }
    @Override public Set<String> extensions() { return Set.of(".sql"); }
    @Override public boolean experimental() { return false; }
    @Override public boolean available() { return true; }
    @Override public boolean incremental() { return false; }

    static boolean accepts(String rel) {
        return rel.toLowerCase(Locale.ROOT).endsWith(".sql");
    }

    @Override
    public Session open(Path root) {
        return new Session() {
            private List<Path> files;
            private Map<String, ParsedSourceFile> results;

            @Override
            public List<Path> sourceFiles() {
                if (files == null) files = TreeSitterParser.scanFiles(root, appProperties, SqlLanguageParser::accepts, language());
                return files;
            }

            @Override
            public Optional<ParsedSourceFile> parse(Path file) {
                if (results == null) results = parseAll(root, sourceFiles());
                return Optional.ofNullable(results.get(TreeSitterParser.rel(root, file)));
            }
        };
    }

    record Statement(String text, int start, int line) {}

    record Script(String path, byte[] bytes, String text, List<Statement> statements) {}

    private Map<String, ParsedSourceFile> parseAll(Path root, List<Path> files) {
        List<Script> scripts = new ArrayList<>();
        for (Path f : files) {
            try {
                if (Files.size(f) > TreeSitterParser.MAX_FILE_BYTES * 5) continue; // large dumps are data, not schema
                String text = TreeSitterParser.decode(Files.readAllBytes(f));
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                scripts.add(new Script(TreeSitterParser.rel(root, f), bytes, text, split(text)));
            } catch (Exception e) {
                // unreadable script
            }
        }
        Set<String> functions = new HashSet<>();
        for (Script s : scripts) for (Statement st : s.statements()) {
            Matcher m = CREATE_ROUTINE.matcher(st.text());
            if (m.find()) functions.add(EmbeddedSql.name(m.group(2)) + "()");
        }
        Map<String, ParsedSourceFile> out = new LinkedHashMap<>();
        for (Script s : scripts) out.put(s.path(), analyse(s, functions));
        return out;
    }

    // ---- splitting ----------------------------------------------------------------------------------------------

    /** Statements with comments blanked out (positions and lines preserved). */
    static List<Statement> split(String text) {
        StringBuilder clean = new StringBuilder(text.length());
        List<Statement> out = new ArrayList<>();
        String delimiter = ";";
        int i = 0, start = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-' || c == '#' && lineStart(text, i) && !text.startsWith("#{", i)) {
                while (i < n && text.charAt(i) != '\n') { clean.append(' '); i++; }
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (; i < end; i++) clean.append(text.charAt(i) == '\n' ? '\n' : ' ');
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                int j = i + 1;
                while (j < n && !(text.charAt(j) == c && (j + 1 >= n || text.charAt(j + 1) != c))) j += text.charAt(j) == c ? 2 : 1;
                j = Math.min(n, j + 1);
                clean.append(text, i, j);
                i = j;
                continue;
            }
            if (c == '$') {
                Matcher tag = DOLLAR_TAG.matcher(text).region(i, n);
                if (tag.lookingAt()) {
                    String t = tag.group();
                    int end = text.indexOf(t, i + t.length());
                    end = end < 0 ? n : end + t.length();
                    clean.append(text, i, end);
                    i = end;
                    continue;
                }
            }
            if (lineStart(text, i)) {
                Matcher d = DELIMITER.matcher(text).region(i, n);
                if (d.lookingAt()) {
                    add(out, clean, start, text);
                    delimiter = d.group(1);
                    for (int k = i; k < d.end(); k++) clean.append(' ');
                    i = d.end();
                    start = i;
                    continue;
                }
                Matcher go = GO.matcher(text).region(i, n);
                if (go.lookingAt()) {
                    add(out, clean, start, text);
                    for (int k = i; k < go.end(); k++) clean.append(text.charAt(k) == '\n' ? '\n' : ' ');
                    i = go.end();
                    start = i;
                    continue;
                }
            }
            if (text.startsWith(delimiter, i)) {
                add(out, clean, start, text);
                for (int k = 0; k < delimiter.length(); k++) clean.append(' ');
                i += delimiter.length();
                start = i;
                continue;
            }
            clean.append(c);
            i++;
        }
        add(out, clean, start, text);
        return out;
    }

    private static boolean lineStart(String text, int i) {
        for (int k = i - 1; k >= 0; k--) {
            char c = text.charAt(k);
            if (c == '\n') return true;
            if (c != ' ' && c != '\t' && c != '\r') return false;
        }
        return true;
    }

    private static void add(List<Statement> out, StringBuilder clean, int start, String original) {
        String s = clean.substring(Math.min(start, clean.length()));
        int lead = 0;
        while (lead < s.length() && Character.isWhitespace(s.charAt(lead))) lead++;
        String body = s.substring(lead).strip();
        if (body.isEmpty()) return;
        int pos = start + lead;
        int line = 1;
        for (int k = 0; k < pos && k < original.length(); k++) if (original.charAt(k) == '\n') line++;
        out.add(new Statement(body, pos, line));
    }

    // ---- analysis -----------------------------------------------------------------------------------------------

    private ParsedSourceFile analyse(Script s, Set<String> functions) {
        String module = TreeSitterParser.pathName(s.path(), true);
        TreeSitterParser.Emitter e = new TreeSitterParser.Emitter();
        StringBuilder all = new StringBuilder();
        for (Statement st : s.statements()) all.append(collapse(st.text())).append(";\n");
        e.symbols.add(sym(s.path().substring(s.path().lastIndexOf('/') + 1), module, SymbolType.MODULE, null, 1,
                Math.max(1, (int) s.text().lines().count()), TreeSitterParser.hash(all.toString()), List.of(), null));
        for (Statement st : s.statements()) {
            try {
                statement(st, module, e, functions);
            } catch (Exception ex) {
                // unreadable statement: skipped
            }
        }
        String p = "/" + s.path().toLowerCase(Locale.ROOT);
        return ParsedSourceFile.builder().relativePath(s.path()).packageName(TreeSitterParser.pathName(TreeSitterParser.directory(s.path()), false))
                .linesOfCode((int) s.text().lines().count()).sizeBytes(s.bytes().length)
                .isTest(p.contains("/test/") || p.contains("/tests/") || p.contains("/spec/"))
                .symbols(e.symbols).dependencies(e.deps).language("sql").build();
    }

    private static void statement(Statement st, String module, TreeSitterParser.Emitter e, Set<String> functions) {
        String t = st.text();
        int endLine = st.line() + (int) t.chars().filter(c -> c == '\n').count();
        Matcher m;
        if ((m = CREATE_TABLE.matcher(t)).find()) {
            String table = EmbeddedSql.name(m.group(1));
            int open = m.end() - 1, close = matching(t, open);
            e.symbols.add(sym(simple(table), table, SymbolType.CLASS, module, st.line(), endLine, TreeSitterParser.hash(collapse(t)),
                    List.of("table"), null));
            String body = t.substring(open + 1, close < 0 ? t.length() : close);
            int offset = open + 1;
            for (String element : topLevel(body, ',')) {
                int line = st.line() + (int) t.substring(0, Math.min(t.length(), offset)).chars().filter(c -> c == '\n').count()
                        + leadingNewlines(element);
                offset += element.length() + 1;
                String el = element.strip();
                if (el.isEmpty()) continue;
                String first = firstWord(el);
                if (CONSTRAINT_START.contains(first.toLowerCase(Locale.ROOT))) {
                    constraint(el, table, table, e, line);
                } else {
                    column(el, table, e, line);
                }
            }
            rest(close < 0 ? "" : t.substring(close + 1), table, e, st.line());
        } else if ((m = CREATE_VIEW.matcher(t)).find()) {
            String view = EmbeddedSql.name(m.group(1));
            e.symbols.add(sym(simple(view), view, SymbolType.CLASS, module, st.line(), endLine, TreeSitterParser.hash(collapse(t)), List.of("view"), null));
            tables(m.group(2), view, e, st.line(), functions);
        } else if ((m = CREATE_ROUTINE.matcher(t)).find()) {
            String fn = EmbeddedSql.name(m.group(2)) + "()";
            String sig = m.group(3) == null ? "" : m.group(3);
            int close = sig.startsWith("(") ? matching(sig, 0) : -1;
            e.symbols.add(sym(simple(fn), fn, SymbolType.FUNCTION, module, st.line(), endLine, TreeSitterParser.hash(collapse(t)),
                    List.of(m.group(1).equalsIgnoreCase("proc") ? "procedure" : m.group(1).toLowerCase(Locale.ROOT)),
                    close > 0 ? collapse(sig.substring(1, close)) : ""));
            tables(sig, fn, e, st.line(), functions);
        } else if ((m = CREATE_TRIGGER.matcher(t)).find()) {
            String trigger = EmbeddedSql.name(m.group(1)) + "()";
            e.symbols.add(sym(simple(trigger), trigger, SymbolType.FUNCTION, module, st.line(), endLine, TreeSitterParser.hash(collapse(t)), List.of("trigger"), null));
            Matcher on = ON_TABLE.matcher(m.group(2));
            if (on.find()) e.dep(trigger, EmbeddedSql.name(on.group(1)), DependencyType.FIELD_ACCESS, st.line());
            Matcher ex = EXECUTE.matcher(m.group(2));
            if (ex.find()) e.dep(trigger, EmbeddedSql.name(ex.group(1)) + "()", DependencyType.METHOD_CALL, st.line());
            tables(m.group(2), trigger, e, st.line(), functions);
        } else if ((m = CREATE_INDEX.matcher(t)).find()) {
            e.dep(module, EmbeddedSql.name(m.group(2)), DependencyType.FIELD_ACCESS, st.line());
        } else if ((m = ALTER_TABLE.matcher(t)).find()) {
            String table = EmbeddedSql.name(m.group(1));
            e.dep(module, table, DependencyType.FIELD_ACCESS, st.line());
            for (String action : topLevel(m.group(2), ',')) {
                String a = action.strip();
                Matcher add = ADD_COLUMN.matcher(a);
                String head = firstWord(a.replaceFirst("(?i)^add\\s+", "")).toLowerCase(Locale.ROOT);
                if (add.find() && !CONSTRAINT_START.contains(add.group(1).toLowerCase(Locale.ROOT)) && !CONSTRAINT_START.contains(head)) {
                    column(add.group(1) + " " + add.group(2), table, e, st.line());
                } else if (a.toLowerCase(Locale.ROOT).startsWith("add")) {
                    constraint(a.replaceFirst("(?i)^add\\s+", ""), table, module, e, st.line());
                }
            }
        } else if ((m = DROP.matcher(t)).find()) {
            e.dep(module, EmbeddedSql.name(m.group(1)), DependencyType.FIELD_ACCESS, st.line());
        } else {
            tables(t, module, e, st.line(), functions);
        }
    }

    /** Column definition: {@code name type [constraints]}; inline {@code REFERENCES t} is a foreign key. */
    private static void column(String el, String table, TreeSitterParser.Emitter e, int line) {
        String name = unquote(firstWord(el));
        String rest = el.substring(Math.min(el.length(), firstWordLength(el))).strip();
        StringBuilder type = new StringBuilder();
        for (String w : rest.split("\\s+")) {
            if (TYPE_END.contains(w.toLowerCase(Locale.ROOT).replaceAll("\\(.*", ""))) break;
            type.append(type.isEmpty() ? "" : " ").append(w);
        }
        String qn = table + "#" + name.toLowerCase(Locale.ROOT);
        e.symbols.add(sym(name, qn, SymbolType.FIELD, table, line, line + (int) el.chars().filter(c -> c == '\n').count(),
                TreeSitterParser.hash(collapse(el)), List.of(), null, type.toString()));
        Matcher r = REFERENCES.matcher(el);
        if (r.find()) e.dep(qn, EmbeddedSql.name(r.group(1)), DependencyType.FIELD_TYPE, line);
    }

    /** Table constraint: {@code FOREIGN KEY (a, b) REFERENCES t (x, y)} links the table and its columns to t. */
    private static void constraint(String el, String table, String source, TreeSitterParser.Emitter e, int line) {
        Matcher r = REFERENCES.matcher(el);
        if (!r.find()) return;
        String target = EmbeddedSql.name(r.group(1));
        e.dep(source, target, DependencyType.FIELD_TYPE, line);
        Matcher fk = FOREIGN_KEY.matcher(el);
        if (fk.find()) for (String c : fk.group(1).split(",")) {
            if (!c.isBlank()) e.dep(table + "#" + unquote(c.strip()).toLowerCase(Locale.ROOT), target, DependencyType.FIELD_TYPE, line);
        }
    }

    /** Text after the column list ({@code INHERITS (parent)}, {@code PARTITION OF parent}) referencing tables. */
    private static void rest(String tail, String table, TreeSitterParser.Emitter e, int line) {
        Matcher inh = Pattern.compile("(?is)\\b(?:inherits|partition\\s+of)\\s*\\(?\\s*" + NAME).matcher(tail);
        if (inh.find()) e.dep(table, EmbeddedSql.name(inh.group(1)), DependencyType.EXTENDS, line);
    }

    /** Tables read or written by {@code text} and repository functions it calls. */
    private static void tables(String text, String source, TreeSitterParser.Emitter e, int line, Set<String> functions) {
        Matcher m = TABLE_REF.matcher(stripStrings(text));
        while (m.find()) {
            String t = EmbeddedSql.name(m.group(1));
            if (!NOT_TABLES.contains(t.substring(EmbeddedSql.PREFIX.length())) && !t.equals(source))
                e.dep(source, t, DependencyType.FIELD_ACCESS, line + lineOf(text, m.start()));
        }
        Matcher c = CALL.matcher(stripStrings(text));
        while (c.find()) {
            String fn = EmbeddedSql.name(c.group(1)) + "()";
            if (functions.contains(fn) && !fn.equals(source)) e.dep(source, fn, DependencyType.METHOD_CALL, line + lineOf(text, c.start()));
        }
    }

    private static int lineOf(String text, int pos) {
        int n = 0;
        for (int i = 0; i < pos && i < text.length(); i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    /** String literals blanked (keeps dollar-quoted bodies, which are code). */
    private static String stripStrings(String text) {
        return text.replaceAll("'(?:[^']|'')*'", "''");
    }

    /** Splits at {@code sep} outside parentheses and quotes. */
    static List<String> topLevel(String s, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '\'' || c == '"' || c == '`') quote = c;
            else if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == sep && depth == 0) { out.add(s.substring(start, i)); start = i + 1; }
        }
        out.add(s.substring(start));
        return out;
    }

    /** Index of the parenthesis closing the one at {@code open}, or -1. */
    static int matching(String s, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '\'' || c == '"' || c == '`') quote = c;
            else if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static int leadingNewlines(String s) {
        int n = 0;
        for (int i = 0; i < s.length() && Character.isWhitespace(s.charAt(i)); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private static String firstWord(String s) {
        Matcher m = FIRST_NAME.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static int firstWordLength(String s) {
        Matcher m = FIRST_NAME.matcher(s);
        return m.find() ? m.end() : 0;
    }

    static String unquote(String s) {
        return s.replaceAll("^[\"`\\[]|[\"`\\]]$", "");
    }

    private static String simple(String qn) {
        String n = qn.substring(EmbeddedSql.PREFIX.length()).replace("()", "");
        return n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : n;
    }

    static String collapse(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    private static ExtractedSymbol sym(String simple, String qn, SymbolType type, String parent, int start, int end, String hash,
                                       List<String> annotations, String parameters) {
        return sym(simple, qn, type, parent, start, end, hash, annotations, parameters, null);
    }

    private static ExtractedSymbol sym(String simple, String qn, SymbolType type, String parent, int start, int end, String hash,
                                       List<String> annotations, String parameters, String returnType) {
        return ExtractedSymbol.builder().simpleName(simple).qualifiedName(qn).symbolType(type).parentQualifiedName(parent)
                .visibility("public").annotations(annotations).annotationText(annotations.isEmpty() ? null : String.join("\n", annotations))
                .parameters(parameters).returnType(returnType == null || returnType.isBlank() ? null : returnType)
                .startLine(start).endLine(Math.max(start, end)).startColumn(1).endColumn(1)
                .resolutionStatus(ResolutionStatus.RESOLVED).contentHash(hash).build();
    }
}
