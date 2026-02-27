# FastAPI Backend Overview

FastAPI + Python 3.12 + async/await throughout + Pydantic v2 + SQLAlchemy async

## Directory Structure

```
app/
  main.py                 # FastAPI app, lifespan, CORS, exception handlers, RAG index loading
  api/v1/                 # REST endpoints (auth.py, transform.py, rag_admin.py, deps.py)
  core/                   # Config (pydantic-settings), security (JWT/BCrypt), exceptions
  models/                 # SQLAlchemy models (user.py, rag.py), enums (9 types), domain dataclasses (9)
  schemas/                # Pydantic v2 request/response DTOs (auth: 6, transform: 3)
  services/               # Application services (auth, email, transform, embedding, rag)
  pipeline/               # AI pipeline (see pipeline/CLAUDE.md)
  email/                  # Email services (console dev, resend prod)
  db/                     # Async SQLAlchemy engine + session + repositories (user, rag)
  scheduling/             # Background task schedulers (verification cleanup, hourly)
  tests/                  # pytest + pytest-asyncio tests (70 tests, 8 files)
```

## Application Entry (`main.py`)

```python
FastAPI(title="PoliteAi", version="1.0.0", lifespan=lifespan)

# Lifespan
startup:  create_all() DB tables, load RAG index (if RAG_ENABLED), start cleanup_scheduler
shutdown: cancel cleanup_task, dispose async engine

# CORS
allow_origins: ["http://localhost:5173", "https://politely-ai.com"]
allow_credentials: True, allow_methods: ["*"], allow_headers: ["*"]

# Routers
/api/auth               → app.api.v1.auth
/api/v1/transform       → app.api.v1.transform
/api/internal/rag/*     → app.api.v1.rag_admin (only if RAG_ADMIN_TOKEN set)
/api/health             → health check endpoint
```

## Application Services

### auth_service (`services/auth_service.py`)

| Function | Parameters | Logic | Returns |
|----------|------------|-------|---------|
| `signup()` | session, email, login_id, name, password | Check email/loginId not taken, email verified, hash password, create User | `AuthResponse` with JWT |
| `login()` | session, email, password | Find user by email, verify BCrypt password | `AuthResponse` with JWT |
| `check_login_id_availability()` | session, login_id | Query exists_user_by_login_id | `bool` |

### email_verification_service (`services/email_verification_service.py`)

| Function | Parameters | Logic | Returns |
|----------|------------|-------|---------|
| `send_verification_code()` | session, email | Check email not registered, generate 6-digit code (`secrets.randbelow`), create record with 5min expiry, send email | `{message}` |
| `verify_code()` | session, email, code | Find latest verification, check not expired, check code matches, mark verified | `{message}` |

Email service selection: `ENVIRONMENT=prod` → `ResendEmailService`, else → `ConsoleEmailService` (logs to stdout)

### transform_app_service (`services/transform_app_service.py`)

| Function | Parameters | Logic | Returns |
|----------|------------|-------|---------|
| `transform()` | persona, contexts, tone_level, original_text, user_prompt, sender_info | Validate length, execute_analysis() → execute_final() | `TransformResult` |
| `validate_transform_request()` | original_text | Check len ≤ 2000 (PAID max) | raises ValueError |
| `get_max_text_length()` | — | Returns settings.tier_paid_max_text_length (2000) | `int` |
| `resolve_final_max_tokens()` | — | Returns settings.openai_max_tokens_paid (4000) | `int` |

## Config (`core/config.py`)

Pydantic-settings class with `.env` file loading:

