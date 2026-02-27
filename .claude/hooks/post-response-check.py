#!/usr/bin/env python3
"""Stop hook - Analyzes modified files and displays self-check reminders.

Runs after Claude finishes responding. Non-blocking, informational only.
Checks for dangerous patterns in modified files and prints soft reminders.
"""
import json
import os
import re
import subprocess
import sys


def get_modified_files(cwd: str) -> list[str]:
    """Get files with uncommitted changes (staged + unstaged + untracked)."""
    files: set[str] = set()
    try:
        # Staged + unstaged changes
        r = subprocess.run(
            ["git", "diff", "--name-only", "HEAD"],
            capture_output=True, text=True, cwd=cwd, timeout=5,
        )
        if r.stdout.strip():
            files.update(r.stdout.strip().splitlines())

        # Untracked new files
        r2 = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard"],
            capture_output=True, text=True, cwd=cwd, timeout=5,
        )
        if r2.stdout.strip():
            files.update(r2.stdout.strip().splitlines())
    except Exception:
        pass
    return sorted(files)


def get_diff_added_lines(filepath: str, cwd: str) -> str:
    """Get only the added lines ('+' lines) from git diff for a file."""
    try:
        r = subprocess.run(
            ["git", "diff", "HEAD", "--", filepath],
            capture_output=True, text=True, cwd=cwd, timeout=5,
        )
        # Extract added lines (lines starting with '+' but not '+++')
        added = []
        for line in r.stdout.splitlines():
            if line.startswith("+") and not line.startswith("+++"):
                added.append(line[1:])  # strip leading '+'
        return "\n".join(added)
    except Exception:
        return ""


# ── Pattern checkers ─────────────────────────────────────────────────

def check_python(filepath: str, added: str) -> list[str]:
    """Check Python file diff for concerning patterns."""
    warnings = []

    # Broad exception catching
    if re.search(r"except\s+(Exception|BaseException)\s*:", added):
        warnings.append(
            f"  ⚠  {filepath}: 광범위한 except (Exception/BaseException) — 구체적 예외 타입으로 좁힐 수 있는지 확인"
        )

    # Bare except
    if re.search(r"except\s*:", added):
        warnings.append(
            f"  ⚠  {filepath}: bare except — 예외 타입을 명시하는 것이 권장됩니다"
        )

    # DB session operations
    if re.search(
        r"(session\s*\.\s*(execute|add|delete|commit|flush|rollback)"
        r"|\.scalar\s*\(|\.scalars\s*\(|\.query\s*\()",
        added,
    ):
        warnings.append(
            f"  💾 {filepath}: DB 작업 — 트랜잭션 관리, 에러 핸들링, 리포지토리 패턴 사용 여부 확인"
        )

    # Raw SQL strings
    if re.search(
        r'(text\s*\(\s*["\']|\.execute\s*\(\s*["\']'
        r"(?:SELECT|INSERT|UPDATE|DELETE))",
        added,
        re.IGNORECASE,
    ):
        warnings.append(
            f"  🔒 {filepath}: Raw SQL 감지 — 파라미터 바인딩으로 SQL injection 방어 확인"
        )

    # Async anti-patterns: blocking calls inside async code
    has_async = re.search(r"async\s+def\s+\w+", added)
    if has_async:
        # time.sleep() instead of asyncio.sleep()
        if re.search(r"time\.sleep\s*\(", added):
            warnings.append(
                f"  ⚡ {filepath}: async 내 time.sleep() — asyncio.sleep() 사용 권장"
            )
        # synchronous requests library
        if re.search(r"requests\.(get|post|put|patch|delete)\s*\(", added):
            warnings.append(
                f"  ⚡ {filepath}: async 내 동기 requests 호출 — httpx.AsyncClient 사용 권장"
            )
        # synchronous open() for file I/O
        if re.search(r"(?<!aio)open\s*\(", added) and "aiofiles" not in added:
            warnings.append(
                f"  ⚡ {filepath}: async 내 동기 파일 I/O — aiofiles 사용 권장"
            )

    # Hardcoded secrets (skip config/test files)
    if not re.search(r"(config|test|conftest)", filepath, re.IGNORECASE):
        if re.search(
            r'(password|secret|api_key|token)\s*=\s*["\'][^"\']{8,}["\']',
            added,
            re.IGNORECASE,
        ):
            warnings.append(
                f"  🔐 {filepath}: 하드코딩된 시크릿 의심 — 환경변수 사용 권장"
            )

    # os.system / subprocess with shell=True
    if re.search(r"(os\.system\s*\(|subprocess\.\w+\(.*shell\s*=\s*True)", added):
        warnings.append(
            f"  🔒 {filepath}: shell 명령 실행 — command injection 위험 확인"
        )

    return warnings


