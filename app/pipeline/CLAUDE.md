# Pipeline Architecture

Multi-model AI pipeline: preprocessing → segmentation → labeling → situation analysis → template selection → redaction → [RAG retrieval] → final transform → validation

## Component Table

| Component | File | Description |
|-----------|------|-------------|
| MultiModelPipeline | `multi_model_pipeline.py` | Core orchestrator — `execute_analysis()` + `build_final_prompt()` + `execute_final()` with retry logic |
| TextOnlyPipeline | `text_only_pipeline.py` | Lightweight text-only orchestrator — no metadata, SA intent-driven, T01 fixed, POLITE tone. 3~4 LLM calls (incl. cushion) |
| AiTransformService | `ai_transform_service.py` | AsyncOpenAI wrapper — `call_openai_with_model()` matches `ai_call_fn` signature, token tracking, error classification |
| AiGeminiService | `ai_gemini_service.py` | Google Gemini wrapper — `call_gemini()` with thinking_budget support, token tracking, error classification |
| AiCallRouter | `ai_call_router.py` | Model-name-based LLM router — `call_llm()` routes to Gemini or OpenAI by prefix |
| MultiModelPromptBuilder | `multi_model_prompt_builder.py` | Final model prompts — JSON segment format, dedupeKey, mustInclude, template sections, FINAL_CORE_SYSTEM_PROMPT (~3000 tokens) |
| AiStreamingService | `ai_streaming_service.py` | SSE streaming — `stream_text_only()` (default) + legacy `stream_transform()`, async generators yielding 16 event types via asyncio.Queue |
| CacheMetricsTracker | `cache_metrics_tracker.py` | Token cache tracking — prompt/cached token counters with cumulative hit rate |
| PromptBuilder | `prompt_builder.py` | Intermediate prompts (labeling, gating) — Korean labels, dynamic persona/context/tone blocks |
| TextNormalizer | `preprocessing/text_normalizer.py` | 7-step text preprocessing |
| LockedSpanExtractor | `preprocessing/locked_span_extractor.py` | Regex-based extraction of 17 span types |
| LockedSpanMasker | `preprocessing/locked_span_masker.py` | Mask spans to `{{TYPE_N}}` placeholders, unmask after LLM call |
| MeaningSegmenter | `segmentation/meaning_segmenter.py` | 7-stage rule-based Korean segmenter (uses `regex` PyPI package) |
| LlmSegmentRefiner | `segmentation/llm_segment_refiner.py` | LLM-based long segment refinement (>30 chars, gpt-4o-mini, \|\|\| delimiter) |
| StructureLabelService | `labeling/structure_label_service.py` | LLM #1: 3-tier labeling (gemini-2.5-flash-lite primary + gpt-4o-mini fallback, temp=0.2, thinking=512). 14 labels, migration map, validation, all-GREEN recovery (scanner → fallback model) |
| YellowTriggerScanner | `labeling/yellow_trigger_scanner.py` | Server-side regex scanner for all-GREEN recovery — 4 pattern categories, score threshold, max 2 upgrades. Runs before LLM diversity retry |
| RedLabelEnforcer | `labeling/red_label_enforcer.py` | Server-side RED enforcement — profanity→AGGRESSION, ability denial→PERSONAL_ATTACK, soft profanity→GREEN→YELLOW |
| RedactionService | `redaction/redaction_service.py` | RED/YELLOW counting + redactionMap (no processedText assembly) |
| TemplateRegistry | `template/template_registry.py` | 12 templates (T01-T12) with section orders and persona skip rules |
| TemplateSelector | `template/template_selector.py` | PURPOSE→CONTEXT→keyword selection, S2 enforcement, persona skip rules |
| StructureTemplate | `template/structure_template.py` | Template model: StructureSection dataclass + StructureTemplate dataclass |
| GatingConditionEvaluator | `gating/gating_condition_evaluator.py` | Evaluates conditions for IdentityBooster / SituationAnalysis |
| IdentityLockBooster | `gating/identity_lock_booster.py` | Optional LLM: semantic locked span extraction (span-only, runs parallel with segmentation/labeling) |
| SituationAnalysisService | `gating/situation_analysis_service.py` | Always-on LLM: facts + intent + metadata validation, RED fact filtering (3-tier fallback) |
| CushionStrategyService | `cushion/cushion_strategy_service.py` | Per-YELLOW parallel LLM cushion strategy (gemini-2.5-flash-lite, temp=0.3, max_tokens=800 incl. thinking=512). Heuristic overall_tone + transition_notes |
| OutputValidator | `validation/output_validator.py` | 13 validation rules + template-aware S2 check, 1 retry on ERROR |

