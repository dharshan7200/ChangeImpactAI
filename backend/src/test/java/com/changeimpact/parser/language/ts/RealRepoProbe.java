package com.changeimpact.parser.language.ts;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.parser.language.LanguageParser;
import com.changeimpact.parser.language.PythonLanguageParser;
import com.changeimpact.parser.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Parser statistics on real repositories, as reported in docs/MULTI_LANGUAGE.md: {@code PROBE_REPOS=<folder of clones>
 * PROBE_OUT=<report file> mvn test -Dtest=RealRepoProbe}. Skipped unless PROBE_REPOS is set. Parses only; never runs code.
 */
@EnabledIfEnvironmentVariable(named = "PROBE_REPOS", matches = ".+")
class RealRepoProbe {

    @Test
    void probe() throws Exception {
        AppProperties p = new AppProperties();
        CssLanguageParser css = new CssLanguageParser(p);
        List<LanguageParser> parsers = List.of(new PythonLanguageParser(p), new JavaScriptLanguageParser(p, css), new PhpLanguageParser(p),
                new CSharpLanguageParser(p), new GoLanguageParser(p), new RubyLanguageParser(p), new HtmlLanguageParser(p, css), css,
                new SqlLanguageParser(p));
        StringBuilder out = new StringBuilder();
        try (var dirs = Files.list(Path.of(System.getenv("PROBE_REPOS")))) {
            for (Path repo : dirs.sorted().toList()) {
                Map<String, ParsedSourceFile> all = new TreeMap<>();
                out.append("\n=== ").append(repo.getFileName()).append('\n');
                for (LanguageParser lp : parsers) {
                    long t0 = System.nanoTime();
                    Map<String, ParsedSourceFile> files = TreeSitterFixture.parseAll(lp, repo);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    if (files.isEmpty()) continue;
                    all.putAll(files);
                    int syms = files.values().stream().mapToInt(f -> f.getSymbols().size()).sum();
                    int deps = files.values().stream().mapToInt(f -> f.getDependencies().size()).sum();
                    long tests = files.values().stream().filter(ParsedSourceFile::isTest).count();
                    out.append(String.format("%-10s files=%4d (tests %3d) symbols=%5d deps=%5d  %5d ms%n", lp.language(), files.size(), tests, syms, deps, ms));
                }
                Set<String> qns = all.values().stream().flatMap(f -> f.getSymbols().stream()).map(ExtractedSymbol::getQualifiedName).collect(Collectors.toSet());
                Map<String, int[]> byLang = new TreeMap<>();
                List<String> samples = new ArrayList<>();
                for (ParsedSourceFile f : all.values()) for (ExtractedDependency d : f.getDependencies()) {
                    int[] c = byLang.computeIfAbsent(f.getLanguage() + " " + d.getDependencyType(), k -> new int[3]);
                    String t = d.getTargetQualifiedName();
                    boolean internal = qns.contains(t) || (t.contains("#") && qns.contains(t.substring(0, t.indexOf('#'))));
                    boolean db = t.startsWith("db:");
                    if (internal) c[0]++; else if (db) c[2]++; else c[1]++;
                    if (internal) samples.add(f.getLanguage() + " " + d.getDependencyType() + " " + d.getSourceQualifiedName() + " -> " + t + "  @" + f.getRelativePath() + ":" + d.getSourceLine());
                }
                byLang.forEach((k, c) -> out.append(String.format("   %-28s internal=%5d external=%5d db-dropped=%4d%n", k, c[0], c[1], c[2])));
                Collections.sort(samples);
                Random r = new Random(42);
                out.append("   samples:\n");
                for (int i = 0; i < Math.min(25, samples.size()); i++) out.append("     ").append(samples.get(r.nextInt(samples.size()))).append('\n');
            }
        }
        Files.writeString(Path.of(System.getenv("PROBE_OUT")), out.toString());
    }
}
