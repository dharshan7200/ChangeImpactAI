# ChangeImpact AI — Database Schema Reference

Managed by **Flyway**.

## Schema Migrations

### V1 — Users, Roles and Security Foundation (`V1__users_and_roles.sql`)
- `roles`: Standard user and administrator role definitions.
- `users`: Core account table with BCrypt passwords and lockout protection.
- `user_roles`: Mapping table linking users and assigned roles.
- `refresh_tokens`: SHA-256 hashed refresh tokens for stateless token rotation.

---

### V2 — GitHub Integration and Repository Management (`V2__github_and_repositories.sql`)
- `connected_accounts`: AES-256-GCM encrypted OAuth/PAT tokens.
- `repositories`: Connected GitHub projects with branch and status tracking.
- `repository_branches`: Tracked Git branches.
- `commits`: Commit history imported via JGit for diff tracking.
- `repository_indexes`: Audit records of indexing execution runs and stats.
- `analysis_jobs`: RabbitMQ background job tracking table with JSON payloads.
- `webhook_deliveries`: Deduplication log for GitHub webhooks.

---

### V3 — Symbols, Dependencies, and Architecture Rules (`V3__symbols_and_dependencies.sql`)

#### `source_files`
| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID | PRIMARY KEY | File identifier |
| `repository_id` | UUID | FK -> repositories(id) | Repository |
| `commit_sha` | VARCHAR(255) | NOT NULL | Associated commit |
| `relative_path` | VARCHAR(1024) | NOT NULL | Relative path in repo |
| `package_name` | VARCHAR(512) | | Package name |
| `lines_of_code` | INT | NOT NULL, DEFAULT 0 | Total LOC |
| `size_bytes` | BIGINT | NOT NULL, DEFAULT 0 | File size in bytes |
| `is_test` | BOOLEAN | NOT NULL, DEFAULT FALSE | Test file flag |

#### `symbols`
| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID | PRIMARY KEY | Symbol identifier |
| `repository_id` | UUID | FK -> repositories(id) | Repository |
| `source_file_id`| UUID | FK -> source_files(id) | File location |
| `commit_sha` | VARCHAR(255) | NOT NULL | Commit SHA |
| `symbol_type` | VARCHAR(50) | NOT NULL | CLASS, INTERFACE, RECORD, ENUM, METHOD, CONSTRUCTOR, FIELD |
| `qualified_name`| VARCHAR(1024)| NOT NULL | Full symbol signature |
| `simple_name` | VARCHAR(255) | NOT NULL | Simple identifier |
| `parent_symbol_id`| UUID | FK -> symbols(id) | Enclosing class/record |
| `return_type` | VARCHAR(512) | | Method/field type |
| `parameters` | TEXT | | Comma-separated parameter types |
| `visibility` | VARCHAR(20) | NOT NULL | PUBLIC, PROTECTED, PACKAGE_PRIVATE, PRIVATE |
| `is_static` | BOOLEAN | NOT NULL | Static modifier |
| `is_abstract` | BOOLEAN | NOT NULL | Abstract modifier |
| `is_final` | BOOLEAN | NOT NULL | Final modifier |
| `annotations` | TEXT[] | | Applied annotations |
| `start_line` | INT | NOT NULL | 1-based start line |
| `end_line` | INT | NOT NULL | 1-based end line |
| `resolution_status`| VARCHAR(50)| NOT NULL | RESOLVED, PARTIAL, UNRESOLVED, EXTERNAL |

#### `dependencies`
| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID | PRIMARY KEY | Dependency identifier |
| `repository_id` | UUID | FK -> repositories(id) | Repository |
| `commit_sha` | VARCHAR(255) | NOT NULL | Commit SHA |
| `source_symbol_id`| UUID | FK -> symbols(id) | Source symbol |
| `target_symbol_id`| UUID | FK -> symbols(id) (nullable) | Internal target symbol |
| `target_qualified_name`| VARCHAR(1024)| NOT NULL | Target signature |
| `dependency_type`| VARCHAR(50)| NOT NULL | EXTENDS, IMPLEMENTS, METHOD_CALL, CONSTRUCTOR_CALL, FIELD_ACCESS, etc. |
| `is_external` | BOOLEAN | NOT NULL, DEFAULT FALSE | External library / JDK flag |
| `source_location_line`| INT | | Line of call/usage |
| `resolution_status`| VARCHAR(50)| NOT NULL | RESOLVED, UNRESOLVED |

#### `architecture_rules`
Stores custom and system-wide boundary validation rules (e.g., `CIRCULAR_DEPENDENCY`, `LAYER_VIOLATION`).

#### `architecture_violations`
Records all circular loops and illegal layer dependencies detected by JGraphT and ArchUnit algorithms.