| Setting | Type | Default | Purpose |
|---------|------|---------|---------|
| `jwt_secret` | str | dev placeholder (32+ chars required) | JWT HS256 signing key |
| `jwt_expiration_ms` | int | 86400000 (24h) | JWT token lifetime |
| `app_email_sender` | str | "noreply@politeai.com" | Email from address |
| `openai_api_key` | str | placeholder | OpenAI API authentication |
| `openai_model` | str | "gpt-4o" | Final transform LLM model |
| `openai_temperature` | float | 0.85 | Final LLM creativity |
| `openai_max_tokens` | int | 2000 | FREE tier output limit |
| `openai_max_tokens_paid` | int | 4000 | PAID tier output limit |
| `gemini_api_key` | str | "" | Google Gemini API key |
| `gemini_final_model` | str | "gemini-2.5-flash" | Final transform Gemini model |
| `gemini_label_model` | str | "gemini-2.5-flash-lite" | Labeling/Booster Gemini model |
| `segmenter_max_segment_length` | int | 250 | Max segment chars before safety split |
| `segmenter_discourse_marker_min_length` | int | 80 | Min length for discourse marker splitting |
| `segmenter_enumeration_min_length` | int | 60 | Min length for enumeration splitting |
| `tier_free_max_text_length` | int | 300 | FREE tier max input |
| `tier_paid_max_text_length` | int | 2000 | PAID tier max input |
| `resend_api_key` | str | "" | Resend.com API key (prod) |
| `rag_enabled` | bool | false | Enable RAG retrieval |
| `rag_embedding_model` | str | "text-embedding-3-small" | OpenAI embedding model |
| `rag_admin_token` | str | "" | Token for `/api/internal/rag/reload` |
| `rag_mmr_duplicate_threshold` | float | 0.92 | MMR dedup threshold |
| `database_url` | str | sqlite+aiosqlite:///./politeai.db | DB connection string |
| `environment` | str | "dev" | Environment (dev/prod) |

## Security (`core/security.py`)

- **Algorithm:** HS256
- `hash_password(password) → str` — BCrypt with salt
- `verify_password(plain, hashed) → bool` — BCrypt verify
- `generate_token(email) → str` — JWT with sub (email), iat (now), exp (now+24h)
- `validate_token(token) → bool` — decode + verify, catches JWTError
- `get_email_from_token(token) → str` — extracts email from valid JWT

## Exceptions (`core/exceptions.py`)

10 custom exception classes with structured JSON responses:

| Exception | HTTP | Code | When |
|-----------|------|------|------|
| `DuplicateEmailError` | 409 | DUPLICATE_EMAIL | Email already registered |
| `DuplicateLoginIdError` | 409 | DUPLICATE_LOGIN_ID | Login ID taken |
| `InvalidVerificationCodeError` | 400 | INVALID_VERIFICATION_CODE | Wrong 6-digit code |
| `VerificationExpiredError` | 400 | VERIFICATION_EXPIRED | Code past 5min expiry |
| `EmailNotVerifiedError` | 400 | EMAIL_NOT_VERIFIED | Signup without verification |
| `VerificationNotFoundError` | 404 | VERIFICATION_NOT_FOUND | No verification record |
| `InvalidPasswordFormatError` | 400 | INVALID_PASSWORD_FORMAT | Missing letters/digits/special, <8 chars |
| `InvalidCredentialsError` | 401 | INVALID_CREDENTIALS | Wrong email or password |
| `TierRestrictionError` | 403 | TIER_RESTRICTION | Feature not available for tier |
| `AiTransformError` | 503 | AI_TRANSFORM_ERROR | LLM API failure |

**Additional handlers:** `ValueError` → 400, `pydantic.ValidationError` → 400, catch-all `Exception` → 500

**Response format:** `{"error": "ERROR_CODE", "message": "Korean user message"}`

## Database

### Engine & Session (`db/session.py`)

- Dev: SQLite via `aiosqlite` (`sqlite+aiosqlite:///./politeai.db`)
- Prod: RDS PostgreSQL via `asyncpg`
- `echo=True` in dev for SQL logging
- Tables auto-created on startup via `Base.metadata.create_all`
- `expire_on_commit=False` for async session compatibility

### ORM Models (`models/user.py`)

**User table:**
| Column | Type | Constraints |
|--------|------|-------------|
| id | Integer | PK, autoincrement |
| email | String(255) | UNIQUE, NOT NULL |
| login_id | String(30) | UNIQUE, NOT NULL |
| name | String(50) | NOT NULL |
| password | String(255) | NOT NULL (bcrypt hash) |
| tier | String(10) | NOT NULL, default "FREE" |
| created_at | DateTime | NOT NULL, default utcnow |

**EmailVerification table:**
| Column | Type | Constraints |
|--------|------|-------------|
| id | Integer | PK, autoincrement |
| email | String(255) | NOT NULL |
| code | String(6) | NOT NULL |
| verified | Boolean | NOT NULL, default False |
| expires_at | DateTime | NOT NULL |
| created_at | DateTime | NOT NULL, default utcnow |

Methods: `is_expired() → bool`, `mark_verified() → None`

### Repositories (`db/repositories.py`)

