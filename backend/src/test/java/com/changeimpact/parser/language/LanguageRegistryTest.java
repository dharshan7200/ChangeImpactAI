package com.changeimpact.parser.language;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.parser.language.ts.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageRegistryTest {

    /** A parser stub; the registry only reads language, availability and the experimental flag. */
    private static LanguageParser stub(String language) {
        return new LanguageParser() {
            @Override public String language() { return language; }
            @Override public Set<String> extensions() { return Set.of("." + language); }
            @Override public boolean experimental() { return false; }
            @Override public boolean available() { return true; }
            @Override public boolean incremental() { return false; }
            @Override public Session open(Path root) { return null; }
        };
    }

    private static final List<LanguageParser> ALL = List.of("java", "python", "javascript", "php", "csharp", "go", "ruby", "html", "css", "sql")
            .stream().map(LanguageRegistryTest::stub).toList();

    @Test
    void allIsTheDefaultAndEnablesEveryLanguage() {
        assertThat(new LanguageRegistry(ALL, "all").languages())
                .containsExactly("java", "csharp", "css", "go", "html", "javascript", "php", "python", "ruby", "sql");
    }

    @Test
    void listsAliasesAndExclusions() {
        assertThat(new LanguageRegistry(ALL, "python, TypeScript ,c#").languages()).containsExactly("java", "csharp", "javascript", "python");
        assertThat(new LanguageRegistry(ALL, "all,-sql,-css").languages()).doesNotContain("sql", "css").contains("python", "go");
        assertThat(new LanguageRegistry(ALL, "-java").languages()).containsExactly("java"); // Java cannot be turned off
        assertThat(new LanguageRegistry(ALL, "java,cobol").languages()).containsExactly("java");
    }

    @Test
    void realParsersAreAllDefaultOn() {
        AppProperties p = new AppProperties();
        CssLanguageParser css = new CssLanguageParser(p);
        List<LanguageParser> parsers = List.of(new PythonLanguageParser(p), new JavaScriptLanguageParser(p, css), new PhpLanguageParser(p),
                new CSharpLanguageParser(p), new GoLanguageParser(p), new RubyLanguageParser(p), new HtmlLanguageParser(p, css), css,
                new SqlLanguageParser(p));
        assertThat(parsers).noneMatch(LanguageParser::experimental);
        assertThat(parsers).allMatch(LanguageParser::available);
    }
}
