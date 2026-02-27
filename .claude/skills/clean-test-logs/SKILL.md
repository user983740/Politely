---
name: clean-test-logs
description: Clean test logs (delete by time/pipeline feature criteria). Shows current log status if no argument provided.
disable-model-invocation: true
---

# clean-test-logs: Test Log Cleanup

Cleans (deletes) past test logs in the `test-logs/` directory by time or pipeline feature criteria.

## User Arguments

$ARGUMENTS

Specify deletion criteria in natural language. Examples:

**Time-based**:

- "3일 전 로그 삭제"
- "2월 이전 로그 삭제"
- "2026-02-10 이전 삭제"
- "최근 3개 빼고 삭제"
- "오래된 거 정리" (→ interpreted as: keep latest 5, delete the rest)

**Pipeline feature-based**:

- "템플릿 시스템 도입 전 로그 삭제"
- "OutputValidator 12규칙 이전 삭제"
- "gpt-4o-mini 전환 전 삭제"
- "RelationIntent always-on 전 로그 삭제"
- "segmenter 250자 설정 이전 삭제"

**Mixed**:

- "2월 이전이면서 v1 파이프라인 로그"
- "템플릿 없던 시절 + 1주일 이전"

If no argument is provided, show current log status and exit.
Make sure not to delete all logs in any cases. At least one log per directory (test-labeling, test-transform) should be preserved.

## Execution Procedure

### Step 1: Collect Log List

Use Glob to collect `.md` files from these two patterns:

- `/home/sms02/PoliteAi/test-logs/test-labeling/*.md`
- `/home/sms02/PoliteAi/test-logs/test-transform/*.md`

Ignore `.gitkeep` files.

Extract timestamp from each filename (format: `YYYY-MM-DD_HH-MM-SS.md`).

**If 0 logs**, show the following and exit:

```
No test logs found.
- test-labeling: 0
- test-transform: 0
```

**If no argument**, show status and exit:

```
Test log status:
- test-labeling: N logs (latest: YYYY-MM-DD, oldest: YYYY-MM-DD)
- test-transform: M logs (latest: YYYY-MM-DD, oldest: YYYY-MM-DD)

To delete, specify criteria. e.g.: /clean-test-logs 3일 전
```

### Step 2: Determine Deletion Targets

Analyze user arguments to determine deletion criteria type and filter target files:

#### Case A: Time-based

Parse timestamps from filenames and filter by condition.

Get current time via Bash:

```bash
date '+%Y-%m-%d %H:%M:%S'
```

Rules:

- "N일 전" → files older than N days (24\*N hours) from now
- "N주 전" → files older than N\*7 days from now
- "N월 이전" / "M월 이전" → files before the 1st of that month at 00:00:00
- "YYYY-MM-DD 이전" → files before that date at 00:00:00
- "최근 N개 빼고" → **per directory** (test-labeling, test-transform), exclude the latest N, delete the rest
- "오래된 거 정리" → keep latest 5 per directory, delete the rest

#### Case B: Pipeline feature-based

Read the **header section** (metadata + code context) of every log file (offset=1, limit=80 is usually enough) and match against the user-specified feature.

Sections to check:

- "Pipeline structure summary" — segmentation/labeling/gating/validation config
- "Key settings" — openai.model, temperature, segmenter settings, etc.
- "Recent backend change history" — git commit messages
- "Recent major changes" — pipeline change summary

Matching logic:

- If the feature mentioned by user (e.g., "template system") is **absent or different** in that log's structure summary → deletion target
- If the setting mentioned by user (e.g., "OutputValidator 12 rules") **doesn't match** the log's settings → deletion target
- When ambiguous, additionally reference the file's Git commit hash and change history to determine the time point

Examples:

- "템플릿 시스템 도입 전" → logs without "template" content in structure summary
- "OutputValidator 12규칙 이전" → logs where validation rule count < 12
- "RelationIntent always-on 전" → logs where RelationIntent is shown as "optional" or "gating"
- "gpt-4o-mini 전환 전" → logs where model is not gpt-4o-mini

#### Case C: Mixed

Only files matching **both** time and feature conditions are deletion targets.

### Step 3: Confirm Deletion Targets

Show deletion target file list to user:

```
Deletion targets ({N} files):

test-labeling/ ({X} files):
  - 2026-02-10_14-30-00.md (commit: abc1234)
  - 2026-02-11_09-15-00.md (commit: def5678)

test-transform/ ({Y} files):
  - 2026-02-10_14-30-00.md (commit: abc1234)

Preserved ({M} files):
  - test-labeling/2026-02-16_10-00-00.md (latest)
  - test-transform/2026-02-16_10-00-00.md (latest)
```

If 0 deletion targets: "No logs match the specified criteria." and exit.

Use AskUserQuestion to confirm:

- Question: "Delete the above {N} logs?"
- Options: "Proceed with deletion" / "Cancel"

### Step 4: Execute Deletion

Only execute if user selects "Proceed with deletion".

Delete each file via Bash:

```bash
rm /home/sms02/PoliteAi/test-logs/test-labeling/{filename}
rm /home/sms02/PoliteAi/test-logs/test-transform/{filename}
```

Post-deletion message:

```
Deletion complete: {N} logs deleted
Remaining logs:
- test-labeling: {X}
- test-transform: {Y}
```
