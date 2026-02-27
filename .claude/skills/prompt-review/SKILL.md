---
name: prompt-review
description: Analyzes pipeline LLM prompts and provides optimization suggestions.
---

Delegate to the `prompt-review` agent. Use the Task tool with `subagent_type="general-purpose"` and pass the full agent prompt from `.claude/agents/prompt-review.md`.

Replace `$ARGUMENTS` in the agent prompt with the user's arguments: $ARGUMENTS

Do NOT perform any analysis yourself — the agent handles everything in an isolated context.
