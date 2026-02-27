# Frontend Architecture

React 19 + TypeScript 5.9 + Vite 7 + Tailwind CSS v4 + Zustand 5 + TanStack Query 5 + React Router 7

**Vite plugins:** `@vitejs/plugin-react` (with React Compiler via `babel-plugin-react-compiler`), `@tailwindcss/vite`

**Path alias:** `@/` → `src/` (configured in `vite.config.ts` and `tsconfig.json`)

## FSD Structure (Feature-Sliced Design)

```
src/
  app/                → App entry, router, global styles
    App.tsx           → BrowserRouter + Routes + AuthStore init
    styles/index.css  → Tailwind @theme tokens, custom keyframes, tone slider styling
  pages/              → Route-level page components
    HomePage.tsx      → Main transformation interface (dual-mode: input → result)
    LoginPage.tsx     → Email + password login form
    SignupPage.tsx    → 2-step signup: email verification → user info input
    AdminPage.tsx     → Admin dashboard (redirects non-admin users)
    PrivacyPage.tsx   → Korean privacy policy (개인정보 처리방침)
  widgets/            → Self-contained UI blocks
    TransformPanel/   → (Legacy, unused) Multi-step transform input form
    ResultPanel/      → Displays streamed result with copy button + animated cursor
    AnalysisPanel/    → 3-tier label display (GREEN/YELLOW/RED with color badges)
    PipelineTracePanel/ → 11-step real-time pipeline execution timeline
    QualityReportPanel/ → Pipeline stats grid (segment counts, gating flags, latency)
    CostPanel/        → Token usage + cost breakdown table with monthly projections
    AdminDashboard/   → Admin metrics with charts, heatmap, stats (mock data)
  features/           → User actions with API definitions
    auth/api.ts       → login, signup, sendVerificationCode, verifyCode, checkLoginId
    transform/        → streamTransform (SSE), getTierInfo, types, stream parser
  shared/             → API client, UI components, Zustand stores, config, constants
    api/client.ts     → ApiClient class (post/get), JWT injection, ApiError
    config/constants.ts → All enums, type aliases, limits
    store/            → useAuthStore, useTransformStore (Zustand)
    ui/               → Reusable UI components (11 components)
```

**Import rule:** layers can only import from layers below them.
`app > pages > widgets > features > entities > shared`

## Routes

| Path | Page | Description |
|------|------|-------------|
| `/` | HomePage | Single-column UI: text input (+ sender_info + user_prompt) → result panels (trace/analysis/result/stats/cost). No metadata selection. |
| `/login` | LoginPage | Email + password form with ApiError handling |
| `/signup` | SignupPage | 2-step: email verification (6-digit code) → loginId/name/password + privacy consent |
| `/admin` | AdminPage | Admin-only dashboard (redirects to `/` if not admin) |
| `/privacy` | PrivacyPage | Korean privacy policy document |

## Widgets

### ResultPanel
- **State:** `copied` (2s clipboard feedback)
- **Data source:** `useTransformStore.transformedText`, `isTransforming`
- Shows animated cursor (blinking `|`) during streaming, copy button on completion

### AnalysisPanel
- **Props:** `labels: LabelData[] | null`
- **Features:** Collapsible panel, 14 label display names mapped from backend, 3-tier color scheme (emerald/amber/red), count badges per tier, RED labels shown with strikethrough text

### PipelineTracePanel (most complex widget, 643 lines)
- **Props:** `currentPhase`, `isTransforming`, `spans[]`, `segments[]`, `maskedText`, `labels[]`, `situationAnalysis`, `processedSegments`, `validationIssues[]`, `chosenTemplate`, `transformedText`, `transformError`
- **State:** `expandedSteps` (Set), `skippedSteps` (Set), `prevPhase`
- **11 execution steps:** Normalize → Extract → Segment → SegmentRefine → Label → Situation → TemplateSelect → Redact → CushionStrategy → Generate → Validate (+ GenerateB in AB mode)
- **Features:** Status tracking (pending/running/completed/skipped/error), auto-expand completed steps, vertical timeline with color-coded connectors, progress bar, LLM/SKIP/ERROR badges, step-specific detail rendering

