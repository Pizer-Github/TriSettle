# Technical Setup & Operational Guide

## B2B Invoice Factoring & Smart Escrow Network

---

## 1. Prerequisites

Before running the application locally or in containers, ensure the following are installed:

| Tool | Minimum Version | Recommended | Notes |
|------|-----------------|-------------|-------|
| **Git** | 2.40+ | Latest | Required for version control |
| **Java Development Kit (JDK)** | 21 (LTS) | Eclipse Temurin 21 | Used by Spring Boot backend |
| **Node.js** | 20.x (LTS) | 20.18+ | Required for Angular workspace |
| **npm** | 10.x+ | 10.8+ | Bundled with Node.js |
| **Docker Desktop** | 4.25+ | Latest | Required for containerized stack |
| **Docker Compose** | 2.20+ | Latest | Included with Docker Desktop |

*Note on Maven:* A standalone Maven installation is **not** required. The repository bundles the Maven Wrapper (`mvnw` on Linux/macOS and `mvnw.cmd` on Windows).

---

## 2. Repository Structure

```
TCS_Project/
├── .github/
│   └── workflows/
│       └── ci.yml               # Automated CI pipeline (build, test, docker lint)
├── backend/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/tcs/invoicefactoring/
│   │   │   │   ├── InvoiceFactoringApplication.java  # Main application entry point
│   │   │   │   ├── config/                           # Security & CORS configuration
│   │   │   │   ├── common/                           # Standard API envelope, exceptions, logging filter
│   │   │   │   ├── identity/                         # Identity domain, auth service, DTOs, controller
│   │   │   │   ├── security/                         # JWT service, auth filter, tenant context, RLS service
│   │   │   │   ├── invoice/                          # Stub (Milestone 2)
│   │   │   │   ├── risk/                             # Stub (Milestone 2)
│   │   │   │   ├── financing/                        # Stub (Milestone 3)
│   │   │   │   ├── agreement/                        # Stub (Milestone 3)
│   │   │   │   ├── ledger/                           # Stub (Milestone 4)
│   │   │   │   ├── payment/                          # Stub (Milestone 4)
│   │   │   │   └── admin/                            # Stub (Milestone 5)
│   │   │   └── resources/
│   │   │       ├── application.yml                   # Base configuration
│   │   │       ├── application-local.yml             # Local development overrides
│   │   │       ├── application-prod.yml              # Production profile
│   │   │       ├── logback-spring.xml                # Structured JSON / console logging
│   │   │       └── db/migration/                     # Flyway SQL migrations (V1 - V4)
│   │   └── test/                                     # Automated test suites (Flyway, Auth, Tenant RLS)
│   ├── pom.xml                                       # Maven build descriptor
│   └── mvnw / mvnw.cmd                               # Maven wrappers
├── frontend/
│   ├── src/
│   │   ├── app/
│   │   │   ├── core/                                 # Auth service, JWT interceptor, route guards, API config
│   │   │   ├── features/                             # SME & Financier portal feature modules
│   │   │   ├── shared/layout/                        # Responsive main layout with navigation
│   │   │   ├── pages/login/                          # Reactive login component
│   │   │   ├── app.config.ts                         # Angular providers
│   │   │   └── app.routes.ts                         # Route definitions
│   │   ├── environments/                             # Environment configs (dev/prod)
│   │   └── index.html
│   ├── angular.json                                  # Angular workspace configuration
│   └── package.json                                  # NPM dependencies and build scripts
├── docker/
│   ├── backend/Dockerfile                            # Multi-stage JDK 21 alpine container
│   ├── frontend/Dockerfile                           # Node 20 dev server container
│   └── postgres/init/00_init_roles.sh                # Postgres restricted runtime role init
├── docker-compose.yml                                # Containerized orchestration
├── .env.example                                      # Environment template (safe to commit)
└── SETUP.md                                          # This technical guide
```

---

## 3. Required Environment Variables

Copy `.env.example` to `.env` in the project root:

```bash
cp .env.example .env
```