## Complete Data Flow

```
1. INPUT: persona, contexts[], tone_level, original_text, user_prompt?, sender_info?, topic?, purpose?

2. PREPROCESSING
   ├─ TextNormalizer.normalize(original_text) → normalized (7-step Unicode cleanup)
   ├─ LockedSpanExtractor.extract(normalized) → regex_spans (17 types, overlap-resolved)
   └─ LockedSpanMasker.mask(normalized, regex_spans) → masked_text

3. PARALLEL: SituationAnalysis + MetadataCheck (always-on)
   └─ analyze(persona, contexts, tone, masked_text, user_prompt, sender_info, topic, purpose)
      → facts[] (max 5, with source quotes), intent, metadata_check

4. PARALLEL LAUNCH: IdentityBooster (async, non-blocking)
   ├─ should_fire: toggle=true OR (BOSS/CLIENT/OFFICIAL AND spans≤1 AND text≥80chars)
   └─ boost(persona, normalized, existing_spans, masked) → extra_spans (SEMANTIC only)

5. SEGMENTATION (uses regex-only masked_text, not blocked by booster)
   ├─ MeaningSegmenter.segment(masked_text) → segments[] (7-stage hierarchical)
   └─ LlmSegmentRefiner.refine(segments) → refined[] (optional, >30 char segments)

6. LABELING
   ├─ StructureLabelService.label(...) → labeled_segments[] (LLM, 14 labels)
   │   └─ all-GREEN recovery: yellow_trigger_scanner first → LLM diversity retry fallback
   └─ RedLabelEnforcer.enforce(labeled) → enforced[] (server-side RED overrides)

6b. COLLECT BOOSTER RESULT (after labeling)
   └─ _merge_and_reindex_spans(regex_spans, extra_spans) → all_spans (re-indexed)

7. COLLECT SA + RED FACT FILTERING + METADATA OVERRIDE
   ├─ filter_red_facts(situation_analysis, masked_text, labeled_segments)
   │   → filtered_facts (3-tier: position overlap → normalized contains → semantic word overlap)
   └─ If metadata_check.meets_threshold(): apply inferred topic/purpose/context

8. TEMPLATE SELECTION (with corrected metadata)
   ├─ TemplateSelector.select(contexts, topic, purpose, label_stats) → template + effective_sections
   └─ S2 enforcement (if ACCOUNTABILITY or NEGATIVE_FEEDBACK present)

9. REDACTION
   └─ RedactionService.process(labeled_segments) → redaction_map{}, red_count, yellow_count
      (RED segments get text=null in JSON, no processedText assembly)

9a. CUSHION STRATEGY (if YELLOW segments exist)
    ├─ Per-YELLOW parallel LLM calls (gemini-2.5-flash-lite, temp=0.3, thinking=512)
    ├─ Each call: system prompt (cushion expert) + user (SA context + target YELLOW + neighbors)
    ├─ Output: {segment_id, label, approach, cushion_phrase (≤15자), avoid} per YELLOW
    ├─ Heuristics: _derive_overall_tone() + _derive_transition_notes()
    ├─ cushion_phrase > 15자 → truncated to 15
    └─ On failure: try/except → continue without cushion (pipeline never breaks)

9b. RAG RETRIEVAL (optional, if RAG_ENABLED=true)
    ├─ _retrieve_rag(original_text, analysis, persona, contexts, tone_level)
    ├─ Build unified query: original_text + SA.intent + persona + contexts
    ├─ embed_text(query) → 1536-dim vector (1 OpenAI API call)
    ├─ rag_index.search() → RagResults (6 categories, metadata pre-filter + cosine + MMR)
    │   expression_pool → section filter, cushion → yellow_label filter
    │   forbidden → vector + trigger_phrases substring, policy → context filter
    │   example → persona+context filter, domain_context → context filter
    └─ On failure: try/except → continue without RAG (pipeline never breaks)

10. FINAL PROMPT BUILDING (MultiModelPromptBuilder / TextOnlyPipeline)
    ├─ Sort labeled_segments by start_pos → OrderedSegment[] (with dedupeKey, mustInclude)
    ├─ build system prompt: CORE + template + [cushion strategy block] + RAG system block
    │   └─ If cushion_strategy exists: _build_system_prompt_with_cushion() appends cushion block
    │       (per-YELLOW approach/phrase/avoid + 적용 규칙: 종결어미 반복 금지, 습니다 연속 3회 금지)
    ├─ build_final_user_message: SA + RAG user block (policy, domain, examples) + JSON {meta, segments[], placeholders{}}
    └─ → FinalPromptPair(system, user, spans, redaction_map)

11. FINAL TRANSFORM (LLM #2)
    ├─ Call Final LLM (gemini-2.5-flash, temp=0.85, max=4000 tokens, dynamic thinking)
    ├─ LockedSpanMasker.unmask(llm_output, spans) → final_text
    ├─ OutputValidator.validate(final_text, original, spans, template...) → ValidationResult
    └─ RETRY once if ERROR or retryable WARNING (temp=0.3, with retry hints)

12. OUTPUT: PipelineResult(transformed_text, validation_issues, stats)
```

