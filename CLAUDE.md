# PoliteAi

A Korean tone/politeness transformation tool. "Not a tool that writes for you, but a safety net that polishes your tone right before sending."

## PRD

See [docs/PRD.md](docs/PRD.md) for the full product requirements document.

## Architecture

### Frontend (React + TypeScript + Tailwind CSS)

Feature-Sliced Design (FSD) structure:

```
src/
  app/          → App entry, providers, global styles
  pages/        → Route-level page components
  widgets/      → Self-contained UI blocks (transform-panel, result-panel)
  features/     → User actions (transform, partial-rewrite, auth)
  entities/     → Business entities (persona, context, tone)
  shared/       → Shared API client, UI components, config, utilities
```

FSD import rule: layers can only import from layers below them.
`app > pages > widgets > features > entities > shared`

### Backend (Spring Boot + Java 21)

Domain-Driven Design (DDD) structure:

```
backend/src/main/java/com/politeai/
  domain/           → Domain models, enums, repository interfaces, domain services
  application/      → Application services (use cases)
  infrastructure/   → External integrations (AI, security, persistence)
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

# Build for production
npm run build

# Lint
npm run lint
```

### Backend

```bash
# Run Spring Boot (requires Java 21)
cd backend && ./gradlew bootRun

# Build
cd backend && ./gradlew build

# Run tests
cd backend && ./gradlew test
```

## Key Domain Concepts

- **Persona** (single select): BOSS, CLIENT, PARENT, PROFESSOR, COLLEAGUE, OFFICIAL
- **Context** (multi select): REQUEST, SCHEDULE_DELAY, URGING, REJECTION, APOLOGY, COMPLAINT, ANNOUNCEMENT, FEEDBACK
- **Tone Level**: VERY_POLITE, POLITE, NEUTRAL, FIRM_BUT_RESPECTFUL

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/auth/signup` | Register (email + password) |
| POST | `/api/v1/auth/login` | Login (returns JWT) |
| POST | `/api/v1/transform` | Full text transformation |
| POST | `/api/v1/transform/partial` | Partial rewrite (selected text only) |

## Constraints

- Original text: max 1,000 characters
- User prompt: max 80 characters
- Output: transformed text only (no explanations, emoji, or filler)
- MVP: no history storage, no file upload, no payment

## Tech Stack

- **Frontend:** React 19, TypeScript, Vite, Tailwind CSS v4
- **Backend:** Spring Boot 3.5, Java 21, Spring Security (JWT), Spring Data JPA
- **Database:** H2 (dev) → RDS PostgreSQL (prod)
- **AI:** OpenAI API (server-side prompt assembly)
- **Infra:** AWS (EC2, RDS, S3, CloudFront, ECR, CloudWatch)
- **CI/CD:** GitHub Actions

## CI/CD & Deployment

Monorepo with path-based triggers — frontend and backend deploy independently.

```
GitHub push (main)
  ├─ src/** changed     → frontend.yml → Build → S3 + CloudFront invalidation
  └─ backend/** changed → backend.yml  → Build → Docker → ECR → EC2
```

### Frontend Pipeline (`.github/workflows/frontend.yml`)

1. `npm ci` → lint → type check → `npm run build`
2. `aws s3 sync dist/ s3://BUCKET --delete`
3. CloudFront cache invalidation

### Backend Pipeline (`.github/workflows/backend.yml`)

1. `./gradlew build` (includes tests)
2. Docker build → push to ECR
3. SSH into EC2 → pull latest image → restart container

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
