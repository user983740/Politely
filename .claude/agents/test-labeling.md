---
name: test-labeling
description: Segment segmentation + labeling quality automated test. Launches haiku sub-agents for data collection, then analyzes labeling quality against verification points.
tools:
  - Bash
  - Read
  - Write
  - Glob
  - Grep
  - Task
model: sonnet
---

# test-labeling: Segment Segmentation + Labeling Quality Automated Test

2-step test: haiku agents extract raw pipeline data, then analyze labeling quality using verification points.

## User Arguments

$ARGUMENTS

No argument = randomly select 3 scenarios. 'all' = all 14. Number (1~14) = specific scenario.

## Step 0: Backend Auto-Management

### 0-1. Check Port 8080

```bash
lsof -i :8080 2>/dev/null || echo "PORT_FREE"
```

### 0-2. Start/Restart

**Port in use**: Notify user ("Port 8080 in use, PID: N. Killing and restarting.") → `kill <PID>` → wait 2s → verify PORT_FREE. If still alive, `kill -9`.

**Port free**: Start backend (Bash `run_in_background`):

```bash
cd /home/sms02/PoliteAi && uvicorn app.main:app --reload --port 8080 2>&1
```

Health check (max 30s, 3s interval):

```bash
for i in $(seq 1 10); do
  curl -s --max-time 3 http://localhost:8080/api/health 2>/dev/null | grep -q 'ok' && echo "READY" && exit 0
  sleep 3
done
echo "TIMEOUT"; exit 1
```

READY → Step 1. TIMEOUT → abort.

## Step 1: Data Collection

### 1-1. Scenario Selection

Based on $ARGUMENTS:

- **Empty**: `python3 -c "import random; print(','.join(map(str, sorted(random.sample(range(1, 15), 3)))))"`
- **'all'**: 1~14
- **Number**: that scenario only

### 1-2. Load Scenario Data

Read `/home/sms02/PoliteAi/.claude/skills/test-scenarios.md` and extract JSON bodies for selected scenarios.

### 1-3. Launch Haiku Agents

Launch a **Task agent** (`subagent_type="general-purpose"`, `model="haiku"`) **in parallel** per scenario.

Each agent prompt uses the template below with `{N}`, `{TITLE}`, `{JSON_BODY}` substituted:

===HAIKU TEMPLATE START===

Run scenario {N} ({TITLE}) against the local backend and return raw SSE data. Do NOT analyze or evaluate — only extract and format data.

## Execute

```bash
curl -s -N --max-time 120 -X POST http://localhost:8080/api/v1/transform/stream \
  -H "Content-Type: application/json" \
  -d '{JSON_BODY}' > /tmp/politeai_label_{N}.sse 2>/dev/null
```

## Parse

Read `/tmp/politeai_label_{N}.sse`. SSE format: lines starting with `event:` followed by `data:` on the next line.

Extract these events:

- `segments` → segmentation result (JSON array)
- `labels` → labeling result (JSON array)
- `stats` → pipeline statistics (JSON)

## Return

Return as structured tables:

**Segments**:

| # | ID | Text | Start | End | Length |
|---|---|---|---|---|---|

**Labels**:

| ID | Tier | Label | Text (full) |
|---|---|---|---|

**Stats**: segment count, GREEN/YELLOW/RED counts, retry count, identity booster fired status

===HAIKU TEMPLATE END===

## Step 2: Analysis

After collecting all haiku agent results, analyze each scenario's labeling quality. **모든 분석을 한국어로 작성. 코드 용어/라벨명은 원문 유지.**

### Per-Scenario Report Format

For each scenario, produce:

---

#### Scenario {N}: {TITLE}

**Original text**: (extract from test-scenarios.md)

**Segmentation results**: (from haiku data)

- Total segments: N
- Segmentation notes: only mention over/under-segmentation issues

**Labeling results**: (haiku Labels table as-is)

- GREEN: N / YELLOW: N / RED: N

**Labeling appropriateness assessment**:

Compare actual labels vs **Verification Points** (read `/home/sms02/PoliteAi/.claude/skills/test-labeling/verification-points.md`) for this scenario.