## Preprocessing

### TextNormalizer — 7 Steps

1. Unicode NFC normalization (`unicodedata.normalize("NFC")`)
2. Remove invisible characters (zero-width, soft hyphen, BOM, etc.)
3. Remove control characters except `\n`, `\r`, `\t`
4. Normalize line endings: `\r\n` and `\r` → `\n`
5. Collapse multiple spaces/tabs to single space
6. Collapse 3+ consecutive newlines to 2 newlines
7. Strip leading/trailing whitespace

### LockedSpanExtractor — 17 Span Types

Extraction order (priority, longer match wins on overlap):

| # | Type | Pattern Examples |
|---|------|-----------------|
| 1 | EMAIL | user@example.com |
| 2 | URL | https://..., www.example.com |
| 3 | PHONE | 010-1234-5678, 02.123.4567 |
| 4 | ACCOUNT | 123-456-789012 (bank account) |
| 5 | DATE | 2025년 3월 15일, 2025/03/15, 2025-03-15, 3월 15일 |
| 6 | TIME | 오전 10시 30분, 오후 2시~5시 |
| 7 | TIME_HH_MM | 14:30 |
| 8 | MONEY | 50,000원, 100.5원 |
| 9 | UNIT_NUMBER | 5개, 3명, 10kg, 50%, 2개월 |
| 10 | LARGE_NUMBER | 1000+, 1,000+ formatted |
| 11 | UUID | 8-4-4-4-12 hex |
| 12 | FILE_PATH | report.pdf, ./data/file.xlsx |
| 13 | ISSUE_TICKET | #1234, PROJ-1234 |
| 14 | VERSION | v1.0, v1.0.0 |
| 15 | QUOTED_TEXT | "text", 'text', "text" (2-60 chars) |
| 16 | IDENTIFIER | camelCase, snake_case, PascalCase (optional ()) |
| 17 | HASH_COMMIT | 7-40 hex chars (git commit) |

**Algorithm:** Collect all matches → sort by start_pos then length desc → resolve overlaps → generate `{{TYPE_N}}` placeholders → return sorted list

### LockedSpanMasker

- `mask(text, spans)` — replace each span at position with `{{TYPE_N}}` placeholder
- `unmask(output, spans)` — flexible regex `\{\{\s*([A-Z]+)[-_](\d+)\s*\}\}`, tracks restored/missing spans
- Missing spans logged as warnings; if original text found verbatim (not via placeholder), logged as "preserved without placeholder"

