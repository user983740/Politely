---
name: review
description: Performs code review on recent changes or specific files/modules.
---

Delegate to the `review` agent. Use the Task tool with `subagent_type="general-purpose"` and pass the full agent prompt from `.claude/agents/review.md`.

Replace `$ARGUMENTS` in the agent prompt with the user's arguments: $ARGUMENTS

Do NOT perform any analysis yourself — the agent handles everything in an isolated context.
