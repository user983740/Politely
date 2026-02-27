---
name: arch
description: Analyzes project architecture structure and dependencies. Supports backend, frontend, pipeline, or full overview modes.
tools:
  - Read
  - Glob
  - Grep
  - Bash
  - Task
model: sonnet
---

# Architecture Analysis

Analyzes the project's architecture structure, module dependencies, and design principles adherence.

## User Arguments

$ARGUMENTS

## Execution Steps

### Step 1: Determine Analysis Scope

| Argument                   | Scope               | Description                                                        |
| -------------------------- | ------------------- | ------------------------------------------------------------------ |
| (none)                     | Full overview       | Frontend + backend structure summary, module map, key observations |
| `backend` or `백엔드`      | Backend modules     | app/ module structure + import dependency analysis                 |
| `frontend` or `프론트`     | Frontend FSD        | src/ FSD layer analysis                                            |
| `pipeline` or `파이프라인` | Pipeline components | Pipeline step-by-step component dependencies/data flow             |
| Specific module name       | Focused analysis    | e.g., `labeling`, `auth`, `segmentation`, `template`               |

### Step 2: Structure Scan

**Backend** (module-based, FastAPI):

- Scan `app/` directory (Glob `app/**/*.py`)
- Module hierarchy:
  - `core/` — Config (pydantic-settings), security (JWT/BCrypt), exceptions
  - `models/` — SQLAlchemy ORM, domain dataclasses, enums
  - `schemas/` — Pydantic v2 request/response DTOs
  - `db/` — Async engine, session factory, repositories
  - `services/` — Application services (auth, email, transform)
  - `pipeline/` — AI pipeline (7 sub-modules)
  - `api/v1/` — REST endpoints (auth, transform)
  - `email/` — Email services (console dev, resend prod)
  - `scheduling/` — Background tasks
- Dependency rule: `core/` and `models/` should not import from `services/`, `api/`, or `pipeline/`

**Frontend** (FSD basis):

- Scan `src/` directory
- Layers: `shared/` → `entities/` → `features/` → `widgets/` → `pages/` → `app/`
- Dependency rule: Only upper layers may import from lower ones

### Step 3: Dependency Analysis

1. **Import scan**: Grep `from app.` and `import app.` statements per module
2. **Violation detection**:
   - Backend: `core/` → `services/api/pipeline` = violation; `models/` → `services/api` = violation
   - Frontend: `shared/` → `features/widgets/pages` = violation; `entities/` → `features+` = violation
3. **Circular dependencies**: A→B→A detection

### Step 4: Per-Component Analysis

#### `backend` mode

```
## Backend Architecture Analysis

### Module Structure

app/
  core/
    ├── config.py        → Pydantic-settings (env loading)
    ├── security.py      → JWT HS256, BCrypt
    └── exceptions.py    → 10 custom exceptions
  models/
    ├── user.py          → SQLAlchemy ORM (User, EmailVerification)
    ├── domain.py        → 9 pipeline dataclasses (frozen)
    └── enums.py         → 11 enums (Persona, Context, Label, etc.)
  schemas/               → Pydantic v2 DTOs (auth: 6, transform: 3)
  db/
    ├── session.py       → Async engine + session factory
    └── repositories.py  → CRUD functions
  services/              → Auth, email verification, transform
  pipeline/              → AI pipeline (see pipeline mode)
  api/v1/                → REST endpoints (auth, transform, deps)
  email/                 → Console (dev) / Resend (prod)
  scheduling/            → Verification cleanup (hourly)
  tests/                 → 70 tests, 8 files

### Dependency Map
### Violations
### Design Observations
```

#### `frontend` mode

```
## Frontend Architecture Analysis

### Layer Structure
shared/
  ├── api/       → API client
  ├── ui/        → Common UI components
  ├── stores/    → Zustand stores
  └── config/    → Constants
entities/        → Business entities (persona, context, tone, etc.)
features/        → User actions (transform, auth, etc.)
widgets/         → Self-contained UI blocks (TransformPanel, ResultPanel, etc.)
pages/           → Route pages (HomePage, LoginPage, etc.)
app/             → App entry, router, global styles

### Dependency Map
### Violations
### Design Observations
```

#### `pipeline` mode

```
## Pipeline Architecture Analysis

### Execution Flow
1. TextNormalizer → 2. LockedSpanExtractor → 3. LockedSpanMasker
→ 4. SituationAnalysis + IdentityBooster (parallel, async)
→ 5. MeaningSegmenter → 6. LlmSegmentRefiner
→ 7. StructureLabelService → 8. YellowTriggerScanner → 9. RedLabelEnforcer
→ 10. TemplateSelector → 11. RedactionService
→ 12. MultiModelPromptBuilder → 13. Final LLM (gemini-2.5-flash)
→ 14. LockedSpanMasker.unmask → 15. OutputValidator

### Sub-module Map
preprocessing/   → TextNormalizer, LockedSpanExtractor, LockedSpanMasker
segmentation/    → MeaningSegmenter, LlmSegmentRefiner
labeling/        → StructureLabelService, YellowTriggerScanner, RedLabelEnforcer
template/        → TemplateRegistry, TemplateSelector, StructureTemplate
gating/          → GatingConditionEvaluator, IdentityLockBooster, SituationAnalysisService
redaction/       → RedactionService
validation/      → OutputValidator

### Component Dependencies
### Data Flow
### Parallel Execution Points
### Design Observations
```

#### Full Overview Mode (no argument)

Scan frontend + backend in **2 parallel Task agents** (`subagent_type="general-purpose"`):

- Agent 1: Backend `app/` module structure + import violation scan
- Agent 2: Frontend `src/` FSD layer structure + import violation scan

Merge results into full overview.

## Output Format

```
## Architecture Analysis: [Scope]

### Structure Summary
(Directory tree + module/layer roles)

### Dependency Map
(Key dependency relationships)

### Violations
| Severity | Location | Description | Impact |
|----------|----------|-------------|--------|
| ERROR    | file:line | core → services import | Module boundary violation |

No violations → "Module rules compliant: no violations found"

### Design Observations
- Strengths: ...
- Improvement opportunities: ...

### Numeric Summary
- Total files: N (backend N / frontend N)
- Module violations: N
- Circular dependencies: N
```

## Rules

- Explain in English; keep code terms as-is
- Do not modify code — analysis only
- Use CLAUDE.md module rules as judgment basis
- If no violations, do not fabricate issues
- For pipeline analysis, reference `app/pipeline/CLAUDE.md` (no re-analysis needed)
- Use parallel Task agents for large-scale scans
