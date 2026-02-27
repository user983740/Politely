# PoliteAi - MVP PRD (Product Requirements Document)

## 1. Product Overview

### 1.1 Problem Definition

In Korean communication, **the risk from tone often outweighs the risk from content itself**.

- Relationship-driven communication: bosses, clients, parents, professors, etc.
- Constant anxiety: "It's technically correct, but will it come across as rude?"

Limitations of existing AI tools:

- Focused on generic sentence rewriting
- Designed primarily for English
- Fail to adequately reflect **relationship (who)** + **situation (what context)**

### 1.2 Solution (MVP)

This service is **not a tool that writes for you, but a safety net that polishes your tone right before sending**.

User flow:

1. **Select persona + situation via keyword combination**
2. **Enter original text as-is**
3. **AI transforms tone while preserving meaning**
4. **Selectively re-transform only unsatisfactory parts**
5. **Optionally add a one-line direction adjustment**

-> **A tone safety net right before hitting send**

### 1.3 MVP Goals

- Validate **real demand (early PMF signal)** for Korean tone transformation
- Collect data on which **persona/situation combinations** are used repeatedly
- Measure **partial re-transformation ratio** vs full re-transformation to assess quality fit
- Verify whether **satisfaction holds even with minimal API costs**

---

## 2. Core User Flow

### 2.1 Basic Flow

1. Enter main screen
   - Usable without login
   - Login enables enhanced usage tracking
2. Select persona / situation keywords
3. Enter original text (max 1,000 characters)
4. Execute transformation
5. Review result
6. (Optional) Partial re-transformation / additional request
7. Copy and use in external service

> **UX Principle:** Input -> Transform -> Copy must be completable within 10 seconds

### 2.2 Partial Re-transformation Flow (Key Differentiator + Cost Reduction)

1. **Drag-select a portion** of the transformed text
2. "Refine selected area" button appears
3. User enters **short additional request (optional)**
4. **Only the selected area is re-transformed and replaced**

**Design Intent:**

- Dissatisfaction points are usually about "specific sentences", not "everything"
- Reduce full re-transformations by **encouraging partial re-transformation -> drastically cut API costs**
- Users gain satisfaction from feeling "I'm in control"

---

## 3. Functional Requirements

### 3.1 Sign Up / Login

**Purpose:**

- Per-user usage pattern analysis
- Quantitative metrics for PMF assessment
- Preparation for future personalization/payment features

**MVP Scope:**

- Email + Password
- JWT-based authentication
- Non-logged-in users get identical functionality

**Collected Data:**

- user_id
- Daily transformation request count
- Daily **partial re-transformation request count**

### 3.2 Persona Selection

**Keyword-based single selection (required)**

- Boss (직장 상사)
- Client (고객)
- Parent (학부모)
- Professor (교수)
- Colleague (동료)
- Official Institution (공식 기관)

**Cost Optimization:**

- No natural language descriptions
- Internally: **short keyword -> prompt mapping**
- Minimize prompt length

### 3.3 Situation Selection (Context)

**Keyword-based multiple selection allowed**

- Request (요청)
- Schedule Delay (일정 지연)
- Urging (독촉)
- Rejection (거절)
- Apology (사과)
- Complaint (항의)
- Announcement (공지)
- Feedback (피드백)

Situations are used **only as condition tags**, not for prompt sentence generation.

### 3.4 Tone Modifier

- Very Polite (매우 공손)
- Polite (공손)
- Neutral (중립)
- Firm but Respectful (단호하지만 예의있게)

Tone options are reflected **only as output constraints**.

### 3.5 Original Text Input

- Textarea
- Max 1,000 characters (MVP limit)
- Real-time character count display

Input limit serves **quality stability + cost management**.

### 3.6 AI Transformation Request

**Input Parameters:**

- `persona` (string)
- `context` (string[])
- `tone_level` (enum: VERY_POLITE | POLITE | NEUTRAL | FIRM_BUT_RESPECTFUL)
- `original_text` (string)
- `optional_user_prompt` (string, max 80 chars)

**Output:**

- `transformed_text` (string only)

Output is **result text only**. No explanations, commentary, emoji, or filler.

### 3.7 Additional Request (User Prompt)

- Free-form single line (max 80 characters)
- Examples:
  - "Don't make it sound like an excuse"
  - "Short and firm"
  - "Don't show emotion"

Used **only as supplementary condition**. Server controls the full prompt structure.

### 3.8 Partial Re-transformation (Highlight Rewrite)

**Input:**

- `selected_text`
- `optional_context` (1 sentence before/after)
- Existing `persona` / `context` / `tone`
- `optional_user_prompt`

**Only selected area + minimal context is sent.** Full text is NOT re-sent.

### 3.9 Result Usage

- Copy button
- History storage: excluded from MVP

---

## 4. AI Strategy (Core Cost Reduction Design)

### 4.1 Core Principles

- Minimize full re-transformations
- Encourage partial re-transformations
- Fixed prompt length
- Enforced output format

### 4.2 Two-tier Model Strategy (Recommended)

**Tier 1 - Basic Transformation:**

- Use cost-effective model
- Most honorific/tone transformations handled here