| Function | Returns | Query |
|----------|---------|-------|
| `find_user_by_email(session, email)` | User \| None | SELECT where email = |
| `exists_user_by_email(session, email)` | bool | SELECT id where email = |
| `exists_user_by_login_id(session, login_id)` | bool | SELECT id where login_id = |
| `save_user(session, user)` | User | add + flush |
| `find_latest_verification_by_email(session, email)` | EmailVerification \| None | SELECT where email ORDER BY created_at DESC LIMIT 1 |
| `save_verification(session, verification)` | EmailVerification | add + flush |
| `delete_expired_verifications(session)` | None | DELETE where expires_at < utcnow, commit |

### RAG Model (`models/rag.py`)

**RagCategory enum:** expression_pool, cushion, forbidden, policy, example, domain_context

**RagEntry table:**
| Column | Type | Purpose |
|--------|------|---------|
| id | Integer PK | Auto-increment |
| category | String(30) | RagCategory value |
| content | Text | Expression text |
| original_text | Text? | Example: original text |
| alternative | Text? | Forbidden: replacement |
| trigger_phrases | Text? | Forbidden: CSV lexical triggers |
| dedupe_key | String(64) UNIQUE | SHA-256 hash for idempotent upsert |
| personas/contexts/tone_levels/sections/yellow_labels | String? | CSV metadata filters |
| embedding_blob | Text? | JSON float32 array (1536-dim) |
| enabled | Boolean | Soft delete |
| created_at/updated_at | DateTime | Timestamps |

**Helpers:** `parse_csv_filter(value) → frozenset[str]`, `compute_dedupe_key(cat, content, personas, contexts) → str`

### RAG Repository (`db/rag_repository.py`)

| Function | Returns | Query |
|----------|---------|-------|
| `find_all_enabled(session)` | list[RagEntry] | SELECT where enabled=True |
| `count_by_category(session)` | dict[str,int] | GROUP BY category |
| `find_by_dedupe_key(session, key)` | RagEntry? | SELECT where dedupe_key= |
| `save_entry(session, entry)` | RagEntry | add + flush |
| `save_batch(session, entries)` | int | add all + flush |
| `delete_all(session)` | int | DELETE all |
| `delete_by_category(session, cat)` | int | DELETE where category= |

### RAG Services

**EmbeddingService** (`services/embedding_service.py`): `embed_text(text) → list[float]`, `embed_batch(texts) → list[list[float]]`, `embedding_to_json(emb) → str`, `json_to_embedding(s) → ndarray`

**RagService** (`services/rag_service.py`): `RagIndex` singleton with in-memory numpy cosine search. `rag_index.search(query_embedding, original_text, persona=, contexts=, ...) → RagResults`. Category-specific thresholds, MMR dedup, fallback logic. `RagResults` has 6 category lists of `RagSearchHit`.

**Seed script:** `python3 -m scripts.seed_rag [--category X] [--reset] [--dry-run]` — ~275 Korean expressions (6 categories), idempotent upsert via dedupe_key.

## Domain Dataclasses (`models/domain.py`)

9 frozen dataclasses for pipeline domain logic (not ORM):

| Dataclass | Key Fields | Used By |
|-----------|-----------|---------|
| `LockedSpan` | index, original_text, placeholder, type, start_pos, end_pos | Preprocessing |
| `Segment` | id, text, start, end | Segmentation |
| `LabeledSegment` | segment_id, label: SegmentLabel, text, start, end | Labeling |
| `TransformResult` | transformed_text, analysis_context | Service output |
| `ValidationIssue` | type: ValidationIssueType, severity: Severity, message, matched_text | Validation |
| `ValidationResult` | passed: bool, issues[] | Validation (has_errors(), errors(), warnings()) |
| `LlmCallResult` | content, analysis_context, prompt_tokens, completion_tokens | LLM wrapper |
| `PipelineStats` | token counts, segment counts, booster/SA/override bools, template_id, latency_ms | Pipeline output |
| `LabelStats` | green/yellow/red counts, has_accountability/negative_feedback/etc. bools | Label analysis |

## Enums (`models/enums.py`)