def check_typescript(filepath: str, added: str) -> list[str]:
    """Check TypeScript/JavaScript file diff for concerning patterns."""
    warnings = []

    # Empty catch blocks
    if re.search(r"catch\s*\([^)]*\)\s*\{\s*\}", added):
        warnings.append(
            f"  ⚠  {filepath}: 빈 catch 블록 — 에러 로깅 또는 사용자 피드백 추가 확인"
        )

    # console.log left in
    if re.search(r"console\.(log|debug|info)\(", added):
        warnings.append(
            f"  🧹 {filepath}: console.log — 프로덕션 빌드 전 제거 필요 여부 확인"
        )

    # any type usage
    if re.search(r":\s*any\b", added):
        warnings.append(
            f"  📝 {filepath}: 'any' 타입 — 구체적 타입 지정 권장"
        )

    # dangerouslySetInnerHTML
    if "dangerouslySetInnerHTML" in added:
        warnings.append(
            f"  🔒 {filepath}: dangerouslySetInnerHTML — XSS 위험, 입력 새니타이징 확인"
        )

    # fetch without error handling (basic heuristic)
    if re.search(r"fetch\s*\(", added) and "catch" not in added:
        warnings.append(
            f"  ⚡ {filepath}: fetch 호출 — 네트워크 에러 핸들링 확인"
        )

    return warnings


# ── Main ─────────────────────────────────────────────────────────────

PYTHON_EXTS = {".py"}
TS_EXTS = {".ts", ".tsx", ".js", ".jsx"}
RELEVANT_EXTS = PYTHON_EXTS | TS_EXTS

# Directories to skip (hooks, tests, scripts, configs, docs)
SKIP_PREFIXES = (".claude/", "test-logs/", "docs/", "scripts/", "node_modules/")


def main():
    raw = sys.stdin.read()
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        sys.exit(0)

    cwd = data.get("cwd", os.getcwd())

    modified = get_modified_files(cwd)
    if not modified:
        sys.exit(0)

    # Filter to code files only, excluding non-app directories
    code_files = [
        f
        for f in modified
        if os.path.splitext(f)[1].lower() in RELEVANT_EXTS
        and not any(f.startswith(p) for p in SKIP_PREFIXES)
    ]
    if not code_files:
        sys.exit(0)

    all_warnings: list[str] = []

    for filepath in code_files:
        ext = os.path.splitext(filepath)[1].lower()
        added = get_diff_added_lines(filepath, cwd)

        # For untracked files, read full content as "added"
        if not added:
            full_path = os.path.join(cwd, filepath)
            if os.path.isfile(full_path):
                try:
                    with open(full_path, encoding="utf-8", errors="ignore") as f:
                        added = f.read()
                except Exception:
                    continue

        if not added:
            continue

        if ext in PYTHON_EXTS:
            all_warnings.extend(check_python(filepath, added))
        elif ext in TS_EXTS:
            all_warnings.extend(check_typescript(filepath, added))

    if not all_warnings:
        sys.exit(0)

    # Build display message
    lines = [
        "",
        "📋 자가 점검 알림 (수정된 파일 분석)",
        "─" * 44,
        *all_warnings,
        "─" * 44,
        f"  수정된 코드 파일: {len(code_files)}개 | 점검 항목: {len(all_warnings)}개",
        "",
    ]

    print("\n".join(lines), file=sys.stderr)
    sys.exit(0)


if __name__ == "__main__":
    main()