**Tier 2 - Auto-upgrade Conditions:**

- Mixed honorific levels detected
- Prohibited words / aggression remnants
- Output excessively verbose / unnatural

-> Call higher-tier model only in these cases

### 4.3 Caching Strategy

- Same input + same conditions -> cache result
- 7-day TTL
- Cost reduction for frequently repeated business phrases

---

## 5. Non-functional Requirements (UX/UI)

### 5.1 UX Principles

- Never interrupt input flow
- Minimize click count
- Optimized for "one click right before sending"

### 5.2 UI Tone

- White background
- Minimal
- Notion / Linear-style productivity tool aesthetic

### 5.3 Responsive Design (Mobile-first)

- **Mobile-first approach**: All layouts start at mobile width and scale up with `sm:` / `lg:` breakpoints
- **Breakpoints**: `sm` (640px), `lg` (1024px) via Tailwind CSS
- **Header**: Hamburger menu on mobile (`< sm`), inline nav on desktop (`>= sm`)
- **Main page**: Single-column stacked layout on mobile, two-column grid on desktop (`>= lg`)
- **Admin dashboard**: 2-column stat cards on mobile, 4-column on desktop; horizontal scroll for heatmap table
- **Touch targets**: Minimum 44px tap area for interactive elements on mobile
- **Typography**: Responsive font sizes (e.g., `text-xl sm:text-2xl`) for readability across screen sizes
- **Spacing**: Tighter padding/gaps on mobile (e.g., `p-4 sm:p-6`, `gap-3 sm:gap-4`)

---

## 6. Technical Architecture

- **Frontend:** React + TypeScript + Tailwind CSS (Feature-Sliced Design)
- **Backend:** Spring Boot (Domain-Driven Design)
- **Infrastructure:** AWS (EC2, RDS, S3, CloudWatch)
- **AI:** OpenAI API (server-side prompt assembly)

---

## 7. Data & Metrics Tracking (MVP Core)

### 7.1 User Metrics

| Metric | Meaning |
|--------|---------|
| Total registered users | Growth tracking |
| New signups per day | Acquisition rate |
| DAU / WAU / MAU | Usage frequency |
| WAU/DAU ratio | Stickiness |

### 7.2 Usage Metrics

| Metric | Meaning |
|--------|---------|
| Total transformation requests | Core usage volume |
| Transformations per user (avg) | Engagement depth |
| **Partial re-transformation count & ratio** | Quality satisfaction signal |
| Full re-transformation count | Dissatisfaction signal |
| Copy button click count & rate | Result usefulness |
| User prompt usage rate | How often users add custom directions |
| Avg transformations per session | Session engagement |

### 7.3 Content Metrics

| Metric | Meaning |
|--------|---------|
| Persona selection distribution | Which relationships matter most |
| Context selection distribution | Which situations are most common |
| Tone level distribution | Preferred politeness level |
| Persona × Context combination heatmap | PMF signal for specific use cases |
| Average input text length | Actual usage context |
| Average output text length | Output characteristics |

### 7.4 Retention Metrics

| Metric | Meaning |
|--------|---------|
| D1 / D3 / D7 retention | Early habit formation |
| Return visit interval | Usage pattern |
| Churn rate (7-day inactive) | User loss tracking |

### 7.5 Performance & Cost Metrics

| Metric | Meaning |
|--------|---------|
| Avg API response time | User experience quality |
| API error rate | Reliability |
| API calls per day | Cost baseline |
| Estimated daily API cost | Budget tracking |
| Cost per transformation | Unit economics |
| Cost savings from partial vs full re-transforms | Partial rewrite ROI |

### 7.6 Admin Dashboard

Admin-only page (`/admin`) accessible to admin accounts. Displays all above metrics with:

- **Overview cards**: Total users, DAU, total transformations today, API cost today
- **Usage charts**: Daily transformation trend (line), persona distribution (bar), context distribution (bar)
- **Retention table**: D1/D3/D7 cohort retention
- **Persona × Context heatmap**: Combination frequency matrix
- **Real-time indicators**: API response time, error rate

---

## 8. MVP Success Criteria (Score-based)

Each item is **scored and summed** (Total: 100 points).

### 8.1 Usage Frequency (25 pts)

- DAU >= 100 -> 15 pts
- WAU/DAU >= 0.4 -> 10 pts

### 8.2 Usage Depth (25 pts)

- Avg transformations per session >= 2 -> 10 pts
- **Partial re-transformation usage ratio >= 30%** -> 15 pts

### 8.3 Retention (25 pts)

- Return within 3 days of signup >= 25% -> 15 pts
- Return within 7 days >= 15% -> 10 pts

### 8.4 Qualitative Signals (25 pts)

- "I want to keep using this" feedback >= 10 -> 15 pts
- Real use case shares >= 3 -> 10 pts

### Pass Criteria

> **70+ points -> Proceed with additional PMF experiments**
>
> **80+ points -> Enter feature expansion / payment review phase**

---

## 9. Intentionally Excluded from MVP

- Auto-learning
- File upload
- Chrome extension
- Mobile app
- Payments

-> **No complexity before PMF**
