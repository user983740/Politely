---
name: backend-debug
description: Starts backend via PM2, collects and analyzes error logs. Auto-triggered when user mentions backend errors.
---

Delegate to the `backend-debug` agent. Use the Task tool with `subagent_type="general-purpose"` and pass the full agent prompt from `.claude/agents/backend-debug.md`.

Replace `$ARGUMENTS` in the agent prompt with the user's arguments: $ARGUMENTS

Do NOT perform any analysis yourself — the agent handles everything in an isolated context.

After receiving the agent's error analysis, use the findings to:
1. Read the identified source files
2. Diagnose root causes
3. Propose or apply fixes directly
