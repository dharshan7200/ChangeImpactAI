# ChangeImpact AI — System Architecture

```mermaid
flowchart TD
    subgraph Client["Client Layer (React / Vite)"]
        UI[React 18 SPA]
        Router[React Router]
        Query[TanStack Query]
        Axios[Axios Interceptor + JWT]
    end

    subgraph API["Backend API Layer (Spring Boot 3.5.1)"]
        Security[Spring Security + JwtAuthenticationFilter]
        AuthCtrl[AuthController]
        RepoCtrl[RepositoryController]
        GhCtrl[GitHubController]
        JobCtrl[JobController]
    end

    subgraph Services["Core Domain Services"]
        AuthSvc[AuthService]
        RepoSvc[RepositoryService]
        GhSvc[GitHubAccountService]
        IngestSvc[IngestionService]
        WorkspaceMgr[WorkspaceManager]
        Scanner[JavaFileScanner]
    end

    subgraph Messaging["Asynchronous Queue (RabbitMQ)"]
        ExMain[changeimpact.main Exchange]
        QIndex[changeimpact.repository.index]
        QDLQ[changeimpact.repository.index.dlq]
    end

    subgraph Storage["Persistence & Isolation"]
        PG[(PostgreSQL 16 + pgvector)]
        Redis[(Redis 7)]
        Workspaces[Isolated Git Workspaces on Disk]
    end

    UI --> Axios
    Axios --> Security
    Security --> AuthCtrl & RepoCtrl & GhCtrl & JobCtrl
    AuthCtrl --> AuthSvc
    RepoCtrl --> RepoSvc
    GhCtrl --> GhSvc
    RepoSvc --> ExMain
    ExMain --> QIndex
    QIndex --> IngestSvc
    IngestSvc --> WorkspaceMgr & Scanner
    WorkspaceMgr --> Workspaces
    AuthSvc & RepoSvc & GhSvc & IngestSvc --> PG
```

## Security Architecture
1. **Stateless JWTs**: Access tokens expire in 15 minutes and carry HMAC-SHA256 signatures with minimal claims (userId, email, username).
2. **Refresh Token Rotation**: Refresh tokens expire in 7 days, stored strictly as SHA-256 hashes in PostgreSQL.
3. **AES-256-GCM Token Encryption**: Connected GitHub tokens are encrypted before saving and decrypted on-the-fly for JGit or WebClient operations.
4. **JGit Workspace Sandbox**: Operations are confined to isolated directory structures (`changeimpact/workspaces/<uuid>`). Path traversal and symlink escapes are actively checked and rejected.