## Segmentation

### MeaningSegmenter — 7 Stages

| Stage | Confidence | What It Splits On |
|-------|------------|-------------------|
| 1. Strong structural | 1.0 | Blank lines (`\n\n+`), separators (`---`, `===`), bullets (`-`, `•`), numbered lists |
| 2. Korean sentence endings | 0.95 | Formal (습니다, 겠습니다), polite (세요, 에요), casual (어, 지), narrative (했음, 했다) — with ambiguous ending suppression (는데, 니까, 거든, 고...) |
| 3. Weak punctuation | 0.9 | After `.!?;` or `…` or `--` with space/EOL |
| 4. Length safety split | 0.85 | Segments > 250 chars → split at nearest weak boundary near midpoint, avoid postposition splits |
| 5. Enumeration detection | 0.9 | Segments > 60 chars with 3+ comma/delimiter/parallel items (each 15+ chars) |
| 6. Discourse markers | 0.88 | 39 Korean markers (그리고, 또한, 하지만, 그래서...) at sentence start, segments > 80 chars |
| 7. Over-segmentation merge | — | Merge 3+ consecutive <5 char segments, protect `{{TYPE_N}}` placeholders as boundaries |

**Internal types:** `_SplitUnit` (text, start, end, confidence), `_ProtectedRange` (start, end, type: PLACEHOLDER/PARENTHETICAL/QUOTED)

### LlmSegmentRefiner

- **Trigger:** Segments > 30 chars
- **Model:** gpt-4o-mini, temp=0.0, max_tokens=600
- **Prompt:** Semantic segmentation expert; preserve text exactly; use `|||` delimiters; rule 6: 연결어미(~라기보단, ~해서, ~하게 등)로 끝나는 불완전 조각 분리 금지
- **Input:** Numbered list `[N] segment text`
- **Output parsing:** `[N] text1 ||| text2 ||| text3` → validate all parts exist in original → rebuild segment IDs (T1, T2...)

## Label System

### 14 Labels across 3 Tiers

**GREEN (5) — Content preserved, expression rewritten:**
| Label | Description | Processing |
|-------|-------------|------------|
| CORE_FACT | Essential facts (dates, numbers, status, results, actions) | Reconstruct with new sentences, preserve all data |
| CORE_INTENT | Core request/goal (ask, propose, alternative, question) | Preserve intent, rewrite expression |
| REQUEST | Explicit request/favor | Preserve, soften if needed |
| APOLOGY | Apology/asking forgiveness | Preserve sincerity |
| COURTESY | Conventional politeness (greeting, thanks, title) | Preserve |

**YELLOW (5) — Meaning preserved, presentation rewritten + cushion added:**
| Label | Description | Strategy |
|-------|-------------|----------|
| ACCOUNTABILITY | Responsibility assignment | ① Cushion → ② Fact → ③ Direction; change subject to situation/system |
| SELF_JUSTIFICATION | Self-defense/excuses | Remove defense frame, keep work context as fact |
| NEGATIVE_FEEDBACK | Negative evaluation | Convert to request form with affirmation first |
| EMOTIONAL | Emotional expression | Never delete; convert direct→indirect emotion |
| EXCESS_DETAIL | Over-explanation/repetition | Remove duplicates; convert speculation→possibility |

**RED (4) — Deleted entirely:**
| Label | Description |
|-------|-------------|
| AGGRESSION | Attack, mockery, provocation |
| PERSONAL_ATTACK | Character/ability insult |
| PRIVATE_TMI | Unnecessary personal info (health, household, job stress) |
| PURE_GRUMBLE | Pure complaints unrelated to message |

### StructureLabelService (LLM #1)

