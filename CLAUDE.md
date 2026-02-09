# PoliteAi

A Korean tone/politeness transformation tool. "Not a tool that writes for you, but a safety net that polishes your tone right before sending."

## PRD

See [docs/PRD.md](docs/PRD.md) for the full product requirements document.

## Architecture

### Frontend (React + TypeScript + Tailwind CSS)

Feature-Sliced Design (FSD) structure:

```
src/
  app/          → App entry, router, global styles
  pages/        → Route-level page components
  widgets/      → Self-contained UI blocks (transform-panel, result-panel, admin-dashboard)
  features/     → User actions with API definitions (transform, partial-rewrite, auth)
  entities/     → Business entities (persona, context, tone)
  shared/       → API client, UI components, Zustand stores, config, constants
```

FSD import rule: layers can only import from layers below them.
`app > pages > widgets > features > entities > shared`

### Backend (Spring Boot + Java 21)

Domain-Driven Design (DDD) structure:

```
backend/src/main/java/com/politeai/
  domain/           → Domain models, enums, repository interfaces, domain services
  application/      → Application services (use cases)
  infrastructure/   → External integrations (AI stub, security/JWT, persistence)
  interfaces/       → REST controllers, DTOs
```

## Development Commands

### Frontend

```bash
# Install dependencies
npm install

# Run dev server (port 5173, proxies /api to localhost:8080)
npm run dev

# Type check
npx tsc -b

# Build for production (tsc -b && vite build)
npm run build

# Lint
npm run lint
```

### Backend

```bash
# Run Spring Boot (requires Java 21)
cd backend && ./gradlew bootRun

# Build (includes tests)
cd backend && ./gradlew build

# Run tests only
cd backend && ./gradlew test
```

## Key Domain Concepts

- **Persona** (single select): BOSS(`직장 상사`), CLIENT(`고객`), PARENT(`학부모`), PROFESSOR(`교수`), COLLEAGUE(`동료`), OFFICIAL(`공식 기관`)
- **Context** (multi select): REQUEST(`요청`), SCHEDULE_DELAY(`일정 지연`), URGING(`독촉`), REJECTION(`거절`), APOLOGY(`사과`), COMPLAINT(`항의`), ANNOUNCEMENT(`공지`), FEEDBACK(`피드백`)
- **Tone Level** (single select): VERY_POLITE(`매우 공손`), POLITE(`공손`), NEUTRAL(`중립`), FIRM_BUT_RESPECTFUL(`단호하지만 예의있게`)

## Frontend Routes

| Path | Page | Description |
|------|------|-------------|
| `/` | HomePage | Main transformation interface with intro animation |
| `/login` | LoginPage | Login form (UI only, API not integrated) |
| `/signup` | SignupPage | Signup form (UI only, API not integrated) |
| `/admin` | AdminPage | Admin dashboard with mock metrics |

## Frontend Key Modules

### Widgets

| Widget | Description |
|--------|-------------|
| TransformPanel | Step-by-step transformation input (persona → context → tone → text) |
| ResultPanel | Displays transformed result, copy button, partial rewrite UI |
| AdminDashboard | Admin metrics dashboard with charts, heatmap, stats (mock data) |

### Shared UI Components

Button, Input, Textarea, Chip, ChipGroup, Header (responsive hamburger nav), Layout, StatCard

### Zustand Stores

| Store | State |
|-------|-------|
| useAuthStore | `isLoggedIn`, `isAdmin`, `email`, `setLoggedIn()`, `setLoggedOut()` |
| useTransformStore | `persona`, `contexts[]`, `toneLevel`, `originalText`, `userPrompt`, `transformedText`, `reset()` |

### API Client

`shared/api/client.ts` — Generic `ApiClient` with `post()` / `get()`, automatic JWT injection from `localStorage`, base URL `/api` (proxied to `localhost:8080` in dev via Vite).

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/auth/signup` | Register (email + password) |
| POST | `/api/v1/auth/login` | Login (returns JWT) |
| POST | `/api/v1/transform` | Full text transformation |
| POST | `/api/v1/transform/partial` | Partial rewrite (selected text only) |

All endpoints are `permitAll` in SecurityConfig. Auth endpoints require `@Valid` request bodies.

## API Documentation (Swagger / OpenAPI)

- **Swagger UI**: http://localhost:8080/swagger-ui/index.html
- **OpenAPI JSON**: http://localhost:8080/v3/api-docs
- Enabled in dev, disabled in prod (`application-prod.properties`)

> **TODO (도메인 연결 후 업데이트 필요):**
> 가비아 네임서버 → Route 53 연결 및 커스텀 도메인(예: `api.politeai.com`) 설정이 완료되면,
> 위 URL을 `https://api.politeai.com/swagger-ui/index.html` 등으로 업데이트하고
> Spring Boot의 OpenAPI server URL 설정도 커스텀 도메인으로 변경할 것.

## Backend Key Details

### Application Services

| Service | Methods | Description |
|---------|---------|-------------|
| AuthService | `signup()`, `login()` | User auth with BCrypt + JWT |
| TransformAppService | `transform()`, `partialRewrite()` | Orchestrates transformation use cases |

### Infrastructure

| Component | Description |
|-----------|-------------|
| AiTransformService | Implements `TransformService` — **currently a stub** returning formatted strings (TODO: OpenAI integration) |
| SecurityConfig | Stateless JWT, CSRF disabled, BCrypt password encoder |
| JwtProvider | JWT generation/validation (JJWT 0.12.6), secret via `jwt.secret` property |
| JwtAuthenticationFilter | Bearer token extraction and authentication |

