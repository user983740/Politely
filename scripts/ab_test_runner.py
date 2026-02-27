"""A/B cushion strategy test runner — 14 scenarios parallel execution.

Streams all 14 predefined scenarios against /api/v1/transform/stream-ab,
collects Variant A (baseline) vs Variant B (cushion-enhanced) results,
and writes a comparison report to test-logs/ab-compare/{timestamp}.md.

Usage:
    python3 scripts/ab_test_runner.py            # Run all 14 scenarios
    python3 scripts/ab_test_runner.py 1 3 5      # Run specific scenarios
"""

from __future__ import annotations

import asyncio
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

import httpx

BASE_URL = "http://localhost:8080"
SCENARIOS_PATH = Path(__file__).resolve().parent.parent / ".claude" / "skills" / "test-scenarios.md"
REPORT_DIR = Path(__file__).resolve().parent.parent / "test-logs" / "ab-compare"

HEALTH_TIMEOUT = 30  # seconds


# ── Data classes ────────────────────────────────────────────────────────


@dataclass
class ScenarioResult:
    index: int
    title: str
    original_text: str
    labels: list[dict] = field(default_factory=list)
    sa: dict | None = None
    text_a: str = ""
    text_b: str = ""
    cushion: dict | None = None
    val_a: list[dict] = field(default_factory=list)
    val_b: list[dict] = field(default_factory=list)
    stats: dict | None = None
    stats_a: dict | None = None
    stats_b: dict | None = None
    usage: dict | None = None
    error: str | None = None
    latency_ms: int = 0


# ── Scenario parsing ───────────────────────────────────────────────────


def parse_scenarios(path: Path) -> list[tuple[int, str, dict]]:
    """Parse test-scenarios.md and return list of (index, title, json_data)."""
    content = path.read_text(encoding="utf-8")

    # Extract ## S{N}: {TITLE} headings and their following ```json blocks
    pattern = r"## S(\d+):\s*(.+?)\n\n```json\n(.+?)\n```"
    matches = re.findall(pattern, content, re.DOTALL)

    scenarios = []
    for num, title, json_str in matches:
        data = json.loads(json_str.strip())
        scenarios.append((int(num), title.strip(), data))

    return scenarios


# ── Health check ───────────────────────────────────────────────────────


async def health_check(timeout: int = HEALTH_TIMEOUT) -> None:
    """Wait for backend to be ready."""
    url = f"{BASE_URL}/api/health"
    deadline = time.monotonic() + timeout
    last_err = None

    async with httpx.AsyncClient() as client:
        while time.monotonic() < deadline:
            try:
                resp = await client.get(url, timeout=5)
                if resp.status_code == 200:
                    print(f"  Backend ready ({url})")
                    return
            except Exception as e:
                last_err = e
            await asyncio.sleep(1)

    raise RuntimeError(f"Backend not ready after {timeout}s: {last_err}")


# ── SSE stream runner ──────────────────────────────────────────────────


async def run_scenario(index: int, title: str, data: dict) -> ScenarioResult:
    """Run a single A/B scenario and collect all SSE events."""
    result = ScenarioResult(
        index=index,
        title=title,
        original_text=data["originalText"],
    )
    start = time.monotonic()

    body = {"originalText": data["originalText"]}
    if data.get("senderInfo"):
        body["senderInfo"] = data["senderInfo"]
    if data.get("userPrompt"):
        body["userPrompt"] = data["userPrompt"]

    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(180.0)) as client:
            async with client.stream(
                "POST",
                f"{BASE_URL}/api/v1/transform/stream-ab",
                json=body,
            ) as resp:
                if resp.status_code != 200:
                    result.error = f"HTTP {resp.status_code}"
                    return result

                event_name = ""
                data_buf: list[str] = []

                async for line in resp.aiter_lines():
                    if line.startswith("event:"):
                        event_name = line[6:].strip()
                        data_buf = []
                    elif line.startswith("data:"):
                        data_buf.append(line[5:].strip())
                    elif line == "":
                        # End of SSE block
                        if event_name and data_buf:
                            raw = "\n".join(data_buf)
                            _collect_event(result, event_name, raw)
                        event_name = ""
                        data_buf = []

    except Exception as e:
        result.error = str(e)

    result.latency_ms = int((time.monotonic() - start) * 1000)
    return result


