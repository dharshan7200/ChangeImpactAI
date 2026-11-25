package com.changeimpact.diff.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileCategoryTest {

    @Test
    void analysedLanguagesAreSourceOrTest() {
        assertThat(FileCategory.of("src/main/java/a/B.java")).isEqualTo(FileCategory.JAVA_SOURCE);
        assertThat(FileCategory.of("tests/test_x.py")).isEqualTo(FileCategory.PYTHON_TEST);
        assertThat(FileCategory.of("src/components/Button.tsx")).isEqualTo(FileCategory.SOURCE);
        assertThat(FileCategory.of("src/components/Button.test.tsx")).isEqualTo(FileCategory.TEST);
        assertThat(FileCategory.of("app/Http/Controllers/UserController.php")).isEqualTo(FileCategory.SOURCE);
        assertThat(FileCategory.of("tests/Feature/UserTest.php")).isEqualTo(FileCategory.TEST);
        assertThat(FileCategory.of("src/App.Tests/UserTests.cs")).isEqualTo(FileCategory.TEST);
        assertThat(FileCategory.of("internal/api/handler_test.go")).isEqualTo(FileCategory.TEST);
        assertThat(FileCategory.of("spec/models/invoice_spec.rb")).isEqualTo(FileCategory.TEST);
        // templates, styles and SQL are analysed now: no longer RESOURCE
        assertThat(FileCategory.of("src/main/resources/templates/owners/ownersList.html")).isEqualTo(FileCategory.SOURCE);
        assertThat(FileCategory.of("src/main/resources/db/schema.sql")).isEqualTo(FileCategory.SOURCE);
        assertThat(FileCategory.of("src/main/resources/static/app.css")).isEqualTo(FileCategory.SOURCE);
        assertThat(FileCategory.of("src/types.d.ts")).isEqualTo(FileCategory.OTHER);
        assertThat(FileCategory.of("src/main/resources/messages.properties")).isEqualTo(FileCategory.RESOURCE);
        assertThat(FileCategory.SOURCE.isSource()).isTrue();
        assertThat(FileCategory.TEST.isTest()).isTrue();
    }
}