### QualityReportPanel
- **Props:** `stats: StatsData`
- **Features:** Collapsible, stat grid (segment count, G/Y/R counts, locked spans, retries, situation analysis ON/OFF), latency in header

### CostPanel
- **Props:** `usageInfo: UsageInfo`
- **Features:** Collapsible, cost breakdown table (analysis vs final LLM calls), token counts (prompt+completion), total cost, monthly projections for MVP/Growth/Mature tiers
- **Pricing:** gpt-4o-mini: $0.15/1M prompt, $0.60/1M completion

### AdminDashboard (mock data)
- Daily transforms chart (7 days), persona/context/tone distribution, retention metrics (D1/D3/D7), persona-context heatmap (6x8), API metrics
- Responsive grid (2 cols on sm), horizontal scroll on heatmap for mobile

## Features — API Calls

### Auth Feature (`features/auth/api.ts`)

| Function | Method | Path | Returns |
|----------|--------|------|---------|
| `login(email, password)` | POST | `/auth/login` | `AuthResponse` |
| `signup(email, loginId, name, password, privacyAgreed)` | POST | `/auth/signup` | `AuthResponse` |
| `sendVerificationCode(email)` | POST | `/auth/email/send-code` | `{message}` |
| `verifyCode(email, code)` | POST | `/auth/email/verify-code` | `{message}` |
| `checkLoginId(loginId)` | POST | `/auth/check-login-id` | `{available: bool}` |

### Transform Feature (`features/transform/`)

| Function | Method | Path | Returns |
|----------|--------|------|---------|
| `getTierInfo()` | GET | `/v1/transform/tier` | `TierInfo` |
| `streamTransform(req, callbacks, signal)` | POST | `/v1/transform/stream` | SSE stream (15 event types) |

## Entities

Entity directories removed (persona, context, tone). All metadata types remain in backend enums only (not used by frontend).

Frontend uses only: `originalText`, `senderInfo`, `userPrompt` — no metadata selection UI.

## Zustand Stores

### useAuthStore

```typescript
// State
isLoggedIn: boolean        // derived from localStorage on init
isAdmin: boolean
email: string | null
loginId: string | null
name: string | null

// Actions
setLoggedIn(data: {email, loginId, name, token}, isAdmin?): void
  // Saves token + user info to localStorage, sets isLoggedIn=true
setLoggedOut(): void
  // Removes all from localStorage, resets state
initFromStorage(): void
  // Called on App mount; reads localStorage, restores auth state
```

### useTransformStore

```typescript
// Input
originalText: string
userPrompt: string
senderInfo: string

// Pipeline Output
transformedText: string              // accumulated from delta chunks
analysisContext: string | null
labels: LabelData[] | null           // 14-label classification results
pipelineStats: StatsData | null      // counts, latency, gating flags
segments: SegmentData[] | null       // text segments with boundaries
maskedText: string | null            // text with {{TYPE_N}} placeholders
situationAnalysis: SituationAnalysisData | null  // facts + intent
processedSegments: ProcessedSegmentsData | null  // post-redaction segments
validationIssues: ValidationIssueData[] | null   // validation errors/warnings
chosenTemplate: TemplateSelectedData | null      // selected template ID
currentPhase: PipelinePhase | null   // current pipeline step

// State Management
isTransforming: boolean
transformError: string | null
usageInfo: UsageInfo | null          // token counts + cost

// Key Actions
resetForNewInput(): void             // clears all state
reset(): void                        // full state reset
```

## API Client (`shared/api/client.ts`)

```typescript
class ApiClient {
  private baseUrl: string         // '/api'
  private getHeaders(): HeadersInit  // auto-injects JWT from localStorage
  async post<T>(path, body): Promise<T>
  async get<T>(path): Promise<T>
}

class ApiError extends Error {
  status: number    // HTTP status code
  code: string      // error code from backend (e.g., 'INVALID_CREDENTIALS')
}
```

- JWT stored in `localStorage['token']`
- Automatically injected as `Authorization: Bearer <token>` on every request
- No token refresh logic — token assumed valid (24h expiry)
- Errors parsed as `ApiError(status, code, message)` from backend JSON

## SSE Transform Flow

**Trigger:** `HomePage.handleTransform()` button click

**Request payload:**
```typescript
{
  originalText: string       // required, 1-2000 chars
  senderInfo?: string        // optional, max 100 chars
  userPrompt?: string        // optional, max 500 chars
}
```

