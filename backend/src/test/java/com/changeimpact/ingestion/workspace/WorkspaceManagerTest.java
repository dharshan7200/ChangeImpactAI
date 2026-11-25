package com.changeimpact.ingestion.workspace;

import com.changeimpact.common.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;

import java.nio.file.Files;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkspaceManager Unit Tests")
class WorkspaceManagerTest {

    private WorkspaceManager workspaceManager;
    private AppProperties appProperties;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getIngestion().setWorkspaceBasePath(tempDir.toString());
        workspaceManager = new WorkspaceManager(appProperties);
    }

    @Test
    @DisplayName("Should resolve and create isolated repository workspace folder")
    void shouldResolveAndCreateWorkspace() {
        UUID repositoryId = UUID.randomUUID();
        Path workspace = workspaceManager.getWorkspacePath(repositoryId);

        assertNotNull(workspace);
        assertTrue(workspace.toFile().exists());
        assertTrue(workspace.startsWith(tempDir));
        assertEquals(repositoryId.toString(), workspace.getFileName().toString());
    }

    @Test
    @DisplayName("Should delete repository workspace cleanly")
    void shouldDeleteWorkspace() {
        UUID repositoryId = UUID.randomUUID();
        Path workspace = workspaceManager.getWorkspacePath(repositoryId);
        assertTrue(workspace.toFile().exists());

        workspaceManager.deleteWorkspace(repositoryId);
        assertFalse(workspace.toFile().exists());
    }

    @Test
    @DisplayName("Re-fetching an existing workspace must move HEAD to the newest remote commit")
    void refetchShouldAdvanceToLatestRemoteCommit() throws Exception {
        // A local "origin" repository stands in for GitHub (no network needed).
        Path origin = Files.createDirectories(tempDir.resolve("origin-repo"));
        try (Git originGit = Git.init().setDirectory(origin.toFile()).setInitialBranch("main").call()) {
            Files.writeString(origin.resolve("A.java"), "class A {}");
            originGit.add().addFilepattern(".").call();
            RevCommit first = originGit.commit().setMessage("first").setSign(false)
                    .setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();

            UUID repositoryId = UUID.randomUUID();
            String url = origin.toUri().toString();
            try (Git ws = workspaceManager.cloneOrFetchRepository(repositoryId, url, "main", null)) {
                assertEquals(first.name(), workspaceManager.getHeadCommitSha(ws));
            }

            // New commit lands on the remote after the first index.
            Files.writeString(origin.resolve("B.java"), "class B {}");
            originGit.add().addFilepattern(".").call();
            RevCommit second = originGit.commit().setMessage("second").setSign(false)
                    .setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();

            try (Git ws = workspaceManager.cloneOrFetchRepository(repositoryId, url, "main", null)) {
                assertEquals(second.name(), workspaceManager.getHeadCommitSha(ws),
                        "incremental fetch must check out the fetched commit, not the stale local branch");
                assertTrue(Files.exists(workspaceManager.getWorkspacePath(repositoryId).resolve("B.java")));
            }
        }
    }

    @Test
    @DisplayName("Fetching a branch that does not exist on the remote fails with a clear error")
    void fetchOfUnknownBranchShouldFail() throws Exception {
        Path origin = Files.createDirectories(tempDir.resolve("origin-repo2"));
        try (Git originGit = Git.init().setDirectory(origin.toFile()).setInitialBranch("main").call()) {
            Files.writeString(origin.resolve("A.java"), "class A {}");
            originGit.add().addFilepattern(".").call();
            originGit.commit().setMessage("first").setSign(false)
                    .setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
            UUID repositoryId = UUID.randomUUID();
            String url = origin.toUri().toString();
            workspaceManager.cloneOrFetchRepository(repositoryId, url, "main", null).close();

            Exception ex = assertThrows(Exception.class,
                    () -> workspaceManager.cloneOrFetchRepository(repositoryId, url, "does-not-exist", null));
            assertNotNull(ex.getMessage());
        }
    }

    @Test
    @DisplayName("work on one repository is serialised; other repositories are not blocked; a busy lock times out")
    void repositoryLock() throws Exception {
        java.util.UUID repo = java.util.UUID.randomUUID();
        java.util.concurrent.atomic.AtomicInteger inside = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxInside = new java.util.concurrent.atomic.AtomicInteger();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.List<java.util.concurrent.Future<Object>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> workspaceManager.withRepositoryLock(repo, 1, () -> {
                maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                Thread.sleep(50);
                inside.decrementAndGet();
                return null;
            })));
        }
        for (var f : futures) f.get();
        assertEquals(1, maxInside.get(), "never two jobs in the same workspace");

        var held = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        pool.submit(() -> workspaceManager.withRepositoryLock(repo, 1, () -> { held.countDown(); release.await(); return null; }));
        held.await();
        assertEquals("other", workspaceManager.withRepositoryLock(java.util.UUID.randomUUID(), 1, () -> "other"));
        assertThrows(Exception.class, () -> workspaceManager.withRepositoryLock(repo, 0, () -> null), "busy repository");
        release.countDown();
        pool.shutdown();
    }
}
