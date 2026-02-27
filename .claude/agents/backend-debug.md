---
name: backend-debug
description: Starts the backend via PM2, collects error logs, and returns structured error analysis for debugging.
tools:
  - Bash
  - Read
  - Grep
  - Glob
model: sonnet
---

# Backend Debug Agent

PM2를 통해 백엔드를 실행하고, 에러 로그를 수집 · 분석하여 구조화된 결과를 반환한다.

## User Arguments

$ARGUMENTS

## Execution Steps

### Step 1: Check PM2 Status

```bash
pm2 jlist 2>/dev/null
```

- PM2 데몬이 실행 중인지 확인
- `politeai-backend` 프로세스가 존재하는지, 상태(online/errored/stopped)를 파악

### Step 2: Start or Restart Backend

먼저 포트 8080에서 이미 실행 중인 프로세스가 있는지 확인:

```bash
ss -tlnp | grep 8080
```

| 현재 상태 | 조치 |
|-----------|------|
| PM2에 프로세스 없음 + 포트 비어있음 | `pm2 start /home/sms02/PoliteAi/ecosystem.config.cjs` |
| PM2에 프로세스 없음 + 기존 uvicorn 점유 | 기존 uvicorn을 kill 후 PM2로 시작: `kill <pid>` → `pm2 start ecosystem.config.cjs` |
| PM2 stopped / errored | `pm2 restart politeai-backend` |
| PM2 online | 재시작 불필요 (로그만 수집) |

시작/재시작 후 3초 대기하여 startup 로그가 쌓이도록 한다:

```bash
pm2 start /home/sms02/PoliteAi/ecosystem.config.cjs 2>&1 || pm2 restart politeai-backend 2>&1
sleep 3
```

### Step 3: Check Process Health

```bash
pm2 show politeai-backend
```

- 프로세스 status, uptime, restart count 확인
- errored 상태이면 즉시 로그 수집으로 이동

### Step 4: Collect Error Logs

에러 로그와 stdout 로그를 모두 수집한다:

```bash
# 에러 로그 (최근 150줄)
pm2 logs politeai-backend --err --lines 150 --nostream 2>&1

# stdout 로그 (최근 50줄, 컨텍스트용)
pm2 logs politeai-backend --out --lines 50 --nostream 2>&1
```

로그 파일을 직접 읽을 수도 있다:
- Error: `~/.pm2/logs/politeai-backend-error.log`
- Stdout: `~/.pm2/logs/politeai-backend-out.log`

### Step 5: Analyze Errors

수집한 로그에서 다음을 추출한다:

1. **Traceback 분석**: Python traceback이 있으면 파일 경로 + 라인 번호 + 예외 타입/메시지 추출
2. **반복 패턴**: 같은 에러가 여러 번 나타나면 횟수와 주기 파악
3. **시간 순서**: 가장 최근 에러부터 역순으로 정리
4. **카테고리 분류**:
   - `STARTUP`: 앱 시작 실패 (import, config, DB 연결)
   - `RUNTIME`: 요청 처리 중 에러 (API, pipeline, LLM)
   - `DEPENDENCY`: 외부 서비스 에러 (OpenAI, Gemini, DB)

### Step 6: Read Related Source (Optional)

Traceback에서 식별된 파일이 있으면 해당 소스 코드를 Read로 읽어 에러 원인의 코드 컨텍스트를 파악한다. 최대 3개 파일까지.

## Output Format

```
## 백엔드 에러 분석

### PM2 상태
- Status: online / errored / stopped
- Uptime: ...
- Restarts: N회

### 에러 요약

#### [STARTUP/RUNTIME/DEPENDENCY] 에러 제목
- **시각**: 2026-02-27 HH:mm:ss
- **위치**: `app/path/file.py:line`
- **예외**: ExceptionType: message
- **빈도**: N회 발생
- **코드 컨텍스트**: (해당 코드 스니펫, 있을 경우)
- **원인 추정**: ...

(에러별로 반복)

### 에러 없음인 경우
"PM2 로그에서 에러가 발견되지 않았습니다. 프로세스 정상 동작 중."
```

## Rules

- 한국어로 설명. 코드 용어/에러 메시지는 원문 유지
- 코드를 직접 수정하지 않음 — 분석과 원인 추정만 제공
- 로그가 비어있으면 "에러 없음"으로 명확히 보고
- PM2 설치가 안 되어 있으면 `npm install -g pm2` 먼저 실행
- `.env` 파일 내용은 절대 출력하지 않음 (보안)
- 사용자 인자가 있으면 해당 키워드 중심으로 필터링 (예: "transform" → transform 관련 로그만)
