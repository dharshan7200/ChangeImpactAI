package com.changeimpact.parser.libraries;

import com.changeimpact.parser.java.JavaAstParser;
import com.changeimpact.parser.model.ExtractedDependency;
import com.changeimpact.parser.model.ParsedSourceFile;
import com.changeimpact.parser.model.ResolutionStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end without network: a library jar is compiled, published to a file-based Maven repository, resolved for a
 * two-module Maven project and a Gradle project, and put on the symbol solver's path.
 */
@DisplayName("Library resolution: Maven/Gradle builds → dependency jars → resolved library-typed calls")
class LibraryResolutionTest {

    @TempDir static Path tmp;
    static Path remote, project;

    @BeforeAll
    static void fixtures() throws Exception {
        remote = Files.createDirectories(tmp.resolve("remote"));
        publishLibrary(remote, "com.acme", "acme-lib", "1.0");
        publishBom(remote, "com.acme", "acme-bom", "2.0", "acme-lib", "1.0");
        project = Files.createDirectories(tmp.resolve("project"));
        write(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId><artifactId>root</artifactId>
                  <version>${revision}</version><packaging>pom</packaging>
                  <properties><revision>1.4.0</revision><acme.version>1.0</acme.version></properties>
                  <repositories><repository><id>evil</id><url>http://127.0.0.1:9/never-contacted</url></repository></repositories>
                  <modules><module>core</module><module>app</module></modules>
                </project>""");
        write(project.resolve("core/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>root</artifactId><version>${revision}</version></parent>
                  <artifactId>core</artifactId>
                  <dependencies><dependency><groupId>com.acme</groupId><artifactId>acme-lib</artifactId><version>${acme.version}</version></dependency></dependencies>
                </project>""");
        write(project.resolve("app/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>root</artifactId><version>${revision}</version></parent>
                  <artifactId>app</artifactId>
                  <dependencies><dependency><groupId>com.example</groupId><artifactId>core</artifactId><version>${revision}</version></dependency></dependencies>
                </project>""");
        write(project.resolve("core/src/main/java/com/example/core/Format.java"), """
                package com.example.core;
                import com.acme.Money;
                public class Format {
                    public static String show(Money m) { return "money"; }
                    public static String show(String s) { return s; }
                }""");
        write(project.resolve("app/src/main/java/com/example/app/Use.java"), """
                package com.example.app;
                import com.acme.Money;
                import com.example.core.Format;
                public class Use {
                    String run() { return Format.show(Money.of(3)); }
                }""");
    }

    private LibraryResolver resolver() {
        return new LibraryResolver(true, remote.toUri().toString(), tmp.resolve("cache").toString(), 50, 100, 60);
    }

    @Test
    void mavenReactorResolvesTheLibraryButNotItsOwnModules() {
        var cp = resolver().resolve(project);
        assertThat(cp.status()).as(cp.message()).isEqualTo(LibraryResolver.Status.RESOLVED);
        assertThat(cp.buildSystem()).isEqualTo("MAVEN");
        assertThat(cp.artifacts()).containsExactly("com.acme:acme-lib:1.0");   // not com.example:core (reactor), evil repo ignored
        assertThat(cp.fingerprint()).hasSize(64).isEqualTo(resolver().resolve(project).fingerprint());
    }

    @Test
    void libraryTypedArgumentsPickTheRightOverloadOnlyWithTheJar() {
        var cp = resolver().resolve(project);
        assertThat(calls(cp.jars())).anySatisfy(d -> {
            assertThat(d.getTargetQualifiedName()).isEqualTo("com.example.core.Format#show(Money)");
            assertThat(d.getResolutionStatus()).isEqualTo(ResolutionStatus.RESOLVED);
        }).anySatisfy(d -> {
            assertThat(d.getTargetQualifiedName()).isEqualTo("com.acme.Money#of(int)");     // library call, real name
            assertThat(d.getResolutionStatus()).isEqualTo(ResolutionStatus.RESOLVED);
        });
        assertThat(calls(List.of())).noneSatisfy(d -> assertThat(d.getTargetQualifiedName()).isEqualTo("com.example.core.Format#show(Money)"));
    }

    private static List<ExtractedDependency> calls(List<Path> jars) {
        JavaAstParser parser = new JavaAstParser();
        var jp = parser.createConfiguredParser(project, jars);
        ParsedSourceFile f = parser.parseSourceFile(jp, project, project.resolve("app/src/main/java/com/example/app/Use.java")).orElseThrow();
        return f.getDependencies();
    }

    @Test
    void gradleBuildWithBomAndCatalog() throws Exception {
        Path g = Files.createDirectories(tmp.resolve("gradle"));
        write(g.resolve("settings.gradle.kts"), "rootProject.name = \"g\"");
        write(g.resolve("gradle/libs.versions.toml"), """
                [versions]
                acme = "1.0"
                [libraries]
                acme-lib = { module = "com.acme:acme-lib", version.ref = "acme" }
                """);
        write(g.resolve("build.gradle.kts"), """
                dependencies {
                    implementation(platform("com.acme:acme-bom:2.0"))
                    implementation("com.acme:acme-lib")          // version from the BOM
                    testImplementation(libs.acme.lib)
                    implementation(project(":other"))
                }""");
        var read = GradleBuildReader.read(g);
        assertThat(read.boms()).extracting(GradleBuildReader.Coordinate::key).containsExactly("com.acme:acme-bom");
        assertThat(read.dependencies()).extracting(c -> c.key() + ":" + c.version()).containsExactly("com.acme:acme-lib:null");
        var cp = resolver().resolve(g);
        assertThat(cp.status()).as(cp.message()).isEqualTo(LibraryResolver.Status.RESOLVED);
        assertThat(cp.buildSystem()).isEqualTo("GRADLE");
        assertThat(cp.artifacts()).containsExactly("com.acme:acme-lib:1.0");
    }

    @Test
    void groovyDslSpringBootPluginAndProperties() throws Exception {
        Path g = Files.createDirectories(tmp.resolve("groovy"));
        write(g.resolve("gradle.properties"), "guavaVersion=33.0.0-jre");
        write(g.resolve("build.gradle"), """
                plugins { id 'org.springframework.boot' version '3.4.1' }
                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-web'
                    implementation "com.google.guava:guava:${guavaVersion}"
                    compileOnly 'org.projectlombok:lombok:1.18.36'
                    testImplementation group: 'x', name: 'y'   // map notation: not understood, reported
                }""");
        var read = GradleBuildReader.read(g);
        assertThat(read.boms()).extracting(c -> c.key() + ":" + c.version()).containsExactly("org.springframework.boot:spring-boot-dependencies:3.4.1");
        assertThat(read.dependencies()).extracting(c -> c.key() + ":" + c.version() + ":" + c.scope()).containsExactly(
                "org.springframework.boot:spring-boot-starter-web:null:compile", "com.google.guava:guava:33.0.0-jre:compile",
                "org.projectlombok:lombok:1.18.36:provided");
        assertThat(read.unparsed()).hasSize(1);
    }

    @Test
    void noBuildFileAndUnreachableRepository() throws Exception {
        Path plain = Files.createDirectories(tmp.resolve("plain"));
        assertThat(resolver().resolve(plain).status()).isEqualTo(LibraryResolver.Status.NO_BUILD_FILE);
        var off = new LibraryResolver(false, remote.toUri().toString(), tmp.resolve("c2").toString(), 50, 100, 60).resolve(project);
        assertThat(off.status()).isEqualTo(LibraryResolver.Status.DISABLED);
        var missing = new LibraryResolver(true, tmp.resolve("empty-remote").toUri().toString(), tmp.resolve("c3").toString(), 50, 100, 60).resolve(project);
        assertThat(missing.status()).isEqualTo(LibraryResolver.Status.FAILED);
        assertThat(missing.jars()).isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    private static void publishLibrary(Path repo, String g, String a, String v) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("libsrc/com/acme"));
        write(src.resolve("Money.java"), "package com.acme; public final class Money { public static Money of(int cents) { return new Money(); } }");
        Path classes = Files.createDirectories(tmp.resolve("libclasses"));
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), src.resolve("Money.java").toString());
        assertThat(rc).isZero();
        Path dir = Files.createDirectories(repo.resolve(g.replace('.', '/') + "/" + a + "/" + v));
        Path jar = dir.resolve(a + "-" + v + ".jar");
        try (OutputStream os = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(os)) {
            jos.putNextEntry(new JarEntry("com/acme/Money.class"));
            jos.write(Files.readAllBytes(classes.resolve("com/acme/Money.class")));
            jos.closeEntry();
        }
        write(dir.resolve(a + "-" + v + ".pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>" + g + "</groupId><artifactId>" + a
                + "</artifactId><version>" + v + "</version></project>");
        checksums(dir);
    }

    private static void publishBom(Path repo, String g, String a, String v, String managedA, String managedV) throws Exception {
        Path dir = Files.createDirectories(repo.resolve(g.replace('.', '/') + "/" + a + "/" + v));
        write(dir.resolve(a + "-" + v + ".pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>" + g + "</groupId><artifactId>" + a
                + "</artifactId><version>" + v + "</version><packaging>pom</packaging><dependencyManagement><dependencies><dependency><groupId>"
                + g + "</groupId><artifactId>" + managedA + "</artifactId><version>" + managedV + "</version></dependency></dependencies></dependencyManagement></project>");
        checksums(dir);
    }

    private static void checksums(Path dir) throws Exception {
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                byte[] b = Files.readAllBytes(f);
                for (String alg : List.of("SHA-1", "MD5")) {
                    String ext = alg.equals("SHA-1") ? ".sha1" : ".md5";
                    Files.writeString(f.resolveSibling(f.getFileName() + ext), HexFormat.of().formatHex(MessageDigest.getInstance(alg).digest(b)));
                }
            }
        }
    }

    private static void write(Path p, String s) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, s);
    }
}
