# Pipeline Deep Dive: 코드 실행 흐름 상세 문서

> 파이프라인의 시작부터 끝까지, 각 파일의 모든 함수가 어떤 입력을 받아 어떻게 처리하고 무엇을 반환하는지 설명한다.

---

## 목차

1. [타입 정의](#1-타입-정의)
2. [0단계: API 진입점](#2-0단계-api-진입점)
3. [1단계: 서비스 레이어](#3-1단계-서비스-레이어)
4. [2단계: 오케스트레이터](#4-2단계-오케스트레이터)
5. [3단계: 전처리](#5-3단계-전처리)
6. [4단계: 상황 분석 + 아이덴티티 부스터](#6-4단계-상황-분석--아이덴티티-부스터)
7. [5단계: 세그멘테이션](#7-5단계-세그멘테이션)
8. [6단계: 라벨링](#8-6단계-라벨링)
9. [7단계: 템플릿 선택](#9-7단계-템플릿-선택)
10. [8단계: 리댁션](#10-8단계-리댁션)
11. [9단계: 최종 프롬프트 빌드](#11-9단계-최종-프롬프트-빌드)
12. [10단계: LLM 호출 라우팅](#12-10단계-llm-호출-라우팅)
13. [11단계: 언마스킹 + 검증](#13-11단계-언마스킹--검증)
14. [12단계: 스트리밍](#14-12단계-스트리밍)

---

## 1. 타입 정의

파이프라인 전체에서 사용되는 핵심 데이터 타입들이다.

### 1-1. Enum 타입 (`app/models/enums.py`)

```python
class Topic(str, Enum):         # REFUND_CANCEL, OUTAGE_ERROR, ... (11종)
class Purpose(str, Enum):       # INFO_DELIVERY, DATA_REQUEST, ... (11종)

# 파이프라인 내부 enum
class SegmentLabelTier(str, Enum):  # GREEN, YELLOW, RED
class SegmentLabel(str, Enum):
    # GREEN (5): CORE_FACT, CORE_INTENT, REQUEST, APOLOGY, COURTESY
    # YELLOW (5): ACCOUNTABILITY, SELF_JUSTIFICATION, NEGATIVE_FEEDBACK, EMOTIONAL, EXCESS_DETAIL
    # RED (4): AGGRESSION, PERSONAL_ATTACK, PRIVATE_TMI, PURE_GRUMBLE
    #
    # @property tier -> SegmentLabelTier  (각 라벨에서 소속 tier를 바로 알 수 있음)

class LockedSpanType(str, Enum):
    # EMAIL, URL, ACCOUNT, DATE, TIME, TIME_HH_MM, PHONE, MONEY, UNIT_NUMBER,
    # LARGE_NUMBER, UUID, FILE_PATH, ISSUE_TICKET, VERSION, QUOTED_TEXT, IDENTIFIER,
    # HASH_COMMIT, SEMANTIC
    #
    # @property placeholder_prefix -> str  (예: DATE → "DATE", SEMANTIC → "NAME")
    # 같은 value를 공유하는 타입들: TIME_HH_MM="TIME", UNIT_NUMBER="NUMBER", LARGE_NUMBER="NUMBER"

class ValidationIssueType(str, Enum):  # EMOJI, FORBIDDEN_PHRASE, ... (14종)
class Severity(str, Enum):      # ERROR, WARNING
```

### 1-2. 도메인 데이터클래스 (`app/models/domain.py`)

```python
@dataclass(frozen=True)
class LockedSpan:
    index: int              # 같은 타입 내 순번 (예: DATE의 1번째 → 1)
    original_text: str      # 원본 텍스트 (예: "2025년 3월 15일")
    placeholder: str        # 치환 문자열 (예: "{{DATE_1}}")
    type: LockedSpanType    # 스팬 종류
    start_pos: int          # 정규화된 텍스트 내 시작 위치
    end_pos: int            # 정규화된 텍스트 내 끝 위치

@dataclass(frozen=True)
class Segment:
    id: str                 # "T1", "T2", ... (순번 ID)
    text: str               # 세그먼트 텍스트 (마스킹된 상태)
    start: int              # masked_text 내 시작 위치
    end: int                # masked_text 내 끝 위치

@dataclass(frozen=True)
class LabeledSegment:
    segment_id: str         # "T1", "T2", ...
    label: SegmentLabel     # 14종 라벨 중 하나
    text: str               # 세그먼트 텍스트
    start: int              # 시작 위치
    end: int                # 끝 위치

@dataclass(frozen=True)
class LlmCallResult:
    content: str            # LLM 응답 텍스트 (strip된 상태)
    analysis_context: str | None  # 분석 컨텍스트 (현재 대부분 None)
    prompt_tokens: int      # 프롬프트 토큰 수
    completion_tokens: int  # 완성 토큰 수

@dataclass(frozen=True)
class ValidationIssue:
    type: ValidationIssueType   # 어떤 규칙 위반인지
    severity: Severity          # ERROR 또는 WARNING
    message: str                # 한국어 설명 메시지
    matched_text: str | None    # 문제가 된 텍스트 조각

@dataclass(frozen=True)
class ValidationResult:
    passed: bool                        # ERROR가 없으면 True
    issues: list[ValidationIssue]       # 모든 이슈 목록
    # has_errors() -> bool              ERROR가 있는지
    # errors() -> list[ValidationIssue] ERROR만 필터
    # warnings() -> list[ValidationIssue] WARNING만 필터

@dataclass(frozen=True)
class TransformResult:
    transformed_text: str           # 최종 변환된 텍스트
    analysis_context: str | None    # 분석 컨텍스트

@dataclass(frozen=True)
class PipelineStats:
    analysis_prompt_tokens: int         # 분석 페이즈 총 프롬프트 토큰
    analysis_completion_tokens: int     # 분석 페이즈 총 완성 토큰
    final_prompt_tokens: int            # 최종 LLM 프롬프트 토큰
    final_completion_tokens: int        # 최종 LLM 완성 토큰
    segment_count: int                  # 총 세그먼트 수
    green_count: int
    yellow_count: int
    red_count: int
    locked_span_count: int              # 고정 스팬 수
    retry_count: int                    # 검증 실패 리트라이 횟수 (0 또는 1)
    identity_booster_fired: bool
    situation_analysis_fired: bool
    metadata_overridden: bool           # SA가 메타데이터를 오버라이드했는지
    chosen_template_id: str             # 선택된 템플릿 ID
    total_latency_ms: int               # 최종 페이즈 총 소요 시간
    yellow_recovery_applied: bool       # all-GREEN 복구 적용 여부
    yellow_upgrade_count: int           # YELLOW로 업그레이드된 세그먼트 수

@dataclass(frozen=True)
class LabelStats:
    green_count: int
    yellow_count: int
    red_count: int
    has_accountability: bool
    has_negative_feedback: bool
    has_emotional: bool
    has_self_justification: bool
    has_aggression: bool
    # @staticmethod from_segments(list[LabeledSegment]) -> LabelStats
```

### 1-3. 파이프라인 내부 데이터클래스 (`app/pipeline/multi_model_pipeline.py`)

```python
@dataclass(frozen=True)
class AnalysisPhaseResult:
    masked_text: str                            # 마스킹된 텍스트
    locked_spans: list[LockedSpan]              # 모든 고정 스팬 (regex + semantic 병합 후)
    segments: list[Segment]                     # 세그먼트 목록
    labeled_segments: list[LabeledSegment]      # 라벨링 + RED 강제 적용 후
    redaction: RedactionResult                  # 리댁션 결과
    situation_analysis: SituationAnalysisResult | None
    summary_text: str | None                    # 라벨링 LLM이 생성한 요약 (선택)
    total_analysis_prompt_tokens: int
    total_analysis_completion_tokens: int
    identity_booster_fired: bool
    situation_analysis_fired: bool
    metadata_overridden: bool
    chosen_template_id: str
    chosen_template: StructureTemplate
    effective_sections: list[StructureSection]   # S2 강제 등 규칙 적용 후 실제 섹션
    green_count: int
    yellow_count: int
    red_count: int
    yellow_recovery_applied: bool
    yellow_upgrade_count: int

@dataclass(frozen=True)
class FinalPromptPair:
    system_prompt: str                  # 시스템 프롬프트 (core + template sections)
    user_message: str                   # 유저 메시지 (SA + JSON {meta, segments, placeholders})
    locked_spans: list[LockedSpan]      # 언마스킹에 필요한 스팬
    redaction_map: dict[str, str]       # "[REDACTED:LABEL_N]" → 원본 텍스트

@dataclass(frozen=True)
class PipelineResult:
    transformed_text: str                       # 최종 변환 텍스트
    validation_issues: list[ValidationIssue]    # 검증 이슈 (있을 수 있음)
    stats: PipelineStats | None                 # 파이프라인 통계
```

---

## 2. 0단계: API 진입점

**파일:** `app/api/v1/transform.py`

### `transform(request: TransformTextOnlyRequest) -> TransformResponse`

일반 (비스트리밍) 변환 엔드포인트.

- **입력:** `TransformTextOnlyRequest` (Pydantic 모델)
  ```
  original_text: str                   # 1~2000자
  user_prompt: str | None              # max 500자
  sender_info: str | None              # max 100자
  ```
- **처리:** `text_only_pipeline.execute()` 호출
- **출력:** `TransformResponse(transformed_text: str)`

### `stream_transform(request: TransformTextOnlyRequest) -> EventSourceResponse`

SSE 스트리밍 변환 엔드포인트.

- **입력:** 동일한 `TransformTextOnlyRequest`
- **처리:** 입력 검증 후 `ai_streaming_service.stream_text_only()`을 직접 호출
- **출력:** SSE 이벤트 스트림 (17종 이벤트)

---

## 3. 1단계: 서비스 레이어

**파일:** `app/services/transform_app_service.py`

### `transform(original_text, user_prompt, sender_info) -> TransformResult`

```
입력:
  original_text: str
  user_prompt: str | None
  sender_info: str | None

처리:
  1. validate_transform_request(original_text)   — 2000자 초과 시 ValueError
  2. analysis = await execute_analysis(...)       — 분석 페이즈 실행
  3. rag_results = await _retrieve_rag(...)       — RAG 검색 (RAG_ENABLED일 때만)
  4. result = await execute_final(..., rag_results=rag_results) — 최종 변환 페이즈 실행

반환: TransformResult(transformed_text=result.transformed_text)
```

### `_retrieve_rag(original_text, analysis) -> RagResults | None`

```
입력:
  original_text: str
  analysis: AnalysisPhaseResult

처리:
  1. RAG_ENABLED=false면 None 반환
  2. rag_index.size == 0이면 None 반환
  3. 통합 query 구성: original_text + SA.intent
  4. embed_text(query) → 1536-dim vector (OpenAI 1회 호출)
  5. rag_index.search() → RagResults (6 카테고리, 메타데이터 필터 + cosine + MMR)
  6. 실패 시 try/except → None (파이프라인 중단 금지)

반환: RagResults (6 카테고리 리스트) 또는 None
```

### `validate_transform_request(original_text: str) -> None`

- `len(original_text) > 2000`이면 `ValueError` raise

### `resolve_final_max_tokens() -> int`

- `settings.openai_max_tokens_paid` 반환 (기본 4000)

---

## 4. 2단계: 오케스트레이터

**파일:** `app/pipeline/multi_model_pipeline.py` (~630줄, 핵심 파일)

### `execute_analysis(...) -> AnalysisPhaseResult`

분석 페이즈 전체를 실행하는 메인 함수.

```
입력:
  original_text: str
  user_prompt: str | None
  sender_info: str | None
  identity_booster_toggle: bool
  topic: Topic | None = None
  purpose: Purpose | None = None
  ai_call_fn = None                    — None이면 call_llm 사용
  callback: PipelineProgressCallback | None = None  — SSE 콜백

처리 순서:
  A) normalize(original_text) → normalized
  B) extract(normalized) → regex_spans, mask(normalized, regex_spans) → masked
  C) asyncio.create_task(situation_analysis.analyze(...))  — 비동기 실행
  D) asyncio.create_task(identity_booster.boost(...))      — 조건부 비동기 실행
  E) meaning_segmenter.segment(masked) → segments
  F) llm_segment_refiner.refine(segments, masked) → segments (조건부)
  G) structure_label_service.label(...) → label_result
  H) red_label_enforcer.enforce(label_result.labeled_segments) → enforced_labels
  I) await booster_task → _merge_and_reindex_spans(regex_spans, extra_spans) → all_spans
  J) await situation_task → filter_red_facts() → situation_result
  K) 메타데이터 오버라이드 (SA의 metadata_check.meets_threshold() 기반)
  L) template_selector.select_template(...) → template_result
  M) redaction_service.process(enforced_labels) → redaction

반환: AnalysisPhaseResult (위 모든 결과를 담은 데이터클래스)
```

### `execute_final(...) -> PipelineResult`

최종 변환 페이즈를 실행하는 메인 함수.

```
입력:
  final_model_name: str                — "gemini-2.5-flash"
  analysis: AnalysisPhaseResult        — execute_analysis의 결과
  original_text: str
  sender_info: str | None
  max_tokens: int                      — 4000
  ai_call_fn = None
  rag_results = None

처리 순서:
  1. build_final_prompt(analysis, sender_info, rag_results) → prompt
  2. compute_thinking_budget(segments, labeled_segments, len(original_text)) → thinking_budget
  3. ai_call_fn(final_model_name, prompt.system_prompt, prompt.user_message, -1, max_tokens, None, thinking_budget=thinking_budget)
     → final_result: LlmCallResult
  4. locked_span_masker.unmask(final_result.content, prompt.locked_spans) → unmask_result
  5. output_validator.validate_with_template(...) → validation
  6. 검증 실패 시 (ERROR 또는 retryable WARNING):
     - retry_system = prompt.system_prompt + retry_hint
     - retry_user = prompt.user_message + error_hint + locked_span_hint
     - retry_thinking = min(1024, thinking_budget * 2)
     - ai_call_fn(..., temp=0.3, thinking_budget=retry_thinking) → retry_result
     - 다시 unmask → validate

반환: PipelineResult(transformed_text, validation_issues, stats)
```

### `build_final_prompt(analysis, sender_info, rag_results=None) -> FinalPromptPair`

```
입력: AnalysisPhaseResult + sender_info + RagResults (optional)

처리:
  1. labeled_segments를 start 위치 기준 정렬
  2. 각 세그먼트에 대해:
     - RED면 text=None, dedupe_key=None
     - booster 스팬이 세그먼트 범위 안에 있으면 텍스트에 플레이스홀더 적용
     - YELLOW면 mustInclude = 텍스트 내 {{TYPE_N}} 플레이스홀더 목록
     - OrderedSegment(id, order, tier, label, text, dedupe_key, must_include) 생성
  3. build_final_system_prompt(template, effective_sections, rag_results) → 시스템 프롬프트 + RAG 시스템 블록
     - forbidden → "⛔ 금지 표현" (강제 규칙)
     - expression_pool → "참고 표현"
     - cushion → "YELLOW 쿠션 참고"
  4. build_final_user_message(..., rag_results) → 유저 메시지 + RAG 유저 블록
     - policy → "정책/규정 참고"
     - domain_context → "도메인 배경 참고"
     - example → "변환 예시"

반환: FinalPromptPair(system_prompt, user_message, locked_spans, redaction_map)
```

### `compute_thinking_budget(segments, labeled_segments, original_text_length) -> int`

```
입력: segments, labeled_segments, original_text_length
처리:
  score = 0
  segments 6개 이상이면 +1
  YELLOW 2개 이상 또는 RED 1개 이상이면 +1
  원문 500자 이상이면 +1
반환: score=0 → 512, score≤2 → 768, score=3 → 1024
```

### `build_dedupe_key(text: str | None) -> str | None`

```
입력: 세그먼트 텍스트
처리: {{TYPE_N}} → "type_n" 치환, 공백/구두점 제거, 소문자 변환
반환: 정규화된 중복 검출 키
```

### `_merge_and_reindex_spans(regex_spans, extra_spans) -> list[LockedSpan]`

```
입력: regex_spans: list[LockedSpan], extra_spans: list[LockedSpan]
처리:
  1. 두 리스트를 합쳐서 start_pos 기준 정렬
  2. 타입별 카운터로 재인덱싱 ({{DATE_1}}, {{DATE_2}}, {{NAME_1}}, ...)
반환: 재인덱싱된 LockedSpan 리스트
```

### `PipelineProgressCallback` (콜백 프로토콜)

```python
class PipelineProgressCallback:
    async def on_phase(self, phase: str) -> None                    # 파이프라인 단계 변경
    async def on_spans_extracted(self, spans, masked_text) -> None  # 스팬 추출 완료
    async def on_segmented(self, segments) -> None                  # 세그먼테이션 완료
    async def on_labeled(self, labeled_segments) -> None            # 라벨링 완료
    async def on_situation_analysis(self, fired, result) -> None    # SA 완료
    async def on_redacted(self, labeled_segments, red_count) -> None # 리댁션 완료
    async def on_template_selected(self, template, overridden) -> None # 템플릿 선택 완료
```

기본 구현은 모두 no-op. `ai_streaming_service.py`의 `StreamCallback`이 이를 상속하여 SSE 이벤트를 발행한다.

---

## 5. 3단계: 전처리

### 5-1. TextNormalizer (`app/pipeline/preprocessing/text_normalizer.py`)

#### `normalize(text: str) -> str`

```
입력: 원본 사용자 입력 텍스트
처리 (7단계):
  1. unicodedata.normalize("NFC", text)
  2. 제로 폭 문자 제거 (U+200B, U+FEFF, U+00AD 등)
  3. 제어 문자 제거 (\n, \r, \t 제외)
  4. \r\n → \n, \r → \n
  5. 연속 공백/탭 → 단일 공백
  6. 3줄 이상 연속 개행 → 2줄
  7. 앞뒤 공백 trim
반환: 정규화된 텍스트
```

### 5-2. LockedSpanExtractor (`app/pipeline/preprocessing/locked_span_extractor.py`)

#### `extract(text: str) -> list[LockedSpan]`

```
입력: 정규화된 텍스트
처리:
  1. 17개 패턴을 우선순위 순서대로 정규식 매칭 → _RawMatch(start, end, text, type) 수집
     패턴 순서: EMAIL → URL → PHONE → ACCOUNT → DATE → TIME → TIME_HH_MM → MONEY
     → UNIT_NUMBER → LARGE_NUMBER → UUID → FILE_PATH → ISSUE_TICKET → VERSION
     → QUOTED_TEXT → IDENTIFIER → HASH_COMMIT
  2. start 오름차순 + 길이 내림차순 정렬
  3. _resolve_overlaps(): 겹치는 매치 중 먼저 나온 것(=긴 것) 유지
  4. 타입별 카운터로 placeholder 생성: "{{DATE_1}}", "{{MONEY_1}}" 등
반환: list[LockedSpan] (start_pos 오름차순)

내부 타입:
  @dataclass _RawMatch: start, end, text, type
```

### 5-3. LockedSpanMasker (`app/pipeline/preprocessing/locked_span_masker.py`)

#### `mask(text: str, spans: list[LockedSpan]) -> str`

```
입력: text (정규화된 텍스트), spans (start_pos 오름차순 정렬)
처리: 각 span의 start_pos~end_pos 범위를 span.placeholder로 치환
반환: 마스킹된 텍스트 (예: "2025년 3월 15일" → "{{DATE_1}}")
```

#### `unmask(output: str, spans: list[LockedSpan]) -> UnmaskResult`

```
입력: output (LLM 출력), spans (모든 스팬)
처리:
  1. span_map 구축: placeholder → LockedSpan
  2. 정규식 r"\{\{\s*([A-Z]+)[-_](\d+)\s*\}\}" 로 유연하게 매칭
     → 매치 찾으면 span_map에서 원본 텍스트로 치환
  3. 복원되지 않은 span 체크:
     - 원본 텍스트가 결과에 있으면 "verbatim으로 보존됨" (info 로그)
     - 없으면 missing_spans에 추가

반환: UnmaskResult(text: str, missing_spans: list[LockedSpan])

내부 타입:
  @dataclass UnmaskResult:
    text: str                   # 복원된 최종 텍스트
    missing_spans: list[LockedSpan]  # 복원 실패한 스팬
```

---

## 6. 4단계: 상황 분석 + 아이덴티티 부스터

이 두 컴포넌트는 `execute_analysis()` 안에서 `asyncio.create_task()`로 비동기 병렬 실행된다.

### 6-1. GatingConditionEvaluator (`app/pipeline/gating/gating_condition_evaluator.py`)

#### `should_fire_situation_analysis(text: str) -> bool`

```
항상 True 반환.
```

#### `should_fire_identity_booster(frontend_toggle, locked_spans, text_length) -> bool`

```
입력:
  frontend_toggle: bool
  locked_spans: list[LockedSpan]
  text_length: int
  min_text_length: int = 80
  max_locked_spans: int = 1

반환 True 조건:
  frontend_toggle == True
```

### 6-2. SituationAnalysisService (`app/pipeline/gating/situation_analysis_service.py`)

**LLM 호출 — gpt-4o-mini, temp=0.2, max_tokens=650**

#### `analyze_text_only(masked_text, sender_info, ai_call_fn, user_prompt?) -> SituationAnalysisResult`

```
입력:
  masked_text: str                    # 마스킹된 텍스트
  sender_info: str | None
  ai_call_fn                          # LLM 호출 함수
  user_prompt: str | None = None

처리:
  1. 유저 메시지 조립:
     sender_info (있으면) + user_prompt (있으면) + 원문
  2. ai_call_fn(MODEL="gpt-4o-mini", SYSTEM_PROMPT_TEXT_ONLY, user_message, 0.2, 650, None) → LlmCallResult
  3. _parse_result_text_only(result) → facts + intent only (metadata_check 없음)

반환: SituationAnalysisResult (facts, intent, prompt_tokens, completion_tokens)
  실패 시: SituationAnalysisResult(facts=[], intent="", prompt_tokens=0, completion_tokens=0)

내부 타입:
  @dataclass(frozen=True) Fact:
    content: str        # 사실 요약 (예: "아이의 수학 시험 점수가 {{UNIT_NUMBER_1}}이다")
    source: str         # 원문 인용 (예: "아이가 수학 시험에서 {{UNIT_NUMBER_1}} 맞았는데")

  @dataclass(frozen=True) MetadataCheck:
    should_override: bool               # 메타데이터 오버라이드 필요 여부
    confidence: float                   # 확신도 (0.0~1.0)
    inferred_topic: Topic | None        # 추론된 주제
    inferred_purpose: Purpose | None    # 추론된 목적
    inferred_primary_context: str | None  # 추론된 주 상황
    # meets_threshold() -> bool         should_override AND confidence >= 0.72

  @dataclass(frozen=True) SituationAnalysisResult:
    facts: list[Fact]
    intent: str
    prompt_tokens: int
    completion_tokens: int
    metadata_check: MetadataCheck | None
```

#### `filter_red_facts(original, masked_text, labeled_segments) -> SituationAnalysisResult`

```
입력:
  original: SituationAnalysisResult
  masked_text: str
  labeled_segments: list[LabeledSegment]

처리 (RED 세그먼트와 겹치는 fact 제거, 3단계 폴백):
  각 fact에 대해:
  1. 정확한 위치 기반: masked_text.find(fact.source) → fact 범위와 RED 세그먼트 범위 겹침 검사
  2. 정규화 문자열 포함: _normalize_for_match(fact.source) in _normalize_for_match(red.text)
  3. 의미 단어 겹침: fact.source에서 한국어 2글자 이상 단어 추출, RED 텍스트에 2개 이상 존재

반환: 필터링된 SituationAnalysisResult (facts만 변경, 나머지 동일)
```

### 6-3. IdentityLockBooster (`app/pipeline/gating/identity_lock_booster.py`)

**LLM 호출 — gemini-2.5-flash-lite, temp=0.2, max_tokens=300, thinking 없음**

#### `boost(normalized_text, current_spans, masked_text, ai_call_fn) -> BoosterResult`

```
입력:
  normalized_text: str          # 정규화된 원본 텍스트 (마스킹 전)
  current_spans: list[LockedSpan]  # 기존 regex 스팬
  masked_text: str              # 마스킹된 텍스트
  ai_call_fn                    # LLM 호출 함수

처리:
  1. 유저 메시지: "원문:\n{masked_text}"
  2. ai_call_fn(MODEL, SYSTEM_PROMPT, user_message, 0.2, 300, None, thinking_budget=None)
  3. _parse_semantic_spans(normalized_text, current_spans, result.content) → new_spans

반환: BoosterResult(extra_spans, prompt_tokens, completion_tokens)

내부 타입:
  @dataclass(frozen=True) BoosterResult:
    extra_spans: list[LockedSpan]   # 새로 찾은 SEMANTIC 스팬
    prompt_tokens: int
    completion_tokens: int
```

#### `_parse_semantic_spans(normalized_text, existing_spans, output) -> list[LockedSpan]`

```
처리:
  1. LLM 출력을 줄 단위로 파싱, "- " 접두사 라인만 처리
  2. 각 항목에 대해:
     - word-boundary-aware 정규식으로 normalized_text에서 위치 탐색
     - 기존 스팬과 겹침 검사 (overlaps면 skip)
     - LockedSpan(type=SEMANTIC, placeholder="{{NAME_N}}") 생성
반환: list[LockedSpan]
```

---

## 7. 5단계: 세그멘테이션

### 7-1. MeaningSegmenter (`app/pipeline/segmentation/meaning_segmenter.py`)

**LLM 없이 정규식 기반 7단계 분할**

#### `segment(masked_text: str) -> list[Segment]`

```
입력: masked_text (마스킹된 텍스트)
처리:
  0. 보호 범위 수집: {{TYPE_N}} 플레이스홀더, 괄호 (…), 따옴표 "…"
  1. Stage 1 — 강한 구조 경계 (confidence=1.0):
     빈 줄 (\n\n+), 구분선 (---/===), 불릿 (-/*/•), 번호 목록
  2. Stage 2 — 한국어 종결어미 (confidence=0.95):
     형식체 (습니다/입니다), 해요체 (세요/에요), 반말 (었어/잖아),
     서술체 (했음/했다/ㅋㅋ)
     → 모호한 어미 (는데/니까/거든/고) 필터링:
       텍스트 250자 초과이거나, 뒤에 담화 표지가 오면 분할
  3. Stage 3 — 약한 구두점 (confidence=0.9): .!?;… 뒤 공백
  4. Stage 4 — 길이 안전 분할 (confidence=0.85):
     250자 초과 세그먼트 → 중간 근처 공백/쉼표에서 분할
     조사 뒤 분할 방지 (은/는/이/가/을/를 등)
  5. Stage 5 — 열거 감지 (confidence=0.9):
     60자 초과 + 3개 이상 쉼표/구분자/고 병렬 구조
  6. Stage 6 — 담화 표지 분할 (confidence=0.88):
     80자 초과 + 39개 한국어 담화 표지 (그리고/하지만/그래서 등)
  7. Stage 7 — 과분할 병합:
     연속 3개 이상 5자 미만 세그먼트 → 병합
     플레이스홀더 경계 보호

  최종: Segment(id="T{n}", text=..., start=..., end=...) 생성

반환: list[Segment]

내부 타입:
  class _SplitUnit:  text, start, end, confidence
  class _ProtectedRange:  start, end, type ("PLACEHOLDER"/"PARENTHETICAL"/"QUOTED")
```

### 7-2. LlmSegmentRefiner (`app/pipeline/segmentation/llm_segment_refiner.py`)

**LLM 호출 — gpt-4o-mini, temp=0.0, max_tokens=600**

#### `refine(segments, masked_text, ai_call_fn, min_length=30) -> RefineResult`

```
입력:
  segments: list[Segment]       # MeaningSegmenter의 결과
  masked_text: str
  ai_call_fn                    # LLM 호출 함수
  min_length: int = 30          # 이 길이 초과 세그먼트만 대상

처리:
  1. 30자 초과 세그먼트 인덱스 수집 → long_indices
  2. 없으면 prompt_tokens=0으로 즉시 반환
  3. 유저 메시지 조립: "[1] segment_text\n[2] segment_text\n..."
  4. ai_call_fn("gpt-4o-mini", SYSTEM_PROMPT, user_msg, 0.0, 600, None)
  5. _parse_response(): 응답에서 "[N] text1 ||| text2" 파싱
  6. _validate_parts(): 각 파트가 원본 텍스트에 순서대로 존재하는지 검증
  7. _rebuild_segments(): 분할된 파트로 세그먼트 재구축, ID 재번호 (T1, T2, ...)

프롬프트 규칙 6: 연결어미(~라기보단, ~보단, ~해서, ~이라서, ~하게, ~인데, ~거든, ~니까)로
  끝나는 불완전 조각 분리 금지. 뒤 절과 함께 하나의 단위로 유지.
예시 1: 완결된 문장 경계에서 분절 (확인했습니다 ||| 관련 자료는~)
예시 2: 연결어미(~지만)로 끝나는 조각이 생기므로 분절하지 않음 (원문 유지)

반환: RefineResult(segments, prompt_tokens, completion_tokens)
  실패 시: 원본 segments 그대로 반환

내부 타입:
  @dataclass RefineResult:
    segments: list[Segment]
    prompt_tokens: int
    completion_tokens: int
```

---

## 8. 6단계: 라벨링

### 8-1. StructureLabelService (`app/pipeline/labeling/structure_label_service.py`)

**LLM 호출 — gemini-2.5-flash-lite, temp=0.2, max_tokens=800, thinking=512**

#### `label_text_only(segments, masked_text, ai_call_fn) -> StructureLabelResult`

```
입력:
  segments: list[Segment]
  masked_text: str
  ai_call_fn

처리:
  1. _build_user_message() → 메타데이터 + 세그먼트 목록 + 마스킹 원문
  2. ai_call_fn(PRIMARY_MODEL, SYSTEM_PROMPT, user_message, 0.2, 800, None, thinking_budget=512)
  3. _parse_output(result.content, masked_text, segments) → labeled: list[LabeledSegment]
     파싱: "SEG_ID|LABEL" 형식, 줄당 하나
     _resolve_label(): 직접 → 마이그레이션 맵 → COURTESY 폴백
  4. _validate_result(): coverage ≥ 60%, CORE_FACT/CORE_INTENT/REQUEST 최소 1개

  검증 실패 시:
    5a. 누락 세그먼트 경고 포함한 메시지로 리트라이
    5b. 리트라이도 실패 → 전체 COURTESY 폴백

  all-GREEN 복구 (4개 이상 세그먼트가 전부 GREEN):
    6a. YellowTriggerScanner.scan_yellow_triggers() → 정규식 기반 업그레이드 시도
    6b. 스캐너 결과 없으면 → FALLBACK_MODEL("gpt-4o-mini")로 재시도
    6c. 폴백 모델도 all-GREEN → 원본 결과 수용

  최종: _fill_missing_labels() → 누락 세그먼트에 COURTESY 기본값

반환: StructureLabelResult

내부 타입:
  @dataclass(frozen=True) StructureLabelResult:
    labeled_segments: list[LabeledSegment]
    summary_text: str | None
    prompt_tokens: int
    completion_tokens: int
    yellow_recovery_applied: bool = False
    yellow_upgrade_count: int = 0
```

### 8-2. YellowTriggerScanner (`app/pipeline/labeling/yellow_trigger_scanner.py`)

**서버사이드 정규식, LLM 없음**

#### `scan_yellow_triggers(segments, labeled_segments) -> list[YellowUpgrade]`

```
입력:
  segments: list[Segment]
  labeled_segments: list[LabeledSegment]

처리:
  GREEN 세그먼트 각각에 대해 4가지 카테고리 점수 계산:
  1. 비난+일반화: "매번/맨날/항상/도대체" + "상대/님/너희/귀사/담당"
     → 둘 다 있으면 strong(+2), generalizer만 있으면 soft(+1)
  2. 감정 표현: "답답/화가/짜증/열받/미치겠/환장" → strong(+2), "정말/너무" → soft(+1)
  3. 추측: "틀림없이/확실히" → strong(+2), "아마/것 같다/분명" → soft(+1)
  4. 방어: "내 탓 하려/말해 두는데" → strong(+2), "난 ~했고/최선을 다했/제 잘못도 있지만" → soft(+1)

  total_score ≥ SCORE_THRESHOLD(2) → 후보
  점수 높은 순 정렬 → MAX_UPGRADES(2)개까지 반환

반환: list[YellowUpgrade]

내부 타입:
  @dataclass(frozen=True) YellowUpgrade:
    segment_id: str         # 업그레이드 대상 세그먼트 ID
    new_label: SegmentLabel # 새 라벨 (ACCOUNTABILITY, EMOTIONAL, EXCESS_DETAIL, SELF_JUSTIFICATION)
    reason: str             # 사유 문자열
    score: int              # 총 점수
```

### 8-3. RedLabelEnforcer (`app/pipeline/labeling/red_label_enforcer.py`)

**서버사이드 정규식, LLM 없음**

#### `enforce(labeled: list[LabeledSegment]) -> list[LabeledSegment]`

```
입력: labeled (LLM 라벨링 결과)
처리:
  각 세그먼트에 대해 (이미 RED면 스킵):
  1. 확정 욕설 (ㅅㅂ/시발/병신/개새끼...) 또는 확정 비꼼 → AGGRESSION (RED)
  2. 능력 부정 (그것도 못/뇌가 있/무능...) → PERSONAL_ATTACK (RED)
  3. 모호 비속어 (미친/개같/ㅈㄴ) + 현재 GREEN → EMOTIONAL (YELLOW 업그레이드)

  텍스트 정규화: 공백/특수문자 제거 후 매칭 (우회 방지)

반환: list[LabeledSegment] (일부 라벨이 변경된 새 리스트)
```

---

## 9. 7단계: 템플릿 선택

### 9-1. StructureTemplate (`app/pipeline/template/structure_template.py`)

```python
class StructureSection(str, Enum):
    S0_GREETING       # 인사
    S1_ACKNOWLEDGE    # 공감/유감
    S2_OUR_EFFORT     # 내부 확인/점검
    S3_FACTS          # 핵심 사실
    S4_RESPONSIBILITY # 책임 프레이밍
    S5_REQUEST        # 요청/행동 요청
    S6_OPTIONS        # 대안/다음 단계
    S7_POLICY         # 정책/한계/불가
    S8_CLOSING        # 마무리

    # 각 섹션의 속성:
    # @property label -> str           (예: "인사")
    # @property instruction -> str     (예: "COURTESY 기반 인사. 보내는 사람 정보 있으면 포함")
    # @property expression_pool -> list[str]  (예: ["말씀해 주신 내용 확인했습니다", ...])
    # @property length_hint -> str     (예: "1~2문장")

@dataclass(frozen=True)
class SectionSkipRule:
    skip_sections: frozenset[StructureSection]      # 이 섹션은 제거
    shorten_sections: frozenset[StructureSection]    # 이 섹션은 짧게
    expand_sections: frozenset[StructureSection]     # 이 섹션은 확장

@dataclass(frozen=True)
class StructureTemplate:
    id: str                             # "T01_GENERAL"
    name: str                           # "일반 전달"
    section_order: list[StructureSection]  # [S0, S1, S3, S5, S6, S8]
    constraints: str                    # 템플릿 설명/제약
```

### 9-2. TemplateRegistry (`app/pipeline/template/template_registry.py`)

#### `TemplateRegistry.__init__()` → 12개 템플릿 등록

```
T01_GENERAL            일반 전달      [S0,S1,S3,S5,S6,S8]
T02_DATA_REQUEST       자료 요청      [S0,S1,S3,S5,S8]
T03_NAGGING_REMINDER   독촉/리마인더   [S0,S1,S3,S5,S8]
T04_SCHEDULE           일정 조율/지연   [S0,S1,S3,S4,S6,S8]
T05_APOLOGY            사과/수습      [S0,S1,S2,S3,S6,S8]
T06_REJECTION          거절/불가      [S0,S1,S7,S3,S6,S8]
T07_ANNOUNCEMENT       공지/안내      [S0,S3,S5,S8]
T08_FEEDBACK           피드백        [S0,S1,S3,S5,S6,S8]
T09_BLAME_SEPARATION   책임 분리      [S0,S1,S2,S3,S4,S6,S8]
T10_RELATIONSHIP_RECOVERY 관계 회복   [S0,S1,S3,S6,S8]
T11_REFUND_REJECTION   환불 거절      [S0,S1,S2,S3,S7,S6,S8]
T12_WARNING_PREVENTION 경고/재발 방지  [S0,S1,S3,S5,S6,S8]
```

#### `get(template_id: str) -> StructureTemplate`

- 없으면 T01_GENERAL 폴백

### 9-3. TemplateSelector (`app/pipeline/template/template_selector.py`)

#### `select_template(registry, topic, purpose, label_stats, masked_text) -> TemplateSelectionResult`

```
입력:
  registry: TemplateRegistry
  topic: Topic | None
  purpose: Purpose | None
  label_stats: LabelStats
  masked_text: str | None

처리 (우선순위 순):
  1. PURPOSE → 직접 매핑 (11종 PURPOSE → template ID)
  2. 기본값: T01_GENERAL
  3. Topic 오버라이드: REFUND_CANCEL + 거절성 → T11
  4. 키워드 오버라이드: "환불/취소/반품" + NEGATIVE_FEEDBACK → T11
  5. S2 강제 삽입: ACCOUNTABILITY 또는 NEGATIVE_FEEDBACK 존재 + S2 없으면 S1 뒤에 삽입

반환: TemplateSelectionResult(template, s2_enforced, effective_sections)

내부 타입:
  @dataclass(frozen=True) TemplateSelectionResult:
    template: StructureTemplate
    s2_enforced: bool
    effective_sections: list[StructureSection]
```

---

## 10. 8단계: 리댁션

**파일:** `app/pipeline/redaction/redaction_service.py`

#### `process(labeled_segments: list[LabeledSegment]) -> RedactionResult`

```
입력: labeled_segments (라벨링 + RED 강제 적용 후)
처리:
  각 세그먼트에 대해:
  - RED → redaction_map에 "[REDACTED:{LABEL}_{N}]" → 원본 텍스트 매핑 추가, red_count++
  - YELLOW → yellow_count++
  - GREEN → 무시

반환: RedactionResult(red_count, yellow_count, redaction_map)

내부 타입:
  @dataclass(frozen=True) RedactionResult:
    red_count: int
    yellow_count: int
    redaction_map: dict[str, str]   # "[REDACTED:AGGRESSION_1]" → "원본 욕설 텍스트"
```

---

## 11. 9단계: 최종 프롬프트 빌드

### 11-1. MultiModelPromptBuilder (`app/pipeline/multi_model_prompt_builder.py`)

#### `OrderedSegment` 데이터클래스

```python
@dataclass(frozen=True)
class OrderedSegment:
    id: str              # "T1", "T2"
    order: int           # 1부터 시작, start 위치 기준 정렬
    tier: str            # "GREEN", "YELLOW", "RED"
    label: str           # "CORE_FACT", "EMOTIONAL" 등
    text: str | None     # RED면 None
    dedupe_key: str | None  # RED면 None
    must_include: list[str]  # YELLOW 세그먼트의 {{TYPE_N}} 플레이스홀더 목록
```

#### `build_final_system_prompt(template, effective_sections, rag_results=None) -> str`

```
입력: 템플릿 정보 + RagResults (optional)
처리:
  1. FINAL_CORE_SYSTEM_PROMPT (~3000 토큰, 고정):
     역할 정의, JSON 입력 형식, 플레이스홀더 규칙, 출력 규칙, 금지 표현,
     자연스러움 규칙, 3계층(GREEN/YELLOW/RED) 처리 규칙, 중복 제거, 연결 권한, 핵심 원칙
  2. + 템플릿 섹션 블록:
     각 섹션의 label, instruction, length_hint, expression_pool
     T05면 사과 필수 경고 추가, S2면 필수 포함 경고 추가
  3. + RAG 시스템 블록 (선택): forbidden/expression_pool/cushion

반환: str (완성된 시스템 프롬프트)
```

#### `build_final_user_message(sender_info, ordered_segments, all_locked_spans, situation_analysis, summary_text, template, effective_sections, rag_results=None) -> str`

```
입력: 모든 분석 결과 + sender_info

처리:
  1. (선택) 상황 분석 결과:
     "--- 상황 분석 ---\n사실:\n- {content} (원문: \"{source}\")\n의도: {intent}"
  2. (선택) 요약: "[요약]: {summary_text}"
  3. JSON 블록:
     ```json
     {
       "meta": {
         "tone": "공손",
         "sender": "이름",
         "template": "T01_GENERAL",
         "sections": "S0_GREETING,S1_ACKNOWLEDGE,..."
       },
       "segments": [
         {"id":"T1","order":1,"tier":"GREEN","label":"CORE_FACT",
          "text":"...","dedupeKey":"...","mustInclude":[...]},
         {"id":"T2","order":2,"tier":"RED","label":"AGGRESSION",
          "text":null,"dedupeKey":null}
       ],
       "placeholders": {
         "{{DATE_1}}": "2025년 3월 15일",
         "{{EMAIL_1}}": "sender@example.com"
       }
     }
     ```

반환: str (JSON 래핑된 유저 메시지)
```

---

## 12. 10단계: LLM 호출 라우팅

### 12-1. AiCallRouter (`app/pipeline/ai_call_router.py`)

#### `call_llm(model, system_prompt, user_message, temp, max_tokens, analysis_context, *, thinking_budget=None) -> LlmCallResult`

```
입력:
  model: str                    # 모델 이름 (예: "gemini-2.5-flash", "gpt-4o-mini")
  system_prompt: str
  user_message: str
  temp: float                   # 온도 (-1이면 설정 기본값 사용)
  max_tokens: int               # 최대 출력 토큰
  analysis_context: str | None
  thinking_budget: int | None   # Gemini 전용 thinking 토큰 예산

처리:
  model이 "gemini-"로 시작 → call_gemini()
  그 외 → call_openai_with_model()

반환: LlmCallResult
```

이것이 `ai_call_fn` 시그니처의 기준이다. 파이프라인 전체에서 모든 LLM 호출은 이 시그니처를 따른다.

### 12-2. AiGeminiService (`app/pipeline/ai_gemini_service.py`)

#### `call_gemini(model, system_prompt, user_message, temp, max_tokens, analysis_context, *, thinking_budget=None) -> LlmCallResult`

```
입력: call_llm과 동일한 시그니처
처리:
  1. temp < 0 → settings.openai_temperature 사용
  2. max_tokens < 0 → settings.openai_max_tokens 사용
  3. thinking_budget 있으면 → ThinkingConfig(thinking_budget=max(512, budget))
  4. client.aio.models.generate_content(model, contents, config) 호출
  5. 토큰 사용량 로깅
  6. 응답 없으면 AiTransformError
반환: LlmCallResult(content.strip(), analysis_context, prompt_tokens, completion_tokens)
에러: _classify_gemini_error() → 한국어 에러 메시지 → AiTransformError
```

### 12-3. AiTransformService (`app/pipeline/ai_transform_service.py`)

#### `call_openai_with_model(model, system_prompt, user_message, temp, max_tokens, analysis_context) -> LlmCallResult`

```
입력: call_llm과 동일한 시그니처 (thinking_budget 없음)
처리:
  1. temp < 0 → settings.openai_temperature 사용
  2. AsyncOpenAI 싱글톤 클라이언트
  3. client.chat.completions.create(model, temperature, max_completion_tokens, messages) 호출
  4. 캐시 토큰 추적 (prompt_tokens_details.cached_tokens)
  5. 응답 없으면 AiTransformError
반환: LlmCallResult(content.strip(), analysis_context, prompt_tokens, completion_tokens)
에러: _classify_api_error() → 한국어 에러 메시지 → AiTransformError

내부 타입:
  class AiTransformError(Exception)   # 모든 LLM API 에러의 기본 예외
```

---

## 13. 11단계: 언마스킹 + 검증

### 13-1. 언마스킹

`execute_final()` 안에서 `locked_span_masker.unmask()` 호출 (5-3 참조).

### 13-2. OutputValidator (`app/pipeline/validation/output_validator.py`)

#### `validate(final_text, original_text, spans, raw_llm_output, redaction_map?, yellow_segment_texts?) -> ValidationResult`

```
입력:
  final_text: str               # 언마스킹된 최종 텍스트
  original_text: str            # 원본 입력 텍스트
  spans: list[LockedSpan] | None
  raw_llm_output: str | None    # 언마스킹 전 LLM 원본 출력
  redaction_map: dict[str, str] | None = None
  yellow_segment_texts: list[str] | None = None

처리 (12개 규칙):
  1. EMOJI (ERROR): 이모지 유니코드 범위 탐지
  2. FORBIDDEN_PHRASE (ERROR): "변환 결과", "다음과 같이" 등 AI 메타 발언 10종
  3. HALLUCINATED_FACT (WARNING): 원문에 없는 3자리+ 숫자 또는 한국어 수량 표현
  4. ENDING_REPETITION (WARNING): 동일 종결어미 3회 연속, "드리겠습니다" 3회 이상
  5. LENGTH_OVEREXPANSION (WARNING): 출력 > 6000자 또는 > 원문 x 3
  6. PERSPECTIVE_ERROR (WARNING): 화자 관점 오류 ("확인해 드리겠습니다" 등)
  7. LOCKED_SPAN_MISSING (ERROR): raw 출력에 플레이스홀더 없고, 원본 텍스트도 없음
  8. REDACTED_REENTRY (ERROR): RED 원본 텍스트(6자+)가 출력에 재등장
     + REDACTION_TRACE (ERROR): "[삭제됨]", "삭제된 내용" 등 검열 흔적
  9. CORE_NUMBER_MISSING (WARNING): CORE_FACT의 숫자가 출력에 없음
  10. CORE_DATE_MISSING (WARNING): CORE_FACT의 날짜가 출력에 없음
  11. SOFTEN_CONTENT_DROPPED (WARNING): YELLOW 세그먼트 의미 단어가 출력에 없음
  12. INFORMAL_CONJUNCTION (WARNING): "어쨌든/아무튼/걍/근데" 탐지

반환: ValidationResult(passed=ERROR 없으면 True, issues=[...])
```

#### `validate_with_template(...) -> ValidationResult`

```
위 validate() 결과 + 추가 규칙:
  13. SECTION_S2_MISSING (WARNING): S2가 effective_sections에 있고, ACCOUNTABILITY/NEGATIVE_FEEDBACK 존재하는데,
      출력에 "확인/점검/검토/살펴/조사/파악" 패턴 없음
```

#### `build_locked_span_retry_hint(issues, locked_spans) -> str`

```
입력: issues (LOCKED_SPAN_MISSING 이슈들), locked_spans
처리: 누락된 플레이스홀더 목록을 리트라이 힌트 문자열로 조립
반환: "\n\n[고정 표현 누락 오류] 다음 고정 표현이 출력에 반드시 포함되어야 합니다:\n- {{DATE_1}} → \"2025년 3월 15일\"\n..."
```

### 13-3. 리트라이 로직 (`execute_final()` 내부)

```
리트라이 대상 WARNING 타입 (_RETRYABLE_WARNINGS):
  - CORE_NUMBER_MISSING
  - CORE_DATE_MISSING
  - SOFTEN_CONTENT_DROPPED
  - SECTION_S2_MISSING
  - INFORMAL_CONJUNCTION

리트라이 조건: validation.passed == False (ERROR 존재) 또는 retryable WARNING 존재
리트라이 방법:
  - temp = 0.3 (원래 0.85에서 낮춤)
  - thinking_budget = min(1024, 원래 * 2)
  - system_prompt에 retry_hint 추가
  - user_message에 error_hint + locked_span_hint 추가
  - 1회만 리트라이
```

---

## 14. 12단계: 스트리밍

**파일:** `app/pipeline/ai_streaming_service.py`

#### `stream_transform(original_text, user_prompt, sender_info, identity_booster_toggle, topic, purpose, final_max_tokens) -> AsyncGenerator[dict, None]`

```
입력: API 엔드포인트에서 받은 전체 파라미터
처리:
  asyncio.Queue 기반 생산자-소비자 패턴:

  생산자 (run_pipeline, asyncio.create_task):
    1. execute_analysis(..., callback=StreamCallback())
       → 각 단계마다 SSE 이벤트 push (phase, spans, maskedText, segments, labels, ...)
    2. build_final_prompt(analysis, ...)
    3. _stream_final_model(...)
       → 토큰 단위로 delta 이벤트 push
    4. validate_with_template(...)
    5. 검증 실패 시:
       - push("retry", "validation_failed")
       - _stream_final_model(..., temp=변경 없음, thinking 2배)
       - 다시 validate
    6. push("validationIssues", [...])
    7. push("stats", {...})
    8. push("usage", {...})
    9. push("done", final_text)
    10. 에러 시: push("error", error_message)
    11. 마지막: queue.put(None)  — 종료 신호

  소비자 (async generator):
    while True:
      event = await queue.get()
      if event is None: break
      yield event

반환: dict 스트림 ({"event": "...", "data": "..."})
```

#### `_stream_final_model(model_name, system_prompt, user_message, locked_spans, max_tokens, push_event, *, thinking_budget) -> dict`

```
입력: 프롬프트 + 스팬 + 이벤트 발행 함수
처리:
  model이 "gemini-"면 → _stream_gemini_final_model()
  아니면 → OpenAI 스트리밍

  둘 다:
  1. 스트림 응답 생성
  2. 각 청크마다 push_event("delta", chunk_text)
  3. 전체 텍스트 조립 → unmask
  4. 토큰 사용량 수집

반환: {
  "unmasked_text": str,     # 언마스킹된 텍스트
  "raw_content": str,       # LLM 원본 출력
  "prompt_tokens": int,
  "completion_tokens": int,
}
```

---

## 부록: LLM 호출 요약

| # | 호출 | 모델 | 온도 | 토큰 | Thinking | 조건 |
|---|------|------|-----|------|----------|------|
| 1 | SituationAnalysis | gpt-4o-mini | 0.2 | 650 | - | 항상 |
| 2 | IdentityBooster | gemini-2.5-flash-lite | 0.2 | 300 | 없음 | 조건부 |
| 3 | LlmSegmentRefiner | gpt-4o-mini | 0.0 | 600 | - | 30자 초과 세그먼트 |
| 4 | StructureLabel (Primary) | gemini-2.5-flash-lite | 0.2 | 800 | 512 | 항상 |
| 4b | StructureLabel (Fallback) | gpt-4o-mini | 0.2 | 800 | - | all-GREEN 복구 |
| 5 | Final Transform | gemini-2.5-flash | 0.85 | 4000 | 512~1024 | 항상 |
| 5b | Final Transform (Retry) | gemini-2.5-flash | 0.3 | 4000 | min(1024, 2x) | 검증 실패 |

**기본 3호출 (SA + Label + Final), 최대 6호출 (+Booster +Refiner +Retry)**

---

## 부록: 코드 읽기 추천 순서

```
 1. app/models/enums.py                  ← Enum 타입 전체
 2. app/models/domain.py                 ← 도메인 데이터클래스
 3. app/api/v1/transform.py              ← API 진입점
 4. app/services/transform_app_service.py ← 서비스 레이어
 5. app/pipeline/multi_model_pipeline.py  ← ★ 핵심 오케스트레이터
 6. app/pipeline/preprocessing/           ← text_normalizer → locked_span_extractor → locked_span_masker
 7. app/pipeline/gating/                  ← gating_condition_evaluator → situation_analysis_service → identity_lock_booster
 8. app/pipeline/segmentation/            ← meaning_segmenter → llm_segment_refiner
 9. app/pipeline/labeling/                ← structure_label_service → yellow_trigger_scanner → red_label_enforcer
10. app/pipeline/template/                ← structure_template → template_registry → template_selector
11. app/pipeline/redaction/               ← redaction_service
12. app/pipeline/multi_model_prompt_builder.py ← 최종 프롬프트 조립
13. app/pipeline/ai_call_router.py        ← LLM 라우터
14. app/pipeline/ai_gemini_service.py     ← Gemini API 래퍼
15. app/pipeline/ai_transform_service.py  ← OpenAI API 래퍼
16. app/pipeline/validation/output_validator.py ← 14규칙 검증
17. app/pipeline/ai_streaming_service.py  ← SSE 스트리밍
18. app/pipeline/text_only_pipeline.py    ← Text-Only 파이프라인 (현재 유일한 활성 파이프라인)
```

---

## Text-Only Pipeline (`text_only_pipeline.py`)

현재 유일한 활성 파이프라인. 사용자 입력은 **원문만** (+ 선택적 sender_info, user_prompt).
SA intent로 구동, T01_GENERAL 템플릿 고정, POLITE 톤 고정.

### Flow

```
원문 → 전처리 → SA(facts+intent) ─┐
                                   ├→ 쿠션 전략 → Final Prompt → Final LLM → 검증 → 출력
       전처리 → 세그먼트 → 라벨링 ─┘
               (T01 템플릿 고정)
```

### 주요 특징

- SA: facts + intent only (metadata_check 없음)
- 라벨링: 원문 세그먼트만 (메타데이터 없음)
- 템플릿: T01_GENERAL 고정 (SA의 purpose/topic으로 오버라이드 가능)
- Final 시스템 프롬프트: SA intent 블록 + POLITE 톤
- Final 유저 메시지 meta: sender만 (있으면)
- 쿠션 전략: YELLOW 있을 때 per-segment 병렬 LLM (gemini-2.5-flash-lite)
- LLM 호출 수: 3~4 (SA + Label + [Cushion] + Final, Refiner 조건부)

### Functions

| Function | Input | Output | Description |
|----------|-------|--------|-------------|
| `execute()` | original_text, sender_info?, user_prompt?, ai_call_fn? | PipelineResult | 메인 오케스트레이터 (쿠션 포함) |
| `_apply_s2_enforcement()` | sections, label_stats | sections | ACCOUNTABILITY/NEGATIVE_FEEDBACK → S2 삽입 |
| `_build_ordered_segments()` | labeled_segments, locked_spans | OrderedSegment[] | 세그먼트 정렬 + dedupeKey/mustInclude |
| `_build_system_prompt()` | template, sections, sa_result | str | CORE + 템플릿 + SA intent블록 + POLITE톤 |
| `_build_system_prompt_with_cushion()` | template, sections, sa_result, cushion_strategy | str | 위 + 쿠션 전략 블록 (per-YELLOW 지침 + 적용 규칙) |
| `_format_cushion_block()` | cushion_strategy | str | 쿠션 전략 → 시스템 프롬프트 블록 (접근/쿠션/금지 + 종결어미 반복 방지) |
| `_build_user_message()` | ordered_segments, spans, sa_result, sender_info, template, sections | str | SA facts/intent + JSON wrapper |

### SA Text-Only (`situation_analysis_service.analyze_text_only`)

- facts + intent only (metadata_check 제거)
- 시스템 프롬프트: `SYSTEM_PROMPT_TEXT_ONLY` (`_SHARED_RULES` 공유 상수로 규칙 1~9 동일, 메타데이터 검증 섹션 제거, few-shot 예시 1개 포함)
  - 규칙 9: facts 간 논리적 모순 검증 (모순 발견 시 원문 맥락 재확인 후 수정)
- 유저 메시지: sender_info (있으면) + 원문만

### Labeling Text-Only (`structure_label_service.label_text_only`)

- 동일 시스템 프롬프트 (`SYSTEM_PROMPT`)
- 유저 메시지: 세그먼트 목록 + 마스킹된 원문
- all-GREEN recovery, coverage 검증, retry 로직 동일

### Endpoints

`POST /api/v1/transform` — 일반 변환
`POST /api/v1/transform/stream` — SSE 스트리밍 변환
- Request: `TransformTextOnlyRequest { originalText: str, senderInfo?: str, userPrompt?: str }`
- Response: `TransformResponse { transformedText: str }` (일반) / SSE 이벤트 스트림 (스트리밍)
