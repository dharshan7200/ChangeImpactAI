# ChangeImpact AI — Technology Stack & Compatibility Matrix

## Backend
| Component | Technology | Version | Purpose |
|---|---|---|---|
| Runtime | Java | 17 LTS | Primary language runtime |
| Framework | Spring Boot | 3.5.1 | Enterprise application framework |
| AI Integration | Spring AI | 1.0.1 | LLM abstraction, tool calling, hybrid RAG |
| Database | PostgreSQL + pgvector | 16 | Relational & vector embeddings store |
| Migration | Flyway | 11.17.2 | Versioned SQL schema migrations |
| Cache & Rate Limiting | Redis | 7-alpine | Session caching and API rate limiting |
| Message Broker | RabbitMQ | 3.13-management | Decoupled background job processing |
| Git Engine | JGit | 7.5.0 | In-process Git repository inspection and cloning |
| AST & Symbol Solver | JavaParser | 3.28.2 | Abstract Syntax Tree and symbol resolution |
| Graph Engine | JGraphT | 1.5.3 | Directed symbol dependency graph algorithms |
| Security | Spring Security + JJWT | 6.5.1 / 0.12.6 | JWT authentication and RBAC |
| Documentation | SpringDoc OpenAPI | 2.8.9 | Automated Swagger 3 documentation |

## Frontend
| Component | Technology | Version | Purpose |
|---|---|---|---|
| Library | React | 18.3.1 | Component-based user interface |
| Build Tool | Vite | 6.0.3 | Fast frontend dev server and bundler |
| Language | TypeScript | 5.6.3 | Type-safe client development |
| Styling | Tailwind CSS | 3.4.17 | Utility-first CSS styling |
| Icons | Lucide React | 0.468.0 | Clean SVG developer icons |
| Querying | TanStack Query | 5.62.7 | Server state caching and background fetching |
| Routing | React Router DOM | 6.28.0 | Client-side SPA routing |

