---
name: test-transform
description: Pipeline transform quality automated test. Launches haiku sub-agents for data collection, then compares pipeline output against Opus reference transforms.
tools:
  - Bash
  - Read
  - Write
  - Glob
  - Grep
  - Task
model: opus
---

# test-transform: Pipeline Transform Quality Automated Test

2-step test: haiku agents extract raw pipeline data, then compare pipeline output against Opus reference transforms + common quality checklist.

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
  -d '{JSON_BODY}' > /tmp/politeai_test_{N}.sse 2>/dev/null
```

## Parse

Read `/tmp/politeai_test_{N}.sse`. SSE format: lines starting with `event:` followed by `data:` on the next line.

Extract ALL events:

- `segments` → segmentation result (JSON array: id, text, start, end)
- `labels` → labeling result (JSON array: segmentId, label, tier, text)
- `situationAnalysis` → situation analysis (JSON: facts array [{content, source}] + intent)
- `processedSegments` → processed segments (JSON array: id, tier, label, text - RED has text=null)
- `done` → final transformed text (string)
- `stats` → pipeline statistics (JSON)
- `validationIssues` → validation results (JSON array)
- `usage` → token/cost info (JSON)

## Return

Return ALL extracted data as structured tables/sections:

**Labels**: | ID | Tier | Label | Text (first 30 chars) |

**Situation Analysis**: Intent + Facts table (# | content | source)

**Final Transform**: full text from `done` event

**Stats**: segment count, GREEN/YELLOW/RED, locked spans, retry count, identity booster, elapsed time, token usage

**Validation Issues**: list issues or "CLEAN" if none

===HAIKU TEMPLATE END===

## Step 2: Analysis

After collecting all haiku agent results, analyze each scenario's transform quality. **모든 분석을 한국어로 작성. 코드 용어/라벨명은 원문 유지.**

### Per-Scenario Report Format

For each scenario, produce:

---

#### Scenario {N}: {TITLE}

**Original text**: (extract from test-scenarios.md)

**Labeling results**: (haiku Labels table as-is)

**Situation Analysis**: Intent + Facts table from haiku data

**Final Transform Result**: full transformed text

**Pipeline Statistics**: segment count, GREEN/YELLOW/RED, locked spans, retry, token usage, elapsed

**Validation Results**: CLEAN or issue table (Severity | Type | Description). Rating: PASS (0 issues) / WARN (warnings only) / FAIL (errors exist)

**Transform Result Feedback**:

Read two files:
- **Opus Reference**: `/home/sms02/PoliteAi/.claude/skills/test-transform/reference-transforms.md` — pre-generated ideal transform for this scenario
- **Common Checklist**: `/home/sms02/PoliteAi/.claude/skills/test-transform/verification-points.md` — 9 structural quality checks

Evaluate in two parts:

**Part A — Opus 레퍼런스 비교**: Pipeline result과 Opus reference를 나란히 놓고 비교. 레퍼런스 대비 파이프라인 결과가 **더 나은 점 / 부족한 점 / 다르지만 수용 가능한 점**을 구체적으로 서술. 레퍼런스를 기준으로 판단하되, 파이프라인이 레퍼런스보다 나은 부분은 인정.

**Part B — 공통 체크리스트**: 9개 항목 중 **FAIL인 것만** 기재. 전부 PASS면 "체크리스트 전항 PASS" 한 줄.

**Overall Assessment**:

1. **Core verdict**: production-ready / usable with edits / not usable
2. **Best aspect**: 1-2 effective improvements (original→transformed comparison)
3. **Weakest aspect**: 1-2 critical issues. Skip if none
4. **Recipient impression**: one sentence
5. **Original intent achievement**: intent fully conveyed? Overly diluted/distorted?

---

## Step 3: Overall Summary

**모든 분석을 한국어로 작성. 코드 용어/라벨명은 원문 유지.**

### 3-1. 전체 품질 평가

- **종합 판정**: production-ready or which input types have issues. Best vs most vulnerable scenario type
- **핵심 이슈 요약**: severity순. Scenario number + original→transformed comparison + real-world impact + estimated cause
- **반복 패턴**: same issue in 2+ scenarios → pattern. Specific per persona/context or structural?

### 3-2. 품질 개선 제안 (3-tier priority)

1. **System prompt** → StructureLabelService or MultiModelPromptBuilder prompt changes
2. **Server pre/post-processing** → OutputValidator/RedactionService/LockedSpanExtractor changes
3. **Pipeline structure** → model/temperature/architecture changes

Each suggestion: **target file** + **concrete change**.

### 3-3. 검증 이슈 분석

- **반복 이슈 패턴**: same validation issue type in 2+ scenarios
- **개선 제안**: 3-tier priority (prompt → server → structure). Target file + change.

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

Glob `test-logs/test-transform/*.md`. If found, Read most recent log's "Overall Analysis" section. Note resolved/recurring issues briefly in this run's analysis.

### 4-3. Write Log

Path: `/home/sms02/PoliteAi/test-logs/test-transform/{FILE_TS}.md`

```markdown
# test-transform Execution Log

## Metadata

- **Execution time**: {TIMESTAMP}
- **Git Commit**: {COMMIT} (branch: {BRANCH})
- **Scenarios run**: {numbers}

## Code Context

### Recent Backend Change History

(git log output as code block)

### Pipeline Structure Summary

(from stats event: segmentation, labeling model, RED enforcement, gating, template, final transform, validation+retry)

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