| Variable | Description | Example / Default |
|----------|-------------|-------------------|
| `POSTGRES_DB` | Database name | `invoice_factoring` |
| `POSTGRES_USER` | Schema owner / migration user | `app_owner` |
| `POSTGRES_PASSWORD` | Owner password | Strong password |
| `POSTGRES_PORT` | Host exposed port | `5432` |
| `APP_DB_USER` | Runtime restricted role (no BYPASSRLS) | `app_worker` |
| `APP_DB_PASSWORD` | Worker password | Strong password |
| `JWT_SECRET` | HMAC-SHA secret (min 64 chars) | Cryptographically random string |
| `JWT_EXPIRATION_MS` | Token validity duration in ms | `3600000` (1 hour) |
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `local` |
| `BACKEND_PORT` | Backend exposed port | `8080` |
| `FRONTEND_PORT` | Frontend exposed port | `4200` |
| `API_BASE_URL` | Frontend API target | `http://localhost:8080` |

---

## 4. Docker Startup Instructions

### Start All Services
```bash
docker compose up -d
```

### Inspect Container Health
```bash
docker compose ps
```
Both `postgres` and `backend` define automated health checks (`pg_isready` and Spring Boot Actuator `/actuator/health`).

### View Live Logs
```bash
docker compose logs -f
```

### Stop Services
```bash
docker compose down
```

### Clean Reset (Destroys local database volume)
```bash
docker compose down -v
```

---

## 5. Database Migration Instructions

Database migrations are managed via **Flyway** and execute automatically when the backend boots.

### Migration Files
- `V1__create_organizations.sql`: Tenant organisations table and indexes.
- `V2__create_users.sql`: Platform user accounts table with BCrypt hash storage.
- `V3__create_user_organizations.sql`: User-to-organisation role mapping.
- `V4__enable_rls_foundation.sql`: Row-Level Security enablement, runtime role grants, and tenant isolation policies.

### Manual Migration Check (CLI)
If connecting directly to PostgreSQL:
```bash
psql -h localhost -p 5432 -U app_owner -d invoice_factoring -c "SELECT version, description, success FROM flyway_schema_history;"
```

---

## 6. Backend Startup (Local Development)

### Prerequisites:
PostgreSQL must be running (either via `docker compose up -d postgres` or local PostgreSQL instance).

### Run with Maven Wrapper:
**Linux / macOS:**
```bash
cd backend
export JWT_SECRET="your-64-character-minimum-random-secret-key-for-development"
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

**Windows PowerShell:**
```powershell
cd backend
$env:JWT_SECRET="your-64-character-minimum-random-secret-key-for-development"
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

### Health Check:
```bash
curl http://localhost:8080/actuator/health
```
Expected response:
```json
{"status":"UP"}
```

---

## 7. Frontend Startup (Local Development)

### Install Dependencies:
```bash
cd frontend
npm install
```

### Start Dev Server:
```bash
npm start
```
The application will be accessible at: `http://localhost:4200`

---

## 8. Automated Test Execution

### Backend Tests (JUnit 5 + Testcontainers + MockMvc)
```bash
cd backend
./mvnw test
```
This executes:
- `FlywayMigrationTest`: Validates clean migration execution against a fresh PostgreSQL container.
- `JwtServiceTest`: Validates HMAC signing, claim extraction, and tampering detection.
- `TenantIsolationIT`: Validates transaction-local RLS context and tenant boundaries.
- `AuthControllerIT`: Validates registration, login, validation errors, and public/protected security filters.

### Frontend Tests (Jasmine + Karma + ChromeHeadless)
```bash
cd frontend
npm test
```

---

## 9. CI Execution

GitHub Actions runs automatically on every push and pull request to `main`:
- **Backend Job:** Compiles on Java 21, executes all unit/integration tests with Testcontainers, and saves test reports.
- **Frontend Job:** Sets up Node.js 20, runs clean install, compiles production bundle, and executes Karma unit tests headlessly.
- **Docker Validation Job:** Validates `docker-compose.yml` and both Dockerfiles.

---

## 10. Common Troubleshooting Steps

### 1. "JWT secret must be at least 64 characters"
**Cause:** `JWT_SECRET` is unset or too short.  
**Resolution:** Generate a 64+ char random string and export it or add it to `.env`:
```bash
openssl rand -base64 64
```

### 2. "Flyway: role 'app_worker' does not exist"
**Cause:** Manual database initialized without the runtime role.  
**Resolution:** `V4__enable_rls_foundation.sql` includes an idempotent `DO $$ BEGIN IF NOT EXISTS...` block that auto-creates `app_worker` if missing.

### 3. Frontend CORS Errors
**Cause:** Frontend running on a host/port not matching `SecurityConfig`.  
**Resolution:** `SecurityConfig` allows `http://localhost:[*]` and `http://127.0.0.1:[*]`. Ensure you access the frontend through `localhost` rather than custom DNS aliases.
