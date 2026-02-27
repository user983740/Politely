# PoliteAi

A Korean tone/politeness transformation tool. "Not a tool that writes for you, but a safety net that polishes your tone right before sending."

**PRD**: [docs/PRD.md](docs/PRD.md)

## Architecture Docs (auto-loaded per directory)

| Location | What's inside | Auto-loaded when |
|----------|---------------|-----------------|
| `src/CLAUDE.md` | FSD structure, routes, widgets, stores, components, SEO, responsive design | Working on frontend files |
| `app/CLAUDE.md` | FastAPI backend overview, services, DB, auth flow, config | Working on backend files |
| `app/pipeline/CLAUDE.md` | Pipeline components table + full pipeline flow detail | Working on pipeline code |
| `app/api/CLAUDE.md` | REST endpoints, Pydantic DTOs, SSE events, tier system | Working on API layer |
| `docs/pipeline-deep-dive.md` | Pipeline full deep-dive: all types, functions (input/processing/output), execution flow | Working on pipeline code |

**Rule: When you modify code, update the corresponding CLAUDE.md file(s) in the same directory to keep them in sync. Additionally, when modifying pipeline code (`app/pipeline/`, `app/services/transform_app_service.py`, `app/models/domain.py`, `app/models/enums.py`), also update `docs/pipeline-deep-dive.md` for the affected functions/types.**

## Development Commands

```bash
# Frontend
npm install          # Install dependencies
npm run dev          # Dev server (port 5173, proxies /api to EC2 43.203.44.188:8080)
npm run dev:local    # Dev server (port 5173, proxies /api to localhost:8080)
npm run build        # Production build (tsc -b && vite build)
npm run lint         # ESLint
npx tsc -b           # Type check only

# Backend
pip install -e ".[dev]"                         # Install with dev deps
uvicorn app.main:app --reload --port 8080       # Dev server
pytest app/tests/                               # Run all 68 tests
ruff check app/                                 # Lint
```

## Auto-Context Loading (Agent-Delegated Code Reading)

When the user requests code exploration, modification, or debugging — **delegate codebase reading to an Explore agent** instead of reading files inline. This keeps raw file contents out of the main context window.

**Trigger:** The `UserPromptSubmit` hook injects 영역/의도/참조 hints. When any code-related 영역 is detected (frontend, backend, pipeline, api, database, rag), spawn an Explore agent before starting work.

**How:**
1. Read the hook's 참조 (reference) CLAUDE.md first if needed for structural context
2. Spawn `Explore` agent with the user's question + detected 영역 as search scope
3. Use the agent's returned summary to inform your response — do NOT re-read the same files inline

**Thoroughness guide:**

| Scenario | Level | Example |
|----------|-------|---------|
| Single module question, simple bug | `quick` | "StructureLabelService에서 fallback 어떻게 동작해?" |
| Cross-module change, debugging flow | `medium` | "라벨링 결과가 변환에 어떻게 전달되는지 추적해줘" |
| Architecture question, large refactor | `very thorough` | "파이프라인 전체 데이터 흐름 분석해줘" |

**Skip agent (read directly) when:**
- Target is a single known file (e.g., "transform.py 수정해줘")
- The user specifies exact file path and line
- Simple config/env changes

## Backend Debugging (PM2 Auto-Debug)

When the user mentions backend errors, crashes, 500 errors, startup failures, or asks to check the backend — **automatically invoke the `/backend-debug` skill** without waiting for explicit PM2 mention. The agent will:
1. Start the backend via PM2 (using `ecosystem.config.cjs`) if not already running
2. Collect and analyze error/stdout logs
3. Return structured error analysis

After receiving the analysis, read the identified source files and apply fixes directly.

**PM2 commands reference:**
```bash
pm2 start ecosystem.config.cjs   # Start backend
pm2 logs politeai-backend --err   # Error logs (real-time)
pm2 restart politeai-backend      # Restart
pm2 stop politeai-backend         # Stop
pm2 status                        # Process list
pm2 flush                         # Clear logs
```

## Constraints

| Constraint | FREE | PAID |
|------------|------|------|
| Original text max length | 300 chars | 2000 chars |
| User prompt | disabled | max 500 chars |
| Sender info | max 100 chars | max 100 chars |
| LLM max output tokens | 2000 | 4000 |

- Output: transformed text only (no explanations, emoji, or filler)
- MVP: no history storage, no file upload, no payment
- All users currently treated as PAID tier

## Pipeline Summary

**All transforms use the text-only pipeline** (no metadata selection). SA intent drives the final transform. T01_GENERAL template fixed, POLITE tone fixed. 3~4 LLM calls (SA + Label + Cushion + Final).

| LLM Call | Model | Always? | Thinking | Purpose |
|----------|-------|---------|----------|---------|
| SituationAnalysis | gpt-4o-mini (temp=0.2) | Yes | — | Facts + intent extraction |
| StructureLabel | gemini-2.5-flash-lite (temp=0.2) | Yes | 512 | 14-label segment classification |
| CushionStrategy | gemini-2.5-flash-lite (temp=0.3) | YELLOW 있을 때 | 512 | Per-YELLOW 쿠션 전략 (병렬 호출) |
| Final Transform | gemini-2.5-flash (temp=0.85) | Yes | dynamic (512/768/1024) | Template-guided rewriting |
| LlmSegmentRefiner | gpt-4o-mini (temp=0.0) | Optional (>30 chars) | — | Long segment splitting |
| RAG Embedding | text-embedding-3-small | Optional (RAG_ENABLED) | — | Query embedding for RAG retrieval |

