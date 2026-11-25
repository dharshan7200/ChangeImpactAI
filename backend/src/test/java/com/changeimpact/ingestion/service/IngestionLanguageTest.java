package com.changeimpact.ingestion.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionLanguageTest {

    private static Map<String, Integer> counts(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    @Test
    void dominantAnalysedLanguageLabelsTheRepository() {
        assertThat(IngestionService.dominantLanguage(counts("java", 0, "python", 37))).contains("Python");
        assertThat(IngestionService.dominantLanguage(counts("java", 120, "python", 14))).contains("Java");
        assertThat(IngestionService.dominantLanguage(counts("java", 5, "python", 5))).contains("Java"); // tie: alphabetical
        assertThat(IngestionService.dominantLanguage(counts("java", 0))).isEqualTo(Optional.empty());
        // markup and schema files never label a repository that has program code
        assertThat(IngestionService.dominantLanguage(counts("css", 90, "html", 40, "javascript", 12))).contains("JavaScript/TypeScript");
        assertThat(IngestionService.dominantLanguage(counts("csharp", 3, "sql", 30))).contains("C#");
        assertThat(IngestionService.dominantLanguage(counts("html", 4, "css", 2))).contains("HTML");
    }

    @Test
    void progressMessageNamesEveryLanguage() {
        assertThat(IngestionService.describe(counts("java", 120, "python", 14))).isEqualTo("120 Java + 14 Python files");
        assertThat(IngestionService.describe(counts("java", 0, "python", 37))).isEqualTo("37 Python files");
        assertThat(IngestionService.describe(counts("php", 8, "sql", 2))).isEqualTo("8 PHP + 2 SQL files");
    }
}