| Enum | Values |
|------|--------|
| `Persona` (6) | BOSS, CLIENT, PARENT, PROFESSOR, OFFICIAL, OTHER |
| `SituationContext` (14) | REQUEST, SCHEDULE_DELAY, URGING, REJECTION, APOLOGY, COMPLAINT, ANNOUNCEMENT, FEEDBACK, BILLING, SUPPORT, CONTRACT, RECRUITING, CIVIL_COMPLAINT, GRATITUDE |
| `ToneLevel` (3) | NEUTRAL, POLITE, VERY_POLITE |
| `Topic` (11) | REFUND_CANCEL, OUTAGE_ERROR, ACCOUNT_PERMISSION, DATA_FILE, SCHEDULE_DEADLINE, COST_BILLING, CONTRACT_TERMS, HR_EVALUATION, ACADEMIC_GRADE, COMPLAINT_REGULATION, OTHER |
| `Purpose` (11) | INFO_DELIVERY, DATA_REQUEST, SCHEDULE_COORDINATION, APOLOGY_RECOVERY, RESPONSIBILITY_SEPARATION, REJECTION_NOTICE, REFUND_REJECTION, WARNING_PREVENTION, RELATIONSHIP_RECOVERY, NEXT_ACTION_CONFIRM, ANNOUNCEMENT |
| `SegmentLabelTier` (3) | GREEN, YELLOW, RED |
| `SegmentLabel` (14) | GREEN: CORE_FACT, CORE_INTENT, REQUEST, APOLOGY, COURTESY / YELLOW: ACCOUNTABILITY, SELF_JUSTIFICATION, NEGATIVE_FEEDBACK, EMOTIONAL, EXCESS_DETAIL / RED: AGGRESSION, PERSONAL_ATTACK, PRIVATE_TMI, PURE_GRUMBLE |
| `LockedSpanType` (17) | EMAIL, URL, ACCOUNT, DATE, TIME, PHONE, MONEY, UNIT_NUMBER, LARGE_NUMBER, UUID, FILE_PATH, ISSUE_TICKET, VERSION, QUOTED_TEXT, IDENTIFIER, HASH_COMMIT, SEMANTIC |
| `ValidationIssueType` (14) | EMOJI, FORBIDDEN_PHRASE, HALLUCINATED_FACT, ENDING_REPETITION, LENGTH_OVEREXPANSION, PERSPECTIVE_ERROR, LOCKED_SPAN_MISSING, REDACTED_REENTRY, REDACTION_TRACE, CORE_NUMBER_MISSING, CORE_DATE_MISSING, SOFTEN_CONTENT_DROPPED, SECTION_S2_MISSING, INFORMAL_CONJUNCTION |
| `Severity` (2) | ERROR, WARNING |
| `UserTier` (2) | FREE, PAID |

## Email Services

| Service | Environment | Implementation |
|---------|-------------|----------------|
| `EmailService` | — | Protocol (interface): `send_verification_email(to, code)` |
| `ConsoleEmailService` | dev | Logs verification code to stdout via logger |
| `ResendEmailService` | prod | Calls `resend.Emails.send()` API, raises `RuntimeError` on failure |

## Background Scheduling (`scheduling/cleanup.py`)

- **Hourly verification cleanup**: Deletes `EmailVerification` records where `expires_at < utcnow()`
- Started as asyncio task in lifespan startup
- Cancelled on app shutdown
- Errors logged, task continues running

## Auth Flow

1. **Email verification:** User sends email → server generates 6-digit code (5min expiry) → ConsoleEmailService (dev) or ResendEmailService (prod) → user enters code → server verifies
2. **Signup:** Email must be verified → validate loginId (3-30 chars, unique) → validate password (letters + digits + special, 8+ chars) → hash with BCrypt → create User → return JWT
3. **Login:** Find user by email → verify BCrypt password → return JWT (HS256, 24h expiry)
4. **Token validation:** Bearer token in Authorization header → decode JWT → extract email → find user (optional dependency)

## Test Suite (70 tests, 8 files)

| File | Tests | Coverage |
|------|-------|----------|
| `test_health.py` | 2 | Health endpoint |
| `test_auth_api.py` | 13 | Auth endpoints + error cases |
| `test_security.py` | 6 | JWT + BCrypt functions |
| `test_repositories.py` | 7 | CRUD operations |
| `test_schemas.py` | 14 | Pydantic validation |
| `test_transform_api.py` | 5 | Transform endpoints |
| `test_domain.py` | 6 | Domain dataclasses |
| `test_output_validator.py` | 17 | Output validation rules |

**Fixtures** (conftest.py): `test_engine` (in-memory SQLite), `test_session`, `test_app` (overridden DB), `client` (AsyncClient), `created_user`, `auth_token`, `verified_email_session`

## Dev Commands

```bash
cd /home/sms02/PoliteAi
pip install -e ".[dev]"                         # Install with dev deps
uvicorn app.main:app --reload --port 8080       # Dev server
pytest app/tests/                               # Run all tests
ruff check app/                                 # Lint
```
