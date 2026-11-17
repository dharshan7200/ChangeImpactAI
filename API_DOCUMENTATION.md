# ChangeImpact AI — REST API Documentation

Base URL: `http://localhost:8080/api/v1`  
Swagger UI: `http://localhost:8080/swagger-ui.html`  
OpenAPI JSON: `http://localhost:8080/api-docs`

---

## 1. Authentication Endpoints

### Register User
- **POST** `/auth/register`
- **Request Body**:
```json
{
  "email": "developer@example.com",
  "username": "developer",
  "password": "Password123",
  "displayName": "Alex Dev"
}
```
- **Response**: `201 Created` with `AuthResponse`.

### Login
- **POST** `/auth/login`
- **Request Body**:
```json
{
  "email": "developer@example.com",
  "password": "Password123"
}
```
- **Response**: `200 OK` with `accessToken`, `refreshToken`.

### Refresh Access Token
- **POST** `/auth/refresh`
- **Request Body**:
```json
{
  "refreshToken": "eyJhbGciOi..."
}
```

---

## 2. GitHub Account Endpoints
*Requires `Authorization: Bearer <token>`*

### Connect GitHub Account
- **POST** `/github/connect`
- **Request Body**:
```json
{
  "accessToken": "ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
}
```

### List Connected Accounts
- **GET** `/github/accounts`

### List Remote GitHub Repositories
- **GET** `/github/accounts/{accountId}/repos?page=1`

---

## 3. Repository Management Endpoints
*Requires `Authorization: Bearer <token>`*

### Connect Repository
- **POST** `/repositories/connect`

### List Repositories
- **GET** `/repositories?page=0&size=20`

### Get Repository Details
- **GET** `/repositories/{id}`

### Trigger Indexing Job
- **POST** `/repositories/{id}/index`

### Get Indexing Status
- **GET** `/repositories/{id}/index-status`

---

## 4. Code Intelligence & Dependency Graph Endpoints (Phase 2)
*Requires `Authorization: Bearer <token>`*

### Search AST Symbols
- **GET** `/code/repositories/{repoId}/symbols?symbolType=CLASS&query=Order&page=0&size=30`
- **Description**: Searches extracted AST classes, interfaces, records, methods, and constructors with 1-based source line ranges.

### Get Symbol Details
- **GET** `/code/symbols/{symbolId}`
- **Description**: Detailed metadata for an individual symbol including return types, parameters, visibility, and annotations.

### Get Forward Dependencies
- **GET** `/code/symbols/{symbolId}/dependencies?depth=2`
- **Description**: Returns all symbols that the specified symbol depends upon (direct or transitive up to `depth`).

### Get Reverse Dependents (Blast Radius)
- **GET** `/code/symbols/{symbolId}/dependents?depth=3`
- **Description**: Returns all symbols that directly or transitively depend on the specified symbol. Used to compute change blast radiuses.

### Get Interactive Dependency Graph Snapshot
- **GET** `/code/repositories/{repoId}/graph?packageFilter=com.example`
- **Description**: Returns nodes and edges formatted for React Flow interactive canvas rendering.

### Calculate Shortest Dependency Path
- **GET** `/code/repositories/{repoId}/paths?sourceSymbolId={s}&targetSymbolId={t}`
- **Description**: Calculates the shortest directed dependency path between two symbols using Dijkstra's algorithm.

### List Architecture Boundary Violations
- **GET** `/code/repositories/{repoId}/architecture/violations`
- **Description**: Returns circular dependency loops and layer violations detected in the repository AST and graph.

### Get Coupling & Instability Metrics
- **GET** `/code/repositories/{repoId}/metrics`
- **Description**: Returns Afferent Coupling ($Ca$), Efferent Coupling ($Ce$), Instability ($I$), and hotspot classifications.

---

## 5. Background Job Monitoring Endpoints
*Requires `Authorization: Bearer <token>`*

### Get Job Details & Logs
- **GET** `/jobs/{id}`

### List Jobs for Repository
- **GET** `/jobs/repository/{repositoryId}?page=0&size=20`
