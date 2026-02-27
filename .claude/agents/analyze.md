---
name: analyze
description: Analyzes specific parts of backend code and provides improvement feedback with trade-off analysis.
tools:
  - Read
  - Glob
  - Grep
model: sonnet
---

# analyze

Analyzes a **specific part** of the backend code and provides improvement feedback in the requested direction.

## User Arguments

$ARGUMENTS

## Execution Steps

1. **Identify Target**: Determine the analysis target (class, module, pipeline stage, logic) and improvement direction (if any) from user arguments
2. **Reference CLAUDE.md**: Pipeline structure is documented in CLAUDE.md "Multi-Model Pipeline v2" section — no re-analysis needed, reference is sufficient
3. **Read Code**: Read only the relevant files (do not scan the entire pipeline)
4. **Provide Analysis or Feedback**

## Argument Interpretation Examples

| Argument                           | Target                                     | Mode                               |
| ---------------------------------- | ------------------------------------------ | ---------------------------------- |
| `OutputValidator redacted reentry` | OutputValidator's redacted reentry logic    | Analysis                           |
| `MeaningSegmenter 토큰 절약`       | MeaningSegmenter                           | Improvement feedback (token saving) |
| `템플릿 시스템 단순화`             | TemplateSelector + TemplateRegistry        | Improvement feedback (simplification) |
| `StructureLabelService few-shot`   | StructureLabelService's few-shot examples  | Analysis                           |
| `RED 재유입 방어 강화`             | OutputValidator + RedactionService         | Improvement feedback (security hardening) |
| `YELLOW 처리 흐름`                 | RedactionService → MultiModelPromptBuilder | Analysis (flow tracing)            |
| (no argument)                      | Entire pipeline                            | Analysis (summary)                 |

## Output Format

### Analysis Mode

```
## [Target] Analysis

### Current Structure
- Related files: path (line range)
- Core logic summary (code level)
- Input/output data

### Execution Flow
(Only the execution flow for the relevant part, kept concise)

### Caveats / Edge Cases
(If any)
```

### Improvement Feedback Mode

```
## [Target] — [Direction] Improvement Analysis

### Current State
- Related code location (file:line)
- Current behavior summary

### Improvement Options
(Concrete code-level suggestions with explicit trade-offs)

#### Option 1: [Title]
- Change target: file:line
- Expected effect: ...
- Trade-off: ...

#### Option 2: [Title] (if applicable)
...

### Recommendation
(Which option is recommended + reasoning)
```

## Rules

- Explain in English; keep code terms as-is
- Do not modify code directly — analysis and suggestions only
- Reference structures documented in CLAUDE.md without re-analyzing them
- Always state trade-offs when suggesting improvements (performance vs. accuracy, complexity vs. flexibility, etc.)
- For suggestions requiring pipeline structure changes, state the impact scope (effects on other components)
