# ChangeImpact AI — Implementation Notes & Manual Verification Guide

## Local Environment Setup

### 1. Prerequisites
- **Java 17 LTS** (installed: Oracle JDK 17.0.12)
- **Maven 3.9+** (installed: Apache Maven 3.9.12)
- **Node.js 20+ & npm 11+** (installed: Node 20.19.5, npm 11.13.0)
- **PostgreSQL 16**, **Redis 7**, **RabbitMQ 3.13** (run via Docker Compose)

---

### 2. Starting Infrastructure Services
```bash
docker compose up -d
```
Spins up PostgreSQL 16 (pgvector), Redis 7, and RabbitMQ 3.13 Management (`http://localhost:15672`).

---

### 3. Running Backend
```bash
cd backend
mvn spring-boot:run
```
- Flyway automatically runs database migrations `V1`, `V2`, and `V3`.
- Swagger UI available at: `http://localhost:8080/swagger-ui.html`
- Actuator health check: `http://localhost:8080/actuator/health`

---

### 4. Running Frontend
```bash
cd frontend
npm run dev
```
Open `http://localhost:5173` in your browser.

---

### 5. Verification Checklist

#### Phase 1 Verification
1. Register/Login: `http://localhost:5173/login`
2. Connect GitHub Account: Add a GitHub Personal Access Token at `/github`.
3. Connect a Java Repository: Import a Java repository at `/repositories`.
4. Trigger Ingestion: Click "Index" and verify status transitions to `INDEXED`.

#### Phase 2 Verification (Static Code Intelligence & Dependency Graph)
1. **Interactive Dependency Graph Explorer**:
   - Open repository details at `http://localhost:5173/repositories/{id}`.
   - Click on the **Interactive Dependency Graph** tab.
   - Verify nodes represent extracted Java classes, interfaces, records, and methods.
   - Test zoom, pan, and mini-map controls.
   - Filter by package prefix (e.g., `com.example`) and click **Apply**.
2. **Blast Radius Analysis**:
   - Click any symbol node in the canvas to open the **Symbol Inspector**.
   - Click **Calculate Blast Radius**.
   - Verify that all direct and transitive dependents up to depth 3 glow red and edges animate.
3. **Architecture Boundaries & Hotspots**:
   - Click on the **Architecture & Hotspots** tab.
   - Review detected circular dependencies and layer violations (e.g. Controllers directly calling Repositories).
   - Review Robert C. Martin's instability metrics ($I = Ce / (Ca + Ce)$) and identified coupling hotspots.