### Domain Models

| Model | Type | Fields |
|-------|------|--------|
| User | JPA Entity | id, email, password, createdAt |
| TransformResult | Value Object | transformedText |
| Persona, SituationContext, ToneLevel | Enums | (see Key Domain Concepts) |

### Database

- Dev: H2 in-memory (`jdbc:h2:mem:politeai`), console at `/h2-console`
- Prod: RDS PostgreSQL (planned)
- JPA `ddl-auto=update`, SQL logging enabled in dev

## Responsive Design

Mobile-first approach using Tailwind CSS breakpoints (`sm: 640px`, `lg: 1024px`).

| Breakpoint | Layout |
|------------|--------|
| `< sm` (mobile) | Single column, hamburger nav, compact spacing/font |
| `>= sm` (tablet) | Inline header nav, relaxed spacing |
| `>= lg` (desktop) | Two-column main layout, 4-column admin grid, sticky result panel |

Key patterns:
- Responsive spacing: `p-4 sm:p-6`, `gap-3 sm:gap-4`, `py-6 sm:py-8`
- Responsive typography: `text-xl sm:text-2xl`, `text-xs sm:text-sm`
- Horizontal scroll for wide tables (heatmap) on mobile with `overflow-x-auto`
- `flex-wrap` for chip groups and retention metrics

## Constraints

- Original text: max 1,000 characters
- User prompt: max 80 characters
- Output: transformed text only (no explanations, emoji, or filler)
- MVP: no history storage, no file upload, no payment

## Tech Stack

- **Frontend:** React 19.2, TypeScript 5.9, Vite 7, Tailwind CSS v4, Zustand 5, TanStack Query 5, React Router 7
- **Backend:** Spring Boot 3.5.0, Java 21, Spring Security (JWT/JJWT 0.12.6), Spring Data JPA, springdoc-openapi 2.8.4, Lombok
- **Database:** H2 (dev) → RDS PostgreSQL (prod)
- **AI:** OpenAI API (server-side prompt assembly, currently stub)
- **Infra:** AWS (EC2, RDS, S3, CloudFront, ECR, CloudWatch)
- **CI/CD:** GitHub Actions
- **Vite plugins:** `@vitejs/plugin-react` (with React Compiler via `babel-plugin-react-compiler`), `@tailwindcss/vite`

## CI/CD & Deployment

Monorepo with path-based triggers — frontend and backend deploy independently.

```
GitHub push (main)
  ├─ src/** changed     → frontend.yml → Build → S3 + CloudFront invalidation
  └─ backend/** changed → backend.yml  → Build → Docker → ECR → EC2
```

### Frontend Pipeline (`.github/workflows/frontend.yml`)

**Triggers:** push/PR to `main` when `src/**`, `index.html`, `package*.json`, `vite.config.ts`, `tsconfig*.json`, `eslint.config.js` change.

1. Node 22 + `npm ci` → lint → type check → `npm run build` → upload artifact
2. Deploy (main push only): download artifact → `aws s3 sync dist/ s3://BUCKET --delete` → CloudFront `/*` invalidation

### Backend Pipeline (`.github/workflows/backend.yml`)

**Triggers:** push/PR to `main` when `backend/**` changes.

1. Java 21 (Temurin) + Gradle → `./gradlew build` (includes tests) → upload JAR artifact
2. Deploy (main push only): build JAR (`-x test`) → ECR login → Docker build/push (`:$SHA` + `:latest`) → SSH to EC2 → pull + restart container with `--env-file`

### Required GitHub Secrets

| Secret | Description |
|--------|-------------|
| `AWS_ACCESS_KEY_ID` | IAM access key |
| `AWS_SECRET_ACCESS_KEY` | IAM secret key |
| `S3_BUCKET_NAME` | Frontend S3 bucket |
| `CLOUDFRONT_DISTRIBUTION_ID` | CloudFront distribution ID |
| `ECR_REGISTRY` | ECR registry URL (e.g. `123456789.dkr.ecr.ap-northeast-2.amazonaws.com`) |
| `EC2_HOST` | EC2 public IP or domain |
| `EC2_USERNAME` | EC2 SSH user (e.g. `ec2-user`) |
| `EC2_SSH_KEY` | EC2 SSH private key |

### Local Docker

```bash
# Backend only (H2 in-memory DB)
cd backend && ./gradlew build && cd .. && docker compose up
```

## Implementation Status

### Done
- Frontend FSD structure with all pages, widgets, shared components
- Backend DDD structure with all controllers, services, DTOs, domain models
- JWT authentication (signup/login backend endpoints fully working)
- Responsive design (mobile-first, Tailwind CSS v4)
- GitHub Actions CI/CD pipelines (frontend + backend)
- Docker configuration (Dockerfile + docker-compose.yml)
- Swagger/OpenAPI documentation setup

### In Progress / Not Integrated
- **AiTransformService**: stub implementation — returns formatted strings, not AI-generated (TODO: OpenAI API integration)
- **Frontend transform flow**: TransformPanel UI exists but uses dummy data, not calling backend API
- **Frontend auth flow**: Login/Signup forms exist but don't call backend API endpoints
- **Frontend partial rewrite**: UI ready in ResultPanel, API defined in features, not wired up

### Out of Scope (MVP)
- History storage
- File upload
- Payments