def _collect_event(result: ScenarioResult, event: str, raw: str) -> None:
    """Store SSE event data into ScenarioResult fields."""
    try:
        if event == "labels":
            result.labels = json.loads(raw)
        elif event == "situationAnalysis":
            result.sa = json.loads(raw)
        elif event == "done_a":
            result.text_a = raw
        elif event == "done_b":
            result.text_b = raw
        elif event == "cushionStrategy":
            result.cushion = json.loads(raw)
        elif event == "validation_a":
            result.val_a = json.loads(raw)
        elif event == "validation_b":
            result.val_b = json.loads(raw)
        elif event == "stats":
            result.stats = json.loads(raw)
        elif event == "stats_a":
            result.stats_a = json.loads(raw)
        elif event == "stats_b":
            result.stats_b = json.loads(raw)
        elif event == "usage":
            result.usage = json.loads(raw)
        elif event == "error":
            result.error = raw
    except json.JSONDecodeError:
        pass  # non-JSON events (delta, phase, etc.) — skip


# ── Report generation ──────────────────────────────────────────────────


def _git_short_hash() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=str(Path(__file__).resolve().parent.parent),
            text=True,
        ).strip()
    except Exception:
        return "unknown"


def _truncate(text: str, max_len: int = 100) -> str:
    if len(text) <= max_len:
        return text
    return text[:max_len] + "..."


def _validation_summary(issues: list[dict]) -> str:
    if not issues:
        return "PASS"
    errors = [i for i in issues if i.get("severity") == "ERROR"]
    warnings = [i for i in issues if i.get("severity") == "WARNING"]
    parts = []
    if errors:
        parts.append(f"ERROR({len(errors)})")
    if warnings:
        parts.append(f"WARN({len(warnings)})")
    return " / ".join(parts)


def _validation_detail(issues: list[dict]) -> str:
    if not issues:
        return "PASS"
    lines = []
    for i in issues:
        sev = i.get("severity", "?")
        typ = i.get("type", "?")
        msg = i.get("message", "")
        lines.append(f"[{sev}] {typ}: {msg}")
    return "<br>".join(lines)


def _count_tier(labels: list[dict], tier: str) -> int:
    return sum(1 for l in labels if l.get("tier") == tier)


