#!/usr/bin/env python3
"""Notification hook — rich WSL2 Windows toast notification.

Reads JSON from stdin for context, builds a detailed message including:
- Terminal ID (pts number from process tree)
- Tool details for permission_prompt
- Question text for elicitation_dialog

Debug: set NOTIFY_DEBUG=1 to log raw stdin to /tmp/notify-debug.json
"""
import json
import os
import subprocess
import sys


def get_pts_number() -> str:
    """Walk up process tree to find the controlling terminal pts number."""
    pid = os.getpid()
    for _ in range(10):
        try:
            link = os.readlink(f"/proc/{pid}/fd/0")
            if "/pts/" in link:
                return link.split("/pts/")[-1]
        except OSError:
            pass
        # Move to parent
        try:
            with open(f"/proc/{pid}/status") as f:
                for line in f:
                    if line.startswith("PPid:"):
                        pid = int(line.split()[1])
                        break
                else:
                    break
            if pid <= 1:
                break
        except (OSError, ValueError):
            break
    return ""


def get_active_terminal_index(pts: str) -> str:
    """Convert pts number to 1-based index among terminals running Claude Code."""
    if not pts:
        return ""
    try:
        result = subprocess.run(
            ["ps", "-eo", "tty,args", "--no-headers"],
            capture_output=True, text=True, timeout=3,
        )
        claude_pts = set()
        for line in result.stdout.strip().split("\n"):
            line = line.strip()
            if not line:
                continue
            tty = line.split()[0]
            if tty.startswith("pts/") and "claude" in line.lower():
                pts_num = tty.replace("pts/", "")
                if pts_num.isdigit():
                    claude_pts.add(int(pts_num))

        my_pts = int(pts)
        if my_pts in claude_pts:
            sorted_pts = sorted(claude_pts)
            return str(sorted_pts.index(my_pts) + 1)
    except Exception:
        pass
    return ""


def build_message(data: dict, fallback_msg: str) -> str:
    """Build rich notification message from hook stdin data."""
    pts = get_pts_number()
    idx = get_active_terminal_index(pts)
    tag = f"[터미널 {idx}] " if idx else ""

    ntype = data.get("type", "")

    # ── permission_prompt: show tool name + key detail ──────────
    if ntype == "permission_prompt":
        tool = data.get("tool_name", "")
        inp = data.get("tool_input", {})
        if isinstance(inp, str):
            try:
                inp = json.loads(inp)
            except (json.JSONDecodeError, ValueError):
                inp = {}

        if tool == "Bash":
            cmd = inp.get("command", "") if isinstance(inp, dict) else ""
            if len(cmd) > 70:
                cmd = cmd[:67] + "..."
            detail = f"Bash: {cmd}" if cmd else "Bash 실행"
        elif tool in ("Edit", "Write"):
            fp = inp.get("file_path", "") if isinstance(inp, dict) else ""
            detail = f"{tool}: {os.path.basename(fp)}" if fp else tool
        elif tool:
            detail = tool
        else:
            detail = "도구 사용"
        return f"{tag}권한 승인 필요: {detail}"

    # ── elicitation_dialog: show question text ──────────────────
    if ntype == "elicitation_dialog":
        msg = data.get("message", "")
        if not msg:
            qs = data.get("questions", [])
            if qs and isinstance(qs, list) and isinstance(qs[0], dict):
                msg = qs[0].get("question", "")
        if msg:
            if len(msg) > 50:
                msg = msg[:47] + "..."
            return f"{tag}질문 답변 대기: {msg}"
        return f"{tag}질문에 대한 답변을 기다리고 있습니다"

    # ── idle_prompt ─────────────────────────────────────────────
    if ntype == "idle_prompt":
        return f"{tag}입력을 기다리고 있습니다"

    # ── fallback (Stop hook, etc.) ──────────────────────────────
    return f"{tag}{fallback_msg}"


def send_toast(message: str):
    """Send Windows toast notification via PowerShell stdin."""
    # XML-escape for toast template
    safe = (
        message
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&apos;")
    )

    ps_script = (
        "[Windows.UI.Notifications.ToastNotificationManager, "
        "Windows.UI.Notifications, ContentType = WindowsRuntime] > $null\n"
        "[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, "
        "ContentType = WindowsRuntime] > $null\n"
        "$t = '<toast><visual><binding template=\"ToastText02\">"
        "<text id=\"1\">Claude Code</text>"
        f"<text id=\"2\">{safe}</text>"
        "</binding></visual></toast>'\n"
        "$x = New-Object Windows.Data.Xml.Dom.XmlDocument\n"
        "$x.LoadXml($t)\n"
        "$n = [Windows.UI.Notifications.ToastNotification]::new($x)\n"
        "[Windows.UI.Notifications.ToastNotificationManager]"
        "::CreateToastNotifier('Claude Code').Show($n)\n"
    )

    try:
        subprocess.run(
            ["powershell.exe", "-NoProfile", "-Command", "-"],
            input=ps_script, capture_output=True, text=True, timeout=8,
        )
    except Exception:
        pass


def main():
    fallback = sys.argv[1] if len(sys.argv) > 1 else "알림"

    raw = sys.stdin.read()

    # Debug mode: log raw stdin
    if os.environ.get("NOTIFY_DEBUG"):
        try:
            with open("/tmp/notify-debug.json", "a") as f:
                f.write(raw + "\n---\n")
        except Exception:
            pass

    try:
        data = json.loads(raw)
    except (json.JSONDecodeError, ValueError):
        data = {}

    send_toast(build_message(data, fallback))


if __name__ == "__main__":
    main()
