package com.changeimpact.diff.service;

import com.changeimpact.common.exception.BusinessException;
import com.changeimpact.diff.model.FileCategory;
import com.changeimpact.diff.model.FileChange;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Git commit comparison (JGit)")
class GitDiffServiceTest {

    @TempDir
    Path dir;

    private final GitDiffService service = new GitDiffService();

    private RevCommit commit(Git git, String msg) throws Exception {
        git.add().addFilepattern(".").call();
        git.add().addFilepattern(".").setUpdate(true).call();
        return git.commit().setMessage(msg).setSign(false).setAuthor("t", "t@e.com").setCommitter("t", "t@e.com").call();
    }

    @Test
    @DisplayName("detects added, modified, deleted and renamed files with hunks; ignores whitespace-only edits")
    void diff() throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            Path src = Files.createDirectories(dir.resolve("src/main/java/a"));
            Files.writeString(src.resolve("A.java"), "package a;\nclass A {\n  int x() { return 1; }\n}\n");
            StringBuilder big = new StringBuilder("package a;\nclass B {\n");
            for (int i = 0; i < 30; i++) big.append("  int m").append(i).append("() { return ").append(i).append("; }\n");
            Files.writeString(src.resolve("B.java"), big.append("}\n").toString());
            Files.writeString(src.resolve("C.java"), "package a;\nclass C {}\n");
            Files.writeString(dir.resolve("README.md"), "hello\n");
            RevCommit base = commit(git, "base");

            Files.writeString(src.resolve("A.java"), "package a;\nclass A {\n  int x() { return 2; }\n}\n");
            Files.move(src.resolve("B.java"), src.resolve("Renamed.java"));
            Files.delete(src.resolve("C.java"));
            Files.writeString(dir.resolve("README.md"), "hello   \n");               // whitespace only
            Files.createDirectories(dir.resolve("src/main/resources"));
            Files.writeString(dir.resolve("src/main/resources/application.yml"), "app:\n  x: 1\n");
            RevCommit head = commit(git, "head");

            Map<String, FileChange> byPath = service.diff(git.getRepository(), base.name(), head.name()).stream()
                    .collect(Collectors.toMap(FileChange::path, f -> f));
            assertEquals("MODIFIED", byPath.get("src/main/java/a/A.java").changeType());
            assertEquals(1, byPath.get("src/main/java/a/A.java").additions());
            assertEquals(3, byPath.get("src/main/java/a/A.java").hunks().get(0).newStart());
            assertEquals("RENAMED", byPath.get("src/main/java/a/Renamed.java").changeType());
            assertEquals("src/main/java/a/B.java", byPath.get("src/main/java/a/Renamed.java").oldPath());
            assertEquals("DELETED", byPath.get("src/main/java/a/C.java").changeType());
            assertEquals(FileCategory.CONFIG, byPath.get("src/main/resources/application.yml").category());
            assertEquals(0, byPath.get("README.md").additions(), "whitespace-only edits produce no changed lines");

            // ref resolution and snapshot export
            assertEquals(head.name(), service.resolveCommit(git.getRepository(), "main").name());
            assertEquals(base.name(), service.resolveCommit(git.getRepository(), base.name().substring(0, 8)).name());
            assertThrows(BusinessException.class, () -> service.resolveCommit(git.getRepository(), "nope"));
            assertThrows(BusinessException.class, () -> service.resolveCommit(git.getRepository(), "../etc"));
            int exported = service.exportJavaSources(git.getRepository(), base.name(), dir.resolve("snap"), List.of("target/"));
            assertEquals(3, exported);
            assertTrue(Files.exists(dir.resolve("snap/src/main/java/a/B.java")));
            assertTrue(service.readFile(git.getRepository(), head.name(), "src/main/resources/application.yml").isPresent());
        }
    }

    @Test
    @DisplayName("an unknown full SHA is COMMIT_NOT_FOUND, not a raw JGit error")
    void unknownCommit() throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            Files.writeString(dir.resolve("a.txt"), "x");
            commit(git, "c");
            BusinessException e = assertThrows(BusinessException.class,
                    () -> service.resolveCommit(git.getRepository(), "1234567890abcdef1234567890abcdef12345678"));
            assertEquals("COMMIT_NOT_FOUND", e.getErrorCode());
        }
    }
}