- **Primary Model:** gemini-2.5-flash-lite, temp=0.2, max_tokens=800, thinking_budget=512
- **Fallback Model:** gpt-4o-mini (used for all-GREEN recovery fallback)
- **Output format:** `SEG_ID|LABEL` (one per line)
- **Validation:** MIN_COVERAGE = 0.6 (at least 60% segments labeled), must have at least one CORE_FACT/CORE_INTENT/REQUEST
- **Hard triggers** (1+ → force YELLOW): direct judgment of recipient, blame + generalization (매번/맨날/항상), emotional outburst (답답하다, 화가 난다), defensive structure
- **Soft triggers** (2+ → force YELLOW): external blame + recipient fault, indirect emotion, speculation (아마, ~것 같다), content repetition, persona-context combos
- **Migration map** (old 8 labels → new 5): ACCOUNTABILITY_FACT/JUDGMENT → ACCOUNTABILITY, SELF_CONTEXT/DEFENSIVE → SELF_JUSTIFICATION, SPECULATION/OVER_EXPLANATION → EXCESS_DETAIL
- **All-GREEN recovery (4+ segments):** 1) YellowTriggerScanner regex scan (0 LLM calls) → if upgrades found, apply and skip LLM retry; 2) fallback to gpt-4o-mini plain call (model diversity)
- **YellowTriggerScanner:** 4 categories (blame+generalization, emotion, speculation, defense), strong (+2) / soft (+1) scoring, SCORE_THRESHOLD=2, MAX_UPGRADES=2, GREEN segments only
- **Other retry logic:** coverage < 60% → retry with missing segment warning; final fallback → all COURTESY
- **StructureLabelResult** includes `yellow_recovery_applied` and `yellow_upgrade_count` metrics

### RedLabelEnforcer (Server-side)

**Confirmed patterns (immediate RED):**
- Profanity (ㅅㅂ, ㅄ, 시발, 병신, 개새끼, 지랄...) → AGGRESSION
- Ability denial (그것도 못, 뇌가 있, 무능...) → PERSONAL_ATTACK
- Sarcastic praise + markers ((잘|대단|훌륭)...시네요 ㅋㅋ) → AGGRESSION

**Ambiguous patterns (GREEN→YELLOW only):**
- Soft profanity (미친, 개같, ㅈㄴ) → upgrade GREEN to EMOTIONAL

## Template System

### 9 Structure Sections (S0-S8)

| Section | Name (KR) | Instruction | Length |
|---------|-----------|-------------|--------|
| S0 | 인사 (Greeting) | COURTESY-based greeting; include sender if available | 1 sentence |
| S1 | 공감/유감 (Acknowledge) | Empathy/regret/thanks for recipient's situation | 1-2 sentences |
| S2 | 내부 확인 (Our Effort) | Our side's verification/inspection effort; blame mitigation | 1 sentence |
| S3 | 핵심 사실 (Facts) | CORE_FACT + ACCOUNTABILITY rewrite; preserve numbers/dates/causes | 1-3 sentences |
| S4 | 책임 프레이밍 (Responsibility) | Set responsibility direction; change subject to situation/system/process | 1-2 sentences |
| S5 | 요청/행동 (Request) | REQUEST + NEGATIVE_FEEDBACK rewrite; preserve deadline/condition | 1-2 sentences |
| S6 | 대안/다음 단계 (Options) | CORE_INTENT + alternatives; concrete resolution direction | 1-3 sentences |
| S7 | 정책/한계 (Policy) | Rejection grounds; policy-based gentle explanation; no emotion | 1-2 sentences |
| S8 | 마무리 (Closing) | Thanks + resolution intent or signature; brief | 1 sentence |

Each section has expression_pool examples (e.g., S2: "내부 확인 결과", "로그 기준으로 보면", "담당 부서와 확인한 바로는")

### 12 Templates (T01-T12)

