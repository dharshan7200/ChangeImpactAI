package com.changeimpact.securityanalysis.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Build-file dependency diff (Maven properties, managed versions, Gradle, XXE-safe)")
class BuildDependencyDiffTest {

    private final BuildDependencyDiff diff = new BuildDependencyDiff();

    private static String pom(String textVersion, String extra) {
        return """
                <project>
                  <version>2.0.0</version>
                  <properties><commons-text.version>%s</commons-text.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.junit</groupId><artifactId>junit-bom</artifactId><version>5.11.0</version></dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.commons</groupId>
                      <artifactId>commons-text</artifactId>
                      <version>${commons-text.version}</version>
                    </dependency>
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
                    <dependency><groupId>com.demo</groupId><artifactId>demo-api</artifactId><version>${project.version}</version></dependency>
                    %s
                  </dependencies>
                </project>
                """.formatted(textVersion, extra);
    }

    @Test
    @DisplayName("POM: property and project.version resolution, managed versions, added and downgraded dependencies")
    void pom() {
        String before = pom("1.10.0", "");
        String after = pom("1.9", "<dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>33.3.1-jre</version></dependency>");
        assertEquals(Map.of("org.apache.commons:commons-text", "1.10.0", "org.springframework.boot:spring-boot-starter-web", "(managed)",
                        "com.demo:demo-api", "2.0.0", "org.junit:junit-bom", "5.11.0"),
                diff.parse("pom.xml", before));
        assertEquals(List.of(
                new BuildDependencyDiff.Change("com.google.guava:guava", null, "33.3.1-jre", "ADDED"),
                new BuildDependencyDiff.Change("org.apache.commons:commons-text", "1.10.0", "1.9", "DOWNGRADED")),
                diff.diff("pom.xml", before, after));
        assertEquals(10, BuildDependencyDiff.lineOf(after, "org.apache.commons:commons-text"));
    }

    @Test
    @DisplayName("Gradle: string notation in common configurations; removed file means removed dependencies")
    void gradle() {
        String before = """
                dependencies {
                    implementation 'org.apache.commons:commons-text:1.10.0'
                    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
                }""";
        String after = """
                dependencies {
                    implementation 'org.apache.commons:commons-text:1.12.0'
                    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
                }""";
        assertEquals(List.of(new BuildDependencyDiff.Change("org.apache.commons:commons-text", "1.10.0", "1.12.0", "UPGRADED")),
                diff.diff("build.gradle.kts", before, after));
        assertEquals(2, diff.diff("build.gradle", before, null).stream().filter(c -> c.type().equals("REMOVED")).count());
        assertEquals(2, BuildDependencyDiff.lineOf(after, "org.apache.commons:commons-text"));
    }

    @Test
    @DisplayName("version direction by numeric segments; non-numeric differences are CHANGED")
    void direction() {
        assertEquals("UPGRADED", BuildDependencyDiff.direction("1.9", "1.10.0"));
        assertEquals("DOWNGRADED", BuildDependencyDiff.direction("2.0.1", "2.0.0"));
        assertEquals("CHANGED", BuildDependencyDiff.direction("1.0-RC1", "1.0-RC2"));
    }

    @Test
    @DisplayName("a POM with an external entity is not expanded (no file read, no network)")
    void xxe() {
        String evil = """
                <?xml version="1.0"?>
                <!DOCTYPE project [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <project><dependencies><dependency><groupId>&xxe;</groupId><artifactId>a</artifactId><version>1</version></dependency></dependencies></project>
                """;
        Map<String, String> parsed = diff.parse("pom.xml", evil);
        assertTrue(parsed.keySet().stream().noneMatch(k -> k.contains("root:")), parsed.toString());
    }
}