**SSE stream parser (`streamSSE()`):**
1. POST to `/api/v1/transform/stream` with Authorization header
2. ReadableStream + TextDecoder with buffer management
3. Parse SSE format: `event:` + `data:` lines (multi-line JSON support)
4. Dispatch 15 event types through callback router

**14 callback types + retry:**
- `onPhase(phase)` — pipeline step change
- `onSpans(spans[])` — locked spans extracted
- `onMaskedText(text)` — text with placeholders
- `onSegments(segments[])` — text segments
- `onLabels(labels[])` — labeled segments (G/Y/R)
- `onSituationAnalysis(data)` — facts + intent
- `onProcessedSegments(data)` — post-redaction labels
- `onTemplateSelected(data)` — chosen template + gating fired
- `onDelta(chunk)` — streaming text token
- `onValidationIssues(issues[])` — validation results
- `onStats(stats)` — pipeline metrics
- `onUsage(usage)` — token counts + cost
- `onDone(fullText)` — final complete text
- `onError(message)` — error message
- `onRetry()` — validation failed, client discards accumulated deltas

**Span replacement in HomePage:**
- `spansRef` stores locked span data (placeholder → original mapping)
- On each `onDelta`: replace `{{TYPE_N}}` placeholders with original text for display
- `rawStreamRef` accumulates raw LLM output (with placeholders)
- `transformedText` in store shows human-readable text (originals restored)

## Key Data Interfaces

```typescript
SegmentData = { id, text, start, end }
LabelData = { segmentId, label, tier: 'GREEN'|'YELLOW'|'RED', text }
SituationAnalysisData = { facts: {content, source?}[], intent }
ValidationIssueData = { type, severity: 'ERROR'|'WARNING', message, matchedText? }
StatsData = { segmentCount, greenCount, yellowCount, redCount, lockedSpanCount,
              retryCount, identityBoosterFired, situationAnalysisFired,
              metadataOverridden, chosenTemplateId, latencyMs }
UsageInfo = { analysisPromptTokens, analysisCompletionTokens, finalPromptTokens,
             finalCompletionTokens, totalCostUsd, monthly: {mvp, growth, mature} }
ProcessedSegmentItem = { id, tier, label, text: string|null }
TemplateSelectedData = { templateId, templateName, metadataOverridden }
LockedSpanInfo = { placeholder: '{{TYPE_N}}', original, type }

PipelinePhase = 'normalizing' | 'extracting' | 'identity_boosting' | 'identity_skipped' |
  'segmenting' | 'segment_refining' | 'segment_refining_skipped' | 'labeling' |
  'situation_analyzing' | 'template_selecting' |
  'situation_skipped' | 'redacting' | 'cushion_strategizing' | 'generating' |
  'generating_a' | 'generating_b' | 'validating' | 'complete'
```

## Shared UI Components (11)

| Component | Description | Key Props/Features |
|-----------|-------------|-------------------|
| Button | 3 variants | `variant: 'primary'|'secondary'|'ghost'`, disabled state, extends ButtonHTMLAttributes |
| Input | Text input | Optional `label`, focus ring on accent, extends InputHTMLAttributes |
| PasswordInput | Password with toggle | Eye icon visibility toggle, extends InputHTMLAttributes |
| Textarea | Multi-line input | Optional `label`, `maxLength` display, `resize-none` |
| Chip | Selectable tag | `label`, `selected`, `onClick`, scale animation on select |
| ChipGroup | Chip container | `options[]`, `selected` (string or string[]), single or multi-select |
| Checkbox | Labeled checkbox | Flex label, accent color |
| Header | App header | Logo + responsive nav (desktop inline / mobile hamburger), login/signup/logout/admin links |
| Footer | App footer | Copyright + privacy policy link |
| Layout | Page wrapper | Header + `<main>` children + Footer |
| StatCard | Metric card | `title`, `value`, optional `description` |

## Styling & Design Tokens

**Tailwind CSS v4** — all customization in `src/app/styles/index.css` `@theme` block (no tailwind.config):

