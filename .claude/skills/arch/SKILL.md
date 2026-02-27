---
name: arch
description: Analyzes project architecture structure and dependencies.
---

Delegate to the `arch` agent. Use the Task tool with `subagent_type="general-purpose"` and pass the full agent prompt from `.claude/agents/arch.md`.

Replace `$ARGUMENTS` in the agent prompt with the user's arguments: $ARGUMENTS

Do NOT perform any analysis yourself — the agent handles everything in an isolated context.
