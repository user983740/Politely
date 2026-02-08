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

---

## 6. Technical Architecture

- **Frontend:** React + TypeScript + Tailwind CSS (Feature-Sliced Design)
- **Backend:** Spring Boot (Domain-Driven Design)
- **Infrastructure:** AWS (EC2, RDS, S3, CloudWatch)
- **AI:** OpenAI API (server-side prompt assembly)

---

## 7. Data & Metrics Tracking (MVP Core)

| Metric | Meaning |
|--------|---------|
| DAU / WAU | Usage frequency |
| Transformation request count | Core usage volume |
| **Partial re-transformation ratio** | Quality satisfaction signal |
| Persona/situation selection distribution | PMF assessment |
| Average input length | Actual usage context |
| Return visit interval | Habit formation |

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