| ID | Name | Sections | Selection Trigger | Persona Rules |
|----|------|----------|-------------------|---------------|
| T01 | 일반 전달 (General) | S0,S1,S3,S5,S6,S8 | Default; PURPOSE=INFO_DELIVERY/NEXT_ACTION_CONFIRM | BOSS/PROF/OFF: shorten S1 |
| T02 | 자료 요청 (Data Request) | S0,S1,S3,S5,S8 | PURPOSE=DATA_REQUEST; CONTEXT=REQUEST | BOSS/PROF/OFF: shorten S1 |
| T03 | 독촉 (Nagging) | S0,S1,S3,S5,S8 | CONTEXT=URGING | All: shorten S1 |
| T04 | 일정 조율 (Schedule) | S0,S1,S3,S4,S6,S8 | PURPOSE=SCHEDULE_COORDINATION; CONTEXT=SCHEDULE_DELAY | PARENT: expand S1 |
| T05 | 사과/수습 (Apology) | S0,S1,S2,S3,S6,S8 | PURPOSE=APOLOGY_RECOVERY; CONTEXT=APOLOGY/SUPPORT | CLIENT: expand S1,S2 |
| T06 | 거절/불가 (Rejection) | S0,S1,S7,S3,S6,S8 | PURPOSE=REJECTION_NOTICE; CONTEXT=REJECTION/CONTRACT | CLIENT: expand S1,S2 |
| T07 | 공지/안내 (Announcement) | S0,S3,S5,S8 | PURPOSE=ANNOUNCEMENT; CONTEXT=ANNOUNCEMENT | (none) |
| T08 | 피드백 (Feedback) | S0,S1,S3,S5,S6,S8 | CONTEXT=FEEDBACK | PARENT: expand S1 |
| T09 | 책임 분리 (Blame Separation) | S0,S1,S2,S3,S4,S6,S8 | CONTEXT=COMPLAINT/BILLING/CIVIL_COMPLAINT | CLIENT: expand S1,S2 |
| T10 | 관계 회복 (Relationship) | S0,S1,S3,S6,S8 | PURPOSE=RELATIONSHIP_RECOVERY; CONTEXT=GRATITUDE | PARENT: expand S1 |
| T11 | 환불 거절 (Refund Rejection) | S0,S1,S2,S3,S7,S6,S8 | PURPOSE=REFUND_REJECTION; refund keyword + topic override | CLIENT: expand S1,S2 |
| T12 | 경고/방지 (Warning) | S0,S1,S3,S5,S6,S8 | PURPOSE=WARNING_PREVENTION | BOSS/PROF/OFF: shorten S1 |

### Template Selection Logic (priority order)

1. **PURPOSE → direct template mapping** (highest priority)
2. **Primary CONTEXT → mapping** (first context in list)
3. **Topic override** (REFUND_CANCEL + rejection-like → T11)
4. **Keyword override** (refund keywords + NEGATIVE_FEEDBACK → T11)
5. **S2 enforcement** (ACCOUNTABILITY or NEGATIVE_FEEDBACK → inject S2 if missing)
6. **Persona skip rules** (remove/shorten/expand sections per persona)

## Gating (Conditional LLM Calls)

### GatingConditionEvaluator

| Gate | Condition | Always? |
|------|-----------|---------|
| IdentityBooster | toggle=true OR (BOSS/CLIENT/OFFICIAL AND spans≤1 AND text≥80 chars) | No |
| SituationAnalysis | Always True | Yes |

### SituationAnalysisService (Always-on, with integrated metadata validation)

- **Model:** gpt-4o-mini, temp=0.2, max_tokens=650
- **Output:** JSON `{ facts: [{content, source}], intent, metadata_check: {should_override, confidence, inferred: {topic, purpose, primary_context}} }`
- **Facts:** max 5, source = exact quotes from original, preserve `{{TYPE_N}}` placeholders, deictic resolution (이것→concrete), suppress stopwords
- **Metadata check:** validates user-selected topic/purpose against text content; override only on clear mismatch (confidence >= 0.72)
- **RED fact filtering (3-tier fallback):**
  1. Exact position-based overlap check
  2. Normalized string contains (remove non-alphanum Korean)
  3. Semantic word overlap (2+ meaningful words from fact.source in RED segment)

### IdentityLockBooster (Optional, runs parallel with segmentation/labeling)

- **Model:** gemini-2.5-flash-lite, temp=0.2, max_tokens=300, thinking_budget=None (disabled)
- **Extracts:** Proper nouns, filenames, code names (not generic words/roles)
- **Output:** `- item` per line, or "없음"
- **Returns:** `BoosterResult(extra_spans, prompt_tokens, completion_tokens)` — SEMANTIC spans only, no re-indexing/remasking
- **Span construction:** Word-boundary-aware regex, overlap check against existing spans, type=SEMANTIC
- **Orchestrator merge:** `_merge_and_reindex_spans()` combines regex + semantic spans after labeling; `build_final_prompt()` applies booster span placeholders to segment text
- **Error handling:** Booster failure is non-fatal (try/except, logged as warning)

