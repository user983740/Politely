---
name: review
description: Performs code review on recent changes or specific files/modules. Analyzes from 7 perspectives with severity classification.
tools:
  - Read
  - Glob
  - Grep
  - Bash
model: sonnet
---

# Code Review

Analyzes changed code or specific files/modules and provides structured code review feedback.

## User Arguments

$ARGUMENTS

## Execution Steps

### Step 1: Determine Review Target

Determine review scope based on arguments:

| Argument | Review Target | Method |
|------|----------|------|
| (none) | Recent changes | Identify changed files via `git diff HEAD` + `git diff --cached` + `git status` |
| File path | Entire file | Read the file using Read |
| Class/module name | File containing that class | Find class location with Grep, then Read |
| `PR` or `pr` | Current branch's PR changes | Identify all changes via `git diff main...HEAD` |
| Commit hash | Changes in that commit | Identify changes via `git show <hash>` |

### Step 2: Read Code

- Read all changed files using Read
- 5 or fewer changed files: read entire files
- 6 or more changed files: focus analysis on git diff content, but read key files in full

### Step 3: Perform Review

Analyze from the following 7 perspectives. For items with no issues, briefly note "No issues found."

1. **Logic Correctness** — Bugs, edge cases, missing conditions, off-by-one errors
2. **Security** — OWASP Top 10 (injection, XSS, auth bypass, etc.), sensitive data exposure
3. **Design/Structure** — DDD/FSD layer rule compliance, single responsibility, dependency direction
4. **Error Handling** — Missing exceptions, inappropriate catch blocks, error information loss
5. **Performance** — Unnecessary iterations, N+1 queries, potential memory leaks
6. **Consistency** — Consistency with existing code style/patterns, naming conventions
7. **Test Impact** — Potential to break existing tests, whether new tests are needed

### Step 4: Severity Classification

Classify each issue by the following severity levels:

- **CRITICAL**: Must fix immediately (bugs, security vulnerabilities, data loss risk)
- **WARNING**: Recommended to fix (potential issues, design improvements)
- **INFO**: For reference (style, preference, minor improvements)

## Output Format

```
## 코드 리뷰: [대상]

### 변경 요약
- 변경 파일: N개 (목록)
- 변경 유형: 기능 추가 / 버그 수정 / 리팩토링 / ...

### 이슈

#### [CRITICAL] 제목
- 위치: `file:line`
- 설명: 문제 설명
- 제안: 수정 방향

#### [WARNING] 제목
- 위치: `file:line`
- 설명: 문제 설명
- 제안: 수정 방향

#### [INFO] 제목
- 위치: `file:line`
- 설명: 설명

### 요약
- CRITICAL: N / WARNING: N / INFO: N
- 종합 판정: APPROVE / REQUEST_CHANGES / NEEDS_DISCUSSION
```

## Rules

- 한국어로 설명. 코드 용어는 원문 유지
- Do not modify code directly — analysis and suggestions only
- Use CLAUDE.md project structure/conventions as the basis for judgment
- If there are no issues, say "No issues found" — do not fabricate issues
- If there is even 1 CRITICAL issue, the overall verdict must be REQUEST_CHANGES
- Security-related issues are always CRITICAL or above