Labeling uses Gemini primary + gpt-4o-mini fallback chain for all-GREEN recovery. Final retry doubles thinking budget (capped at 1024).

3-tier labels: GREEN(5) / YELLOW(5) / RED(4) = 14 labels. T01_GENERAL template with S2 enforcement. OutputValidator 14 rules, 1 retry on ERROR or retryable WARNING.

Input: original text + optional sender_info + optional user_prompt (SA context). Details → `docs/pipeline-deep-dive.md`

### RAG (Retrieval-Augmented Generation)

Optional in-memory vector search over ~275 seed expressions. Disabled by default (`RAG_ENABLED=false`).

| Category | Injection | Tone | Threshold | Top-K | Seeds |
|----------|-----------|------|-----------|-------|-------|
| `expression_pool` | System prompt | Reference | 0.78 | 5 | 84 |
| `cushion` | System prompt | Reference | 0.78 | 3 | 35 |
| `forbidden` | System prompt | **Mandatory ban** | 0.72 | 3 | 35 |
| `policy` | User message | Reference | 0.82 | 3 | 49 |
| `example` | User message | Example | 0.80 | 2 | 42 |
| `domain_context` | User message | Reference | 0.82 | 2 | 30 |

Files: `app/models/rag.py`, `app/services/embedding_service.py`, `app/services/rag_service.py`, `app/db/rag_repository.py`, `app/api/v1/rag_admin.py`, `scripts/seed_rag.py`

Details → `app/pipeline/CLAUDE.md`

## Tech Stack

- **Frontend:** React 19.2, TypeScript 5.9, Vite 7, Tailwind CSS v4, Zustand 5, TanStack Query 5, React Router 7
- **Backend:** FastAPI, Python 3.12, python-jose (JWT HS256), BCrypt, SQLAlchemy async, OpenAI Python SDK, Google GenAI SDK, Resend Python SDK
- **Database:** SQLite/aiosqlite (dev) → RDS PostgreSQL/asyncpg (prod)
- **AI:** OpenAI API + Google Gemini API (multi-model pipeline, `openai` + `google-genai` SDKs)
- **Domain:** `politely-ai.com` (registered at Gabia, Route 53 + CloudFront connected)
- **Infra:** AWS (EC2 ap-northeast-2, RDS, S3, CloudFront, ECR ap-southeast-2, CloudWatch)
- **CI/CD:** GitHub Actions (frontend.yml, backend.yml)

## Infrastructure & Deployment

| Component | Service | Details |
|-----------|---------|---------|
| Frontend hosting | S3 + CloudFront | Bucket: `polite-ai-frontend`, CF: `dw4bv55v7wbh6.cloudfront.net` → `politely-ai.com` |
| Backend hosting | EC2 + Docker | Instance: `polite-ai-backend`, IP: `43.203.44.188` → `politely-ai.com/api/*` |
| Container registry | ECR | Region: **ap-southeast-2** (Sydney) — different from EC2 (Seoul ap-northeast-2) |
| Database | RDS PostgreSQL | Host: `polite-ai-db.cp4esg82u44n.ap-northeast-2.rds.amazonaws.com` |
| DNS | Route 53 | Domain registered at Gabia, NS transferred to Route 53 |
| SSL | ACM | Certificate in us-east-1 (for CloudFront), DNS validation |
| Email | Resend API | Verification emails (AWS SES approval denied → Resend alternative) |

**CI/CD Triggers:**
- `src/**` changes → `frontend.yml` → Build → S3 upload → CloudFront invalidation
- `app/**` changes → `backend.yml` → Docker build → ECR push → EC2 deploy

## Monorepo Structure

```
PoliteAi/
  src/                  # React frontend (FSD architecture)
  app/                  # FastAPI backend + AI pipeline
  docs/                 # PRD and documentation
  scripts/              # Dev helper scripts
  test-logs/            # Test result logs (persistent)
  .github/workflows/    # CI/CD (frontend.yml, backend.yml)
  package.json          # Frontend dependencies
  pyproject.toml        # Backend dependencies
  vite.config.ts        # Vite config with proxy and React Compiler
  tsconfig*.json        # TypeScript config
  .env                  # Backend environment variables (not committed)
```

## Test Logs

Test results are permanently saved in the `test-logs/` directory.

```
test-logs/
  test-labeling/       → Labeling test logs (auto-saved on /test-labeling run)
  test-transform/      → Transform test logs (auto-saved on /test-transform run)
```

Agent reference: Glob `test-logs/test-*/*.md` then Read. Check latest log's "Overall Analysis" section for current issues. Log cleanup: `/clean-test-logs`.

## Environment Variables

Backend `.env` file (required for local dev):

```
JWT_SECRET=...                          # Min 32 chars
OPENAI_API_KEY=sk-...                   # OpenAI API key
GEMINI_API_KEY=...                      # Google Gemini API key
DATABASE_URL=sqlite+aiosqlite:///./politeai.db  # or PostgreSQL URL
ENVIRONMENT=dev                         # dev or prod
RESEND_API_KEY=...                      # Prod email (optional in dev)
RAG_ENABLED=false                       # Enable RAG retrieval (optional)
RAG_ADMIN_TOKEN=...                     # Token for POST /api/internal/rag/reload (optional)
```

See `app/core/config.py` for all settings with defaults.

## Bash Command Rules

**Never chain commands with `&&`, `||`, or `;`.** Each command must be a separate Bash tool call. Independent commands should be called in parallel. This ensures each command matches its permission pattern (e.g., `Bash(git diff:*)`) and avoids unnecessary permission prompts.