## Redaction

- Count RED & YELLOW segments by tier
- Build `redaction_map` for RED segments: `[REDACTED:{LABEL}_{N}]` → original text
- RED segments get `text=null` in the JSON segments passed to Final LLM
- No processedText assembly — Final model receives JSON directly

## Final Prompt Builder (`multi_model_prompt_builder.py`)

### OrderedSegment Structure

```python
@dataclass
class OrderedSegment:
    id: str              # segment ID
    order: int           # 1-based position by start position
    tier: str            # "GREEN", "YELLOW", "RED"
    label: str           # e.g., "CORE_FACT"
    text: str | None     # None for RED
    dedupe_key: str | None  # normalized text for dedup (None for RED)
    must_include: list[str]  # {{TYPE_N}} placeholders that must appear in output
```

### System Prompt Structure (~3000 tokens)

1. **FINAL_CORE_SYSTEM_PROMPT:** Role (Korean communication expert), input format (JSON meta + segments + placeholders), placeholder rules (MUST preserve {{TYPE_N}}), **output rules (text only, no meta/emoji)**, **forbidden AI phrases**, **naturalness rules**, 3-tier processing rules (GREEN: preserve content rewrite expression; YELLOW: preserve meaning rewrite + cushion; RED: delete, no inference), deduplication rules, connector freedom, core principles
2. **Dynamic persona block** (~50 tokens) — per-persona tone/style guidance + YELLOW cushion phrase
3. **Dynamic context block** (~30 tokens per context) — context-specific rewriting guidance
4. **Dynamic tone level block** — NEUTRAL/POLITE/VERY_POLITE style rules
5. **Template section block** — per-section guidance (label, instruction, length_hint, expression_pool)

### User Message Structure

```json
{
  "meta": {
    "receiver": "직장 상사",
    "context": "요청, 사과",
    "tone": "매우 공손",
    "sender": "이름",
    "template": "T01_GENERAL",
    "sections": "S0,S1,S3,S5,S6,S8"
  },
  "segments": [
    {"id":"T1", "order":1, "tier":"GREEN", "label":"CORE_FACT",
     "text":"...", "dedupeKey":"...", "mustInclude":[...]},
    {"id":"T2", "order":2, "tier":"RED", "label":"AGGRESSION",
     "text":null, "dedupeKey":null, "mustInclude":[]}
  ],
  "placeholders": {
    "{{DATE_1}}": "2025년 3월 15일",
    "{{EMAIL_1}}": "sender@example.com"
  }
}
```

Optional prepended: Situation Analysis (facts + intent), summary from labeling

## Validation — 14 Rules

| Rule | Severity | What It Checks |
|------|----------|----------------|
| EMOJI | ERROR | Emoji Unicode ranges in output |
| FORBIDDEN_PHRASE | ERROR | Meta-commentary: "변환 결과", "다음과 같이", "변환해 드리겠" |
| HALLUCINATED_FACT | WARNING | Numbers 3+ digits or Korean spelled-out numbers not in original (exceptions: 제3, 3호, 3층) |
| ENDING_REPETITION | WARNING | 3+ consecutive identical Korean sentence endings |
| LENGTH_OVEREXPANSION | WARNING | Output > 6000 chars or > original x 2.5 (for short originals) |
| PERSPECTIVE_ERROR | WARNING | Wrong perspective phrases: "확인해 드리겠습니다", "접수되었습니다" (if recipient persona mismatch) |
| LOCKED_SPAN_MISSING | ERROR | `{{TYPE_N}}` in raw LLM output but missing in final text after unmask |
| REDACTED_REENTRY | ERROR | RED segment marker `[REDACTED:LABEL_N]` appears in final output |
| REDACTION_TRACE | ERROR | Redaction trace patterns in output |
| CORE_NUMBER_MISSING | WARNING | CORE_FACT segment had numbers but output doesn't (with safe context exceptions) |
| CORE_DATE_MISSING | WARNING | CORE_FACT segment had dates but output doesn't |
| SOFTEN_CONTENT_DROPPED | WARNING | YELLOW segment `{{TYPE_N}}` placeholders not in output (checked via mustInclude) |
| SECTION_S2_MISSING | ERROR (if enforced) | Template requires S2 but no effort/verification pattern found in output |
| INFORMAL_CONJUNCTION | WARNING | Informal conjunctions (어쨌든/아무튼/걍/근데) detected in output |

