#!/usr/bin/env python3
"""UserPromptSubmit hook - Analyzes user prompt and injects relevant context.

Reads JSON from stdin, outputs JSON with additionalContext on stdout.
"""
import json
import re
import sys


def main():
    raw = sys.stdin.read()
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        sys.exit(0)

    prompt = data.get("prompt", "")
    if len(prompt) < 3:
        sys.exit(0)

    p = prompt.lower()

    # ── Area detection ─────────────────────────────────────────────
    areas: list[str] = []
    techs: list[str] = []
    refs: list[str] = []

    if re.search(r"프론트|frontend|react|tsx?|컴포넌트|component|vite|tailwind|zustand|tanstack|src/|widget|route|store|페이지|ui|ux|반응형|responsive", p):
        areas.append("frontend")
        techs.append("React 19 / TypeScript 5.9 / Vite 7 / Tailwind v4 / Zustand 5")
        refs.append("src/CLAUDE.md (FSD 구조, 라우트, 위젯, 스토어)")

    if re.search(r"백엔드|backend|fastapi|python|파이썬|uvicorn|sqlalchemy|pydantic", p):
        areas.append("backend")
        techs.append("FastAPI / Python 3.12 / SQLAlchemy async / Pydantic")
        refs.append("app/CLAUDE.md (FastAPI 개요, 서비스, DB, 인증)")

    if re.search(r"파이프라인|pipeline|변환|transform|라벨|label|세그먼트|segment|프롬프트|prompt|llm|gpt|gemini|openai|임베딩|embedding|situation.?analysis|gating|template|cushion|identity.?booster|refiner", p):
        areas.append("pipeline")
        techs.append("Multi-model pipeline (SA→Label→Transform) / 14 labels / 12 templates")
        refs.append("app/pipeline/CLAUDE.md + docs/pipeline-deep-dive.md (파이프라인 상세)")

    if re.search(r"api|엔드포인트|endpoint|sse|dto|스키마|schema|요청|request|응답|response|rest|swagger|/api/", p):
        areas.append("api")
        techs.append("REST API / Pydantic DTOs / SSE streaming / tier system")
        refs.append("app/api/CLAUDE.md (REST 엔드포인트, DTO, SSE, 티어)")

    if re.search(r"인프라|infra|배포|deploy|docker|ec2|s3|cloudfront|ecr|rds|ci.?cd|github.?action|aws|워크플로우|workflow", p):
        areas.append("infra")
        techs.append("AWS EC2+Docker / S3+CloudFront / ECR(ap-southeast-2) / RDS PostgreSQL")

    if re.search(r"테스트|test|pytest|검증|verify|품질|quality", p):
        areas.append("testing")
        techs.append("pytest (68 tests) / /test-labeling / /test-transform skills")

    if re.search(r"db|데이터베이스|database|마이그레이션|migration|sqlite|postgres|sql|쿼리|query", p):
        areas.append("database")
        techs.append("SQLite(dev) → PostgreSQL(prod) / SQLAlchemy async / aiosqlite/asyncpg")

    if re.search(r"rag|retrieval|검색|seed|벡터|vector|expression.?pool|forbidden|policy|domain.?context", p):
        areas.append("rag")
        techs.append("RAG: ~275 seeds / 6 categories / text-embedding-3-small / RAG_ENABLED=false")
        refs.append("app/models/rag.py, app/services/rag_service.py, app/db/rag_repository.py")

    # ── Intent detection ───────────────────────────────────────────
    intents: list[str] = []

    if re.search(r"버그|bug|에러|error|오류|fix|수정|고치|안\s?되|안됨|왜|why|문제|issue|깨진|broken|실패|fail", p):
        intents.append("debugging")

    if re.search(r"추가|add|새로운|new|기능|feature|만들|create|구현|implement|개발|develop", p):
        intents.append("feature")

    if re.search(r"리팩터|refactor|개선|improve|최적화|optimize|정리|clean|성능|performance", p):
        intents.append("refactoring")

    if re.search(r"리뷰|review|검토|확인|check|분석|analyze|평가|evaluate", p):
        intents.append("review")

    if re.search(r"배포|deploy|push|릴리즈|release|운영|prod|라이브|live", p):
        intents.append("deployment")

    if re.search(r"설명|explain|어떻게|how|뭐|what|구조|structure|이해|understand|알려", p):
        intents.append("explanation")

    # ── Build context ──────────────────────────────────────────────
    if not areas and not intents:
        sys.exit(0)

    lines: list[str] = []
    if techs:
        lines.append(f"관련 기술: {', '.join(techs)}")
    if areas:
        lines.append(f"영역: {', '.join(areas)}")
    if intents:
        lines.append(f"의도: {', '.join(intents)}")
    if refs:
        for r in refs:
            lines.append(f"참조: {r}")

    context = "\n".join(lines)

    output = {
        "hookSpecificOutput": {
            "hookEventName": "UserPromptSubmit",
            "additionalContext": context,
        }
    }
    print(json.dumps(output, ensure_ascii=False))
    sys.exit(0)


if __name__ == "__main__":
    main()