```css
/* Colors */
--color-primary: #18181b       /* dark gray */
--color-accent: #18181b        /* main brand dark */
--color-accent-hover: #27272a
--color-accent-light: #f4f4f5  /* selected state background */
--color-bg: #f8fafc            /* page background */
--color-surface: #f1f5f9       /* panel/section background */
--color-border: #e2e8f0        /* input/panel borders */
--color-text: #0f172a          /* main text */
--color-text-secondary: #64748b /* secondary text */
--color-success: #16a34a       /* green */
--color-error: #dc2626         /* red */
--color-warm: #f59e0b          /* amber */

/* Font */
--font-sans: Pretendard Variable, Pretendard, system-ui stack

/* Status colors (used directly via Tailwind utilities) */
GREEN: bg-emerald-50/100, text-emerald-600/700
YELLOW: bg-amber-50/100, text-amber-600/700
RED: bg-red-50/100, text-red-600/700
```

**Custom CSS:** `animate-fade-in-up` (0.6s), `animate-fade-in` (0.5s), tone slider webkit/moz styling

## Responsive Design

Mobile-first approach using Tailwind breakpoints:

| Breakpoint | Layout |
|------------|--------|
| `< sm` (mobile, <640px) | Single column, hamburger nav, compact spacing/font, collapsible settings |
| `>= sm` (tablet, 640px+) | Inline header nav, relaxed spacing |
| `>= lg` (desktop, 1024px+) | Single-column centered (max-w-2xl), 4-column admin grid |

**Key patterns:** `p-4 sm:p-6`, `text-xl sm:text-2xl`, `grid-cols-2 sm:grid-cols-4`, `hidden lg:flex` / `lg:hidden`

## SEO

### Meta Tags & Social Sharing (`index.html`)
- `lang="ko"`, description, author, robots, canonical
- Open Graph: title, description, image, url, locale=ko_KR, site_name
- Twitter Card: summary type
- JSON-LD: WebApplication schema (schema.org)
- `theme-color: #6366f1`, apple-touch-icon, manifest link

### Per-Page Meta Tags (React 19 native head hoisting)

| Page | Title | Extras |
|------|-------|--------|
| `/` | Politely - 한국어 말투 다듬기 도구 | description, canonical |
| `/login` | 로그인 - Politely | description, canonical |
| `/signup` | 회원가입 - Politely | description, canonical |
| `/privacy` | 개인정보 처리방침 - Politely | description, canonical |
| `/admin` | 관리자 대시보드 - Politely | `noindex, nofollow` |

### Static SEO Files (`public/`)
- `robots.txt` — Allow `/`, Disallow `/admin` and `/api/`, sitemap reference
- `sitemap.xml` — 4 public pages with priority/changefreq
- `manifest.json` — PWA metadata (name, icons, theme color, lang)

### Semantic HTML
- Layout: `<header>` + `<nav>`, `<main>`, `<footer>`
- HomePage: `<section>` for settings, `<article>` for result, `<aside>` for desktop sidebar
- PrivacyPage: `<article>` wrapper, `<section>` per policy item
- AdminDashboard: `<section>` for each card

### Accessibility
- All decorative SVGs: `aria-hidden="true"`
- Icon-only buttons: `aria-label` (hamburger, help, settings)
- Form inputs: `<label>` with `htmlFor`
- Semantic heading hierarchy (h1, h2, h3)

## Vite Config

```typescript
// vite.config.ts
plugins: [
  react({ babel: { plugins: [['babel-plugin-react-compiler']] } }),  // Auto memoization
  tailwindcss()  // @tailwindcss/vite (bundled, faster)
]
resolve.alias: { '@': './src' }

// Proxy
server.proxy['/api']:
  mode === 'localdev' → http://localhost:8080
  else                → http://43.203.44.188:8080  (EC2 backend)
```

## Key Dependencies

| Package | Version | Purpose |
|---------|---------|---------|
| react | ^19.2.0 | UI framework |
| react-router-dom | ^7.13.0 | Client-side routing |
| zustand | ^5.0.11 | State management |
| @tanstack/react-query | ^5.90.20 | Server state (available, not heavily used) |
| tailwindcss | ^4.1.18 | Utility-first CSS (v4, no config file) |
| typescript | ~5.9.3 | Type checking |
| babel-plugin-react-compiler | ^1.0.0 | React Compiler auto-memoization |
| vite | ^7.2.4 | Build tool + dev server |