⚠️ **핵심 원칙: 표현이 아닌 내용으로 판단한다.**

라벨링 평가는 **전체 맥락 속 내용의 적절성**만으로 판단. 표면적 표현 패턴("알아서", "~든지", "판단에 맡기겠음" 등)에 반응하지 말 것.

판단 기준:
- GREEN = 수신자에게 전달해야 할 핵심 내용 (거친 표현이어도 내용이 필수면 GREEN)
- YELLOW = 내용 자체가 재구성이 필요한 것 (변명, 비난, 감정 토로 등)
- RED = 내용 자체가 불필요하거나 해로운 것 (삭제 가능)

**"알아서" 류 표현 판단법**: 해당 사안이 수신자가 실제로 자율 결정할 수 있는 영역인가?
- YES → GREEN (예: "다른 알바 잡는 건 알아서 판단해도 됨" = 수신자 영역의 결정)
- NO → YELLOW (예: "처리는 알아서 해" = 발신자 책임을 떠넘기는 경우)

Evaluate from 5 perspectives (only mention items with issues):

1. **GREEN accuracy** — rough expression ≠ YELLOW. Under-labeled? 표현에 끌리지 말고 내용의 필수성 판단
2. **YELLOW accuracy** — sub-label correct? Intensity appropriate?
3. **RED accuracy** — necessary facts over-labeled? Enforcement rules applied?
4. **Boundary cases** — request-form directives, apology-form excuses, persona→recipient relationship
5. **RedLabelEnforcer** — pattern-based overrides correct?

Rating: PASS / WARN / FAIL. Point out specific problematic segments with reasoning.

---

## Step 3: Overall Summary

**모든 분석을 한국어로 작성. 코드 용어/라벨명은 원문 유지.**

### 3-1. 분절 품질 요약

동일 유형의 분절 이슈가 2+ 시나리오에서 반복되면 패턴으로 식별.

### 3-2. 라벨링 품질 요약

- **반복 오라벨링 패턴**: 동일 이슈 유형이 2+ 시나리오에서 반복 시 식별
- **FAIL 항목 상세**: segment ID + text, actual vs expected, reasoning, Final Transform 영향

### 3-3. 개선 제안 (3-tier priority)

1. **System prompt** → StructureLabelService prompt changes (target file + change)
2. **Server rule** → RedLabelEnforcer/MeaningSegmenter/LlmSegmentRefiner changes
3. **Pipeline structure** → model/temperature/architecture changes

Each suggestion: **target file** + **concrete change**.

## Step 4: Log Save

### 4-1. Context Collection

Run in **parallel** via Bash:

```bash
echo "COMMIT:$(git -C /home/sms02/PoliteAi rev-parse --short HEAD)" && echo "BRANCH:$(git -C /home/sms02/PoliteAi branch --show-current)" && echo "TIMESTAMP:$(date '+%Y-%m-%d %H:%M:%S')" && echo "FILE_TS:$(date '+%Y-%m-%d_%H-%M-%S')"
```

```bash
git -C /home/sms02/PoliteAi log --oneline -10 -- app/
```

### 4-2. Past Log Reference

Glob `test-logs/test-labeling/*.md`. If found, Read most recent log's "Overall Analysis" section. Note resolved/recurring issues briefly in this run's analysis.

### 4-3. Write Log

Path: `/home/sms02/PoliteAi/test-logs/test-labeling/{FILE_TS}.md`

```markdown
# test-labeling Execution Log

## Metadata

- **Execution time**: {TIMESTAMP}
- **Git Commit**: {COMMIT} (branch: {BRANCH})
- **Scenarios run**: {numbers}

## Code Context

### Recent Backend Change History

(git log output as code block)

### Pipeline Structure Summary

(from stats event data: segmentation, labeling model, RED enforcement, gating, final transform, validation)

### Recent Major Changes

(pipeline changes in git log, or "No recent pipeline-related changes")

## Per-Scenario Results

(Step 2 reports)

## Overall Analysis

(Step 3 summary)

## Changes vs Previous Log

(comparison or "First run — no comparison available")
```

### 4-4. Notify User

Log path, existing log count, key changes summary.
