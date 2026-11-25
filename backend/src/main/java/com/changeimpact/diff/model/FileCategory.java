package com.changeimpact.diff.model;

import java.util.Locale;

/** Coarse classification of a changed file, used for reporting and verification advice. */
public enum FileCategory {
    JAVA_SOURCE,
    JAVA_TEST,
    PYTHON_SOURCE,
    PYTHON_TEST,
    /** Source of the other analysed languages (JavaScript/TypeScript, PHP, C#, Go, Ruby, HTML templates, CSS, SQL). */
    SOURCE,
    /** Tests of those languages. */
    TEST,
    CONFIG,
    BUILD,
    RESOURCE,
    DOCS,
    OTHER;

    public static FileCategory of(String path) {
        String p = path.replace('\\', '/');
        String name = p.substring(p.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) {
            boolean test = p.contains("/src/test/") || p.startsWith("src/test/")
                    || name.endsWith("test.java") || name.endsWith("tests.java") || name.endsWith("it.java");
            return test ? JAVA_TEST : JAVA_SOURCE;
        }
        if (name.endsWith(".py")) {
            boolean test = name.startsWith("test_") || name.endsWith("_test.py") || name.equals("conftest.py")
                    || p.startsWith("tests/") || p.contains("/tests/") || p.startsWith("test/") || p.contains("/test/");
            return test ? PYTHON_TEST : PYTHON_SOURCE;
        }
        String lang = otherLanguage(name);
        if (lang != null) return isTestPath(p, name, lang) ? TEST : SOURCE;
        if (name.equals("pom.xml") || name.startsWith("build.gradle") || name.startsWith("settings.gradle")
                || name.equals("gradle.properties") || name.equals("mvnw") || name.equals("gradlew")) {
            return BUILD;
        }
        if (isConfigFile(name)) {
            return CONFIG;
        }
        if (name.endsWith(".md") || name.endsWith(".adoc") || name.endsWith(".txt") || p.startsWith("docs/")) {
            return DOCS;
        }
        if (p.contains("/src/main/resources/") || p.contains("/src/test/resources/")
                || p.startsWith("src/main/resources/") || p.startsWith("src/test/resources/")) {
            return RESOURCE;
        }
        return OTHER;
    }

    /** Source code of an analysed language (production or test). */
    public boolean isSource() {
        return this == JAVA_SOURCE || this == JAVA_TEST || this == PYTHON_SOURCE || this == PYTHON_TEST || this == SOURCE || this == TEST;
    }

    public boolean isTest() {
        return this == JAVA_TEST || this == PYTHON_TEST || this == TEST;
    }

    /** Language of a file analysed by a tree-sitter or SQL parser, by extension; null for others. */
    static String otherLanguage(String name) {
        if (name.endsWith(".d.ts") || name.endsWith(".min.js") || name.endsWith(".min.css")) return null;
        if (name.matches(".*\\.(ts|tsx|js|jsx|mjs|cjs|mts|cts)$")) return "javascript";
        if (name.endsWith(".php")) return name.endsWith(".blade.php") ? null : "php";
        if (name.endsWith(".cs")) return "csharp";
        if (name.endsWith(".go")) return "go";
        if (name.endsWith(".rb") || name.endsWith(".rake")) return "ruby";
        if (name.matches(".*\\.(html|htm|jinja|jinja2|j2|twig|njk|liquid)$")) return "html";
        if (name.endsWith(".css")) return "css";
        if (name.endsWith(".sql")) return "sql";
        return null;
    }

    /** Same conventions as the parsers' test detection. */
    static boolean isTestPath(String path, String name, String language) {
        String p = "/" + path.toLowerCase(Locale.ROOT);
        return switch (language) {
            case "javascript" -> name.matches(".*\\.(test|spec|e2e|cy|stories)\\.[cm]?[jt]sx?$") || p.contains("/__tests__/")
                    || p.contains("/__mocks__/") || p.contains("/test/") || p.contains("/tests/") || p.contains("/e2e/") || p.contains("/cypress/");
            case "php" -> p.contains("/tests/") || name.endsWith("test.php");
            case "csharp" -> p.contains(".tests/") || p.contains(".test/") || p.contains("/tests/") || p.contains("/test/")
                    || name.endsWith("tests.cs") || name.endsWith("test.cs");
            case "go" -> name.endsWith("_test.go");
            case "ruby" -> p.contains("/spec/") || p.contains("/test/") || name.endsWith("_spec.rb") || name.endsWith("_test.rb");
            default -> p.contains("/src/test/") || p.contains("/tests/") || p.contains("/test/");
        };
    }

    /** Spring Boot configuration files (application*.yml|yaml|properties, bootstrap*). */
    public static boolean isConfigFile(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return (n.startsWith("application") || n.startsWith("bootstrap"))
                && (n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".properties"));
    }
}