**Retry:** If ERROR-level issues or retryable WARNINGs (CORE_NUMBER_MISSING, CORE_DATE_MISSING, SOFTEN_CONTENT_DROPPED, SECTION_S2_MISSING, INFORMAL_CONJUNCTION), retry once with temp=0.3 and retry hints appended to prompts.

## Streaming — 17 SSE Event Types

| Event | Data | When |
|-------|------|------|
| `phase` | string | Pipeline step change (17 phase values, incl. `cushion_strategizing`, `rag_retrieving`) |
| `spans` | JSON array | Locked spans extracted `[{placeholder, original, type}]` |
| `maskedText` | string | Text after masking |
| `segments` | JSON array | Segments `[{id, text, start, end}]` |
| `labels` | JSON array | Labeled segments `[{segmentId, label, tier, text}]` |
| `situationAnalysis` | JSON object | `{facts[], intent}` |
| `processedSegments` | JSON array | Post-redaction `[{id, tier, label, text|null}]` |
| `templateSelected` | JSON object | `{templateId, templateName, metadataOverridden}` |
| `cushionStrategy` | JSON object | `{overallTone, strategies[], transitionNotes}` (if YELLOW segments) |
| `ragResults` | JSON object | `{totalHits, categories: {cat: count}}` (only if RAG_ENABLED + hits) |
| `delta` | string | Streaming token from Final LLM |
| `retry` | string | Validation failed, retrying ("validation_failed") |
| `validationIssues` | JSON array | `[{type, severity, message, matchedText}]` |
| `stats` | JSON object | Pipeline stats (counts, latency, gating flags, cushionApplied) |
| `usage` | JSON object | Token usage (prompt/completion per phase) |
| `done` | string | Final unmasked transformed text |
| `error` | string | Error message |

**Flow:** asyncio.Queue → StreamCallback sends events during analysis → [cushion strategy] → [RAG retrieval] → build_final_prompt (with cushion block) → stream Final LLM via delta events → validate → retry if needed → send final events (validationIssues, stats, done)

## LLM Call Summary

| Call | Model | Temp | Max Tokens | Thinking | Always? | Purpose |
|------|-------|------|------------|----------|---------|---------|
| SituationAnalysis | gpt-4o-mini | 0.2 | 650 | — | Yes | Facts + intent + metadata validation |
| StructureLabel | gemini-2.5-flash-lite | 0.2 | 800 | 128 | Yes | 14-label classification |
| CushionStrategy | gemini-2.5-flash-lite | 0.3 | 800 | 512 | YELLOW 있을 때 | Per-YELLOW 쿠션 전략 (병렬) |
| Final Transform | gemini-2.5-flash | 0.85 | 4000 | dynamic (512/768/1024) | Yes | Template-guided rewriting |
| IdentityBooster | gemini-2.5-flash-lite | 0.2 | 300 | — | Conditional | Semantic span extraction |
| LlmSegmentRefiner | gpt-4o-mini | 0.0 | 600 | — | Conditional (>30 chars) | Long segment splitting |
| Final Retry | gemini-2.5-flash | 0.3 | 4000 | min(1024, base×2) | On validation failure | Re-generation with hints |

Labeling: Gemini primary + gpt-4o-mini fallback for all-GREEN recovery. Router: `ai_call_router.call_llm()` routes by model prefix.

Base: 3-4 calls (SA + Label + [Cushion] + Final). Max: 6 calls (+1 retry = 7).
