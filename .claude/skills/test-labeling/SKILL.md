---
name: test-labeling
description: Segment segmentation + labeling quality automated test
---

Delegate to the `test-labeling` agent. Use the Task tool with `subagent_type="general-purpose"` and pass the full agent prompt from `.claude/agents/test-labeling.md`.

Replace `$ARGUMENTS` in the agent prompt with the user's arguments: $ARGUMENTS

Do NOT perform any analysis yourself — the agent handles everything in an isolated context.
