package com.changeimpact.ingestion.workspace;

import com.changeimpact.common.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}