def write_report(results: list[ScenarioResult], timestamp: str) -> Path:
    """Generate markdown report and return file path."""
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    filepath = REPORT_DIR / f"{timestamp}.md"

    lines: list[str] = []
    w = lines.append

    git_hash = _git_short_hash()
    total = len(results)
    success = sum(1 for r in results if not r.error)

    w("# A/B 쿠션 전략 테스트 결과\n")
    w("## Metadata\n")
    w(f"- **실행**: {timestamp}")
    w(f"- **Git**: {git_hash}")
    w(f"- **시나리오**: {total}개 (성공 {success} / 실패 {total - success})")
    w("")

    # ── Per-scenario sections ──
    for r in sorted(results, key=lambda x: x.index):
        w("---")
        w(f"\n### S{r.index}: {r.title}\n")

        if r.error:
            w(f"**ERROR**: {r.error}\n")
            continue

        # Original text
        w(f"**원문** ({len(r.original_text)}자): {_truncate(r.original_text)}\n")

        # Labels
        green = _count_tier(r.labels, "GREEN")
        yellow = _count_tier(r.labels, "YELLOW")
        red = _count_tier(r.labels, "RED")
        w(f"**라벨링**: GREEN {green} / YELLOW {yellow} / RED {red}\n")

        w("| ID | Tier | Label | Text |")
        w("|-----|------|-------|------|")
        for l in r.labels:
            text = _truncate(l.get("text", ""), 30)
            w(f"| {l.get('segmentId','')} | {l.get('tier','')} | {l.get('label','')} | {text} |")
        w("")

        # SA
        if r.sa:
            w(f"**SA Intent**: {r.sa.get('intent', 'N/A')}\n")

        # Cushion strategy
        if r.cushion and r.cushion.get("strategies"):
            w("**쿠션 전략**:\n")
            w(f"- Overall Tone: {r.cushion.get('overallTone', 'N/A')}")
            if r.cushion.get("transitionNotes"):
                w(f"- Transition: {r.cushion['transitionNotes']}")
            w("")
            w("| Segment | Approach | Phrase |")
            w("|---------|----------|--------|")
            for s in r.cushion["strategies"]:
                seg = s.get("segmentId", "")
                approach = s.get("approach", "")
                phrase = s.get("phrase", "")
                w(f"| {seg} | {approach} | {phrase} |")
            w("")
        else:
            w("**쿠션 전략**: YELLOW 없음 (쿠션 미생성)\n")

        # A/B comparison
        w("**A/B 비교**:\n")
        w("| 항목 | A (기본) | B (쿠션 적용) |")
        w("|------|---------|-------------|")
        w(f"| 변환 결과 | {r.text_a} | {r.text_b} |")
        w(f"| 글자수 | {len(r.text_a)} | {len(r.text_b)} |")
        w(f"| 검증 | {_validation_detail(r.val_a)} | {_validation_detail(r.val_b)} |")
        w("")

    # ── Summary table ──
    w("---\n")
    w("## 종합 비교 테이블\n")
    w("| # | 시나리오 | YELLOW | 쿠션 생성 | A 검증 | B 검증 | A 길이 | B 길이 | Latency |")
    w("|---|---------|--------|----------|--------|--------|--------|--------|---------|")

    for r in sorted(results, key=lambda x: x.index):
        if r.error:
            w(f"| S{r.index} | {r.title} | - | - | ERROR | ERROR | - | - | {r.latency_ms}ms |")
            continue

        yellow = _count_tier(r.labels, "YELLOW")
        has_cushion = "O" if (r.cushion and r.cushion.get("strategies")) else "X"
        val_a_sum = _validation_summary(r.val_a)
        val_b_sum = _validation_summary(r.val_b)

        w(f"| S{r.index} | {r.title} | {yellow} | {has_cushion} | {val_a_sum} | {val_b_sum} | {len(r.text_a)} | {len(r.text_b)} | {r.latency_ms}ms |")
    w("")

    # ── Cost summary ──
    w("## 비용 요약\n")
    total_cost = 0.0
    total_prompt = 0
    total_completion = 0
    valid_count = 0

    for r in results:
        if r.usage:
            valid_count += 1
            total_cost += r.usage.get("totalCostUsd", 0)
            total_prompt += r.usage.get("analysisPromptTokens", 0) + r.usage.get("finalPromptTokens", 0)
            total_completion += r.usage.get("analysisCompletionTokens", 0) + r.usage.get("finalCompletionTokens", 0)

    avg_cost = total_cost / valid_count if valid_count > 0 else 0
    w(f"- **총 prompt 토큰**: {total_prompt:,}")
    w(f"- **총 completion 토큰**: {total_completion:,}")
    w(f"- **총 비용**: ${total_cost:.4f}")
    w(f"- **시나리오 평균**: ${avg_cost:.4f}")
    w(f"- **유효 시나리오**: {valid_count}/{total}")
    w("")

    filepath.write_text("\n".join(lines), encoding="utf-8")
    return filepath


# ── Main ───────────────────────────────────────────────────────────────


async def main() -> None:
    print("A/B 쿠션 전략 테스트 러너")
    print("=" * 50)

    # Parse scenarios
    scenarios = parse_scenarios(SCENARIOS_PATH)
    print(f"  시나리오 로드: {len(scenarios)}개")

    # Filter by CLI args
    if len(sys.argv) > 1:
        selected = {int(a) for a in sys.argv[1:]}
        scenarios = [(i, t, d) for i, t, d in scenarios if i in selected]
        print(f"  선택된 시나리오: {[i for i, _, _ in scenarios]}")

    if not scenarios:
        print("  실행할 시나리오가 없습니다.")
        return

    # Health check
    print("\n  헬스체크 중...")
    await health_check()

    # Run all in parallel
    print(f"\n  {len(scenarios)}개 시나리오 병렬 실행 시작...")
    start = time.monotonic()

    tasks = [run_scenario(i, t, d) for i, t, d in scenarios]
    results: list[ScenarioResult] = await asyncio.gather(*tasks)

    elapsed = time.monotonic() - start
    print(f"  완료! ({elapsed:.1f}s)")

    # Report status
    for r in sorted(results, key=lambda x: x.index):
        status = "ERROR" if r.error else "OK"
        cushion = "쿠션O" if (r.cushion and r.cushion.get("strategies")) else "쿠션X"
        print(f"    S{r.index:02d} [{status}] {r.title} — {cushion} / {r.latency_ms}ms")

    # Write report
    timestamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    filepath = write_report(results, timestamp)
    print(f"\n  리포트: {filepath}")


if __name__ == "__main__":
    asyncio.run(main())
