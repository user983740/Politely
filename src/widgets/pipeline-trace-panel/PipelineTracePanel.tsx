import { useState } from 'react';
import type {
  LockedSpanInfo,
  SegmentData,
  RelationIntentData,
  ValidationIssueData,
  PipelinePhase,
  LabelData,
  ProcessedSegmentsData,
  TemplateSelectedData,
} from '@/features/transform/stream-api';

interface Props {
  currentPhase: PipelinePhase | null;
  isTransforming: boolean;
  spans: LockedSpanInfo[] | null;
  segments: SegmentData[] | null;
  maskedText: string | null;
  labels: LabelData[] | null;
  relationIntent: RelationIntentData | null;
  processedSegments: ProcessedSegmentsData | null;
  validationIssues: ValidationIssueData[] | null;
  chosenTemplate: TemplateSelectedData | null;
  transformedText: string;
  transformError: string | null;
}

const SPAN_TYPE_LABELS: Record<string, string> = {
  EMAIL: '이메일',
  URL: 'URL',
  PHONE: '전화번호',
  ACCOUNT: '계좌번호',
  DATE: '날짜',
  TIME: '시간',
  TIME_HH_MM: '시간(HH:MM)',
  MONEY: '금액',
  UNIT_NUMBER: '단위숫자',
  LARGE_NUMBER: '큰 수',
  UUID: 'UUID',
  FILE_PATH: '파일경로',
  ISSUE_TICKET: '이슈티켓',
  VERSION: '버전',
  QUOTED_TEXT: '인용문',
  IDENTIFIER: '식별자',
  HASH_COMMIT: '해시/커밋',
  SEMANTIC: '고유명사(LLM)',
};

// Pipeline step definitions in execution order
interface StepDef {
  id: string;
  label: string;
  activeLabel: string;
  /** Phases that mean this step is currently running */
  runPhases: PipelinePhase[];
  /** Phases that mean this step was skipped */
  skipPhases?: PipelinePhase[];
  isLlm?: boolean;
}

const STEPS: StepDef[] = [
  {
    id: 'normalize',
    label: '텍스트 정규화',
    activeLabel: '정규화 중',
    runPhases: ['normalizing'],
  },
  {
    id: 'extract',
    label: '필수 표현 추출',
    activeLabel: '추출 중',
    runPhases: ['extracting'],
  },
  {
    id: 'identity',
    label: '고유명사 보호',
    activeLabel: '고유명사 분석 중',
    runPhases: ['identity_boosting'],
    skipPhases: ['identity_skipped'],
    isLlm: true,
  },
  {
    id: 'segment',
    label: '의미 분절',
    activeLabel: '분절 중',
    runPhases: ['segmenting'],
  },
  {
    id: 'segment_refine',
    label: '긴 세그먼트 정제',
    activeLabel: '세그먼트 정제 중',
    runPhases: ['segment_refining'],
    skipPhases: ['segment_refining_skipped'],
    isLlm: true,
  },
  {
    id: 'label',
    label: '3계층 구조 분석',
    activeLabel: '라벨링 중',
    runPhases: ['labeling'],
    isLlm: true,
  },
  {
    id: 'template_select',
    label: '구조 템플릿 선택',
    activeLabel: '템플릿 선택 중',
    runPhases: ['template_selecting'],
  },
  {
    id: 'context_gating',
    label: '컨텍스트 게이팅',
    activeLabel: '메타데이터 검증 중',
    runPhases: ['context_gating'],
    skipPhases: ['context_gating_skipped'],
    isLlm: true,
  },
  {
    id: 'relation',
    label: '관계/의도 분석',
    activeLabel: '분석 중',
    runPhases: ['relation_analyzing'],
    skipPhases: ['relation_skipped'],
    isLlm: true,
  },
  {
    id: 'redact',
    label: '서버 검열',
    activeLabel: '검열 적용 중',
    runPhases: ['redacting'],
  },
  {
    id: 'generate',
    label: '최종 변환',
    activeLabel: '생성 중',
    runPhases: ['generating'],
    isLlm: true,
  },
  {
    id: 'validate',
    label: '출력 검증',
    activeLabel: '검증 중',
    runPhases: ['validating'],
  },
];

type StepStatus = 'pending' | 'running' | 'completed' | 'skipped' | 'error';

function getStepStatuses(currentPhase: PipelinePhase | null, skippedSteps: Set<string>, hasError: boolean): Map<string, StepStatus> {
  const statuses = new Map<string, StepStatus>();

  if (!currentPhase) {
    STEPS.forEach((s) => statuses.set(s.id, 'pending'));
    return statuses;
  }

  let foundCurrent = false;

  for (const step of STEPS) {
    if (foundCurrent) {
      statuses.set(step.id, 'pending');
      continue;
    }

    if (step.runPhases.includes(currentPhase)) {
      // If there's an error, mark the currently running step as error instead of running
      statuses.set(step.id, hasError ? 'error' : 'running');
      foundCurrent = true;
    } else if (step.skipPhases?.includes(currentPhase)) {
      statuses.set(step.id, 'skipped');
      foundCurrent = true;
    } else if (currentPhase === 'complete') {
      statuses.set(step.id, skippedSteps.has(step.id) ? 'skipped' : 'completed');
    } else {
      statuses.set(step.id, skippedSteps.has(step.id) ? 'skipped' : 'completed');
    }
  }

  return statuses;
}

function HighlightLocked({ text }: { text: string }) {
  const parts = text.split(/({{[A-Z]+_\d+}})/g);
  return (
    <span>
      {parts.map((part, i) =>
        /^{{[A-Z]+_\d+}}$/.test(part) ? (
          <span key={i} className="px-1 py-0.5 mx-0.5 rounded bg-indigo-100 text-indigo-700 text-[11px] font-mono font-medium">
            {part}
          </span>
        ) : (
          <span key={i}>{part}</span>
        ),
      )}
    </span>
  );
}

function SpinnerIcon() {
  return (
    <svg className="animate-spin w-3.5 h-3.5 text-accent" viewBox="0 0 24 24" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
    </svg>
  );
}

function CheckIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className="text-emerald-500" aria-hidden="true">
      <polyline points="20 6 9 17 4 12" />
    </svg>
  );
}

function SkipIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-text-secondary/40" aria-hidden="true">
      <line x1="5" y1="12" x2="19" y2="12" />
    </svg>
  );
}

function ErrorIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className="text-red-500" aria-hidden="true">
      <line x1="18" y1="6" x2="6" y2="18" />
      <line x1="6" y1="6" x2="18" y2="18" />
    </svg>
  );
}

function StepIndicator({ status }: { status: StepStatus }) {
  if (status === 'running') return <SpinnerIcon />;
  if (status === 'completed') return <CheckIcon />;
  if (status === 'skipped') return <SkipIcon />;
  if (status === 'error') return <ErrorIcon />;
  return <div className="w-3.5 h-3.5 rounded-full border-2 border-border/60" />;
}

function StepContent({
  stepId,
  spans,
  segments,
  maskedText,
  labels,
  relationIntent,
  processedSegments,
  validationIssues,
  chosenTemplate,
}: {
  stepId: string;
  spans: LockedSpanInfo[] | null;
  segments: SegmentData[] | null;
  maskedText: string | null;
  labels: LabelData[] | null;
  relationIntent: RelationIntentData | null;
  processedSegments: ProcessedSegmentsData | null;
  validationIssues: ValidationIssueData[] | null;
  chosenTemplate: TemplateSelectedData | null;
}) {
  switch (stepId) {
    case 'extract':
      if (!spans || spans.length === 0) return null;
      return (
        <div className="space-y-1">
          {spans.map((span, i) => (
            <div key={i} className="flex items-center gap-1.5 text-[11px]">
              <span className="shrink-0 px-1 py-0.5 rounded bg-indigo-50 text-indigo-600 font-medium">
                {SPAN_TYPE_LABELS[span.type] ?? span.type}
              </span>
              <span className="text-text-secondary font-mono">{span.placeholder}</span>
              <span className="text-text truncate">{span.original}</span>
            </div>
          ))}
        </div>
      );

    case 'segment':
      if (!segments || segments.length === 0) return null;
      return (
        <div className="space-y-1">
          {segments.map((seg) => (
            <div key={seg.id} className="flex items-start gap-1.5 text-[11px]">
              <span className="shrink-0 px-1 py-0.5 rounded bg-slate-100 text-slate-500 font-mono font-medium">
                {seg.id}
              </span>
              <span className="text-text leading-relaxed line-clamp-1">{seg.text}</span>
            </div>
          ))}
        </div>
      );

    case 'normalize':
      if (!maskedText) return null;
      return (
        <div className="text-[11px] text-text leading-relaxed whitespace-pre-wrap bg-surface/40 rounded-lg p-2 max-h-20 overflow-hidden">
          <HighlightLocked text={maskedText} />
        </div>
      );

    case 'label':
      if (!labels) return null;
      {
        const green = labels.filter((l) => l.tier === 'GREEN').length;
        const yellow = labels.filter((l) => l.tier === 'YELLOW').length;
        const red = labels.filter((l) => l.tier === 'RED').length;
        return (
          <div className="space-y-1.5">
            <div className="flex items-center gap-2 text-[11px]">
              <span className="px-1.5 py-0.5 rounded bg-emerald-50 text-emerald-600 font-medium">GREEN {green}</span>
              <span className="px-1.5 py-0.5 rounded bg-amber-50 text-amber-600 font-medium">YELLOW {yellow}</span>
              <span className="px-1.5 py-0.5 rounded bg-red-50 text-red-600 font-medium">RED {red}</span>
            </div>
            {labels.map((l, i) => (
              <div key={i} className="flex items-start gap-1.5 text-[11px]">
                <span className={`shrink-0 px-1 py-0.5 rounded font-medium ${
                  l.tier === 'GREEN' ? 'bg-emerald-50 text-emerald-600'
                    : l.tier === 'YELLOW' ? 'bg-amber-50 text-amber-600'
                      : 'bg-red-50 text-red-600'
                }`}>
                  {l.label}
                </span>
                <span className="text-text leading-relaxed line-clamp-1">{l.text}</span>
              </div>
            ))}
          </div>
        );
      }

    case 'template_select':
      if (!chosenTemplate) return null;
      return (
        <div className="space-y-0.5 text-[11px]">
          <div className="flex gap-1.5">
            <span className="shrink-0 font-medium text-text-secondary">템플릿</span>
            <span className="text-text">{chosenTemplate.templateName}</span>
            <span className="text-text-secondary/50 font-mono">({chosenTemplate.templateId})</span>
          </div>
          {chosenTemplate.contextGatingFired && (
            <div className="flex items-center gap-1 text-violet-500">
              <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <polyline points="20 6 9 17 4 12" />
              </svg>
              <span>게이팅 LLM이 템플릿을 조정함</span>
            </div>
          )}
        </div>
      );

    case 'relation':
      if (!relationIntent) return null;
      return (
        <div className="space-y-0.5 text-[11px]">
          <div className="flex gap-1.5">
            <span className="shrink-0 font-medium text-text-secondary w-7">관계</span>
            <span className="text-text">{relationIntent.relation}</span>
          </div>
          <div className="flex gap-1.5">
            <span className="shrink-0 font-medium text-text-secondary w-7">의도</span>
            <span className="text-text">{relationIntent.intent}</span>
          </div>
          <div className="flex gap-1.5">
            <span className="shrink-0 font-medium text-text-secondary w-7">태도</span>
            <span className="text-text">{relationIntent.stance}</span>
          </div>
        </div>
      );

    case 'redact':
      if (!processedSegments || processedSegments.length === 0) return null;
      return (
        <div className="space-y-1">
          {processedSegments.map((seg) => (
            <div key={seg.id} className="flex items-start gap-1.5 text-[11px]">
              <span className={`shrink-0 px-1 py-0.5 rounded font-medium ${
                seg.tier === 'GREEN' ? 'bg-emerald-50 text-emerald-600'
                  : seg.tier === 'YELLOW' ? 'bg-amber-50 text-amber-600'
                    : 'bg-red-50 text-red-600'
              }`}>
                {seg.label}
              </span>
              <span className="text-text leading-relaxed line-clamp-1">
                {seg.text ?? '[삭제됨]'}
              </span>
            </div>
          ))}
        </div>
      );

    case 'validate':
      if (!validationIssues) return null;
      if (validationIssues.length === 0) {
        return (
          <p className="text-[11px] text-emerald-600 flex items-center gap-1">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <polyline points="20 6 9 17 4 12" />
            </svg>
            검증 통과
          </p>
        );
      }
      return (
        <div className="space-y-1">
          {validationIssues.map((issue, i) => (
            <div
              key={i}
              className={`flex items-center gap-1.5 text-[11px] ${
                issue.severity === 'ERROR' ? 'text-red-600' : 'text-amber-600'
              }`}
            >
              <span className={`shrink-0 px-1 py-0.5 rounded font-medium ${
                issue.severity === 'ERROR' ? 'bg-red-50' : 'bg-amber-50'
              }`}>
                {issue.severity}
              </span>
              <span>{issue.message}</span>
            </div>
          ))}
        </div>
      );

    default:
      return null;
  }
}

export default function PipelineTracePanel({
  currentPhase,
  isTransforming,
  spans,
  segments,
  maskedText,
  labels,
  relationIntent,
  processedSegments,
  validationIssues,
  chosenTemplate,
  transformedText,
  transformError,
}: Props) {
  const [expandedSteps, setExpandedSteps] = useState<Set<string>>(new Set());
  const [skippedSteps, setSkippedSteps] = useState<Set<string>>(new Set());
  const [prevPhase, setPrevPhase] = useState<PipelinePhase | null>(null);

  // Adjust state when currentPhase prop changes (React "state adjustment during render" pattern)
  if (currentPhase !== null && currentPhase !== prevPhase) {
    setPrevPhase(currentPhase);

    // Compute new skipped steps
    const newSkipped = currentPhase === 'normalizing' ? new Set<string>() : new Set(skippedSteps);
    for (const step of STEPS) {
      if (step.skipPhases?.includes(currentPhase)) {
        newSkipped.add(step.id);
      }
    }
    setSkippedSteps(newSkipped);

    // Auto-expand completed steps
    const statuses = getStepStatuses(currentPhase, newSkipped, false);
    const completedWithData = new Set<string>();
    statuses.forEach((status, stepId) => {
      if (status === 'completed') completedWithData.add(stepId);
    });
    setExpandedSteps(completedWithData);
  }

  // After complete, show all expandable
  const isComplete = currentPhase === 'complete' || (!isTransforming && transformedText.length > 0);

  // Detect error: not transforming, phase is not 'complete', and there's an error message
  const hasError = !isTransforming && currentPhase !== null && currentPhase !== 'complete' && !!transformError;

  const statuses = getStepStatuses(currentPhase, skippedSteps, hasError);

  // Determine if we should render at all
  const hasAnyPhase = currentPhase !== null;
  if (!hasAnyPhase && !isComplete) return null;

  const toggleStep = (stepId: string) => {
    setExpandedSteps((prev) => {
      const next = new Set(prev);
      if (next.has(stepId)) next.delete(stepId);
      else next.add(stepId);
      return next;
    });
  };

  const completedCount = Array.from(statuses.values()).filter(
    (s) => s === 'completed' || s === 'skipped',
  ).length;

  return (
    <div className="border border-border/60 rounded-xl overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 bg-surface border-b border-border/40">
        <div className="flex items-center gap-2.5">
          <h3 className="text-sm font-semibold text-text">파이프라인 트레이스</h3>
          {isTransforming && !isComplete && (
            <SpinnerIcon />
          )}
        </div>
        <span className="text-xs text-text-secondary tabular-nums">
          {completedCount} / {STEPS.length}
        </span>
      </div>

      {/* Progress bar */}
      <div className="h-0.5 bg-border/30">
        <div
          className="h-full bg-accent transition-all duration-500 ease-out"
          style={{ width: `${(completedCount / STEPS.length) * 100}%` }}
        />
      </div>

      {/* Steps */}
      <div className="py-1">
        {STEPS.map((step, idx) => {
          const status = statuses.get(step.id) ?? 'pending';
          const isExpanded = expandedSteps.has(step.id);
          const hasContent = status === 'completed' && (
            (step.id === 'extract' && spans && spans.length > 0) ||
            (step.id === 'segment' && segments && segments.length > 0) ||
            (step.id === 'normalize' && maskedText) ||
            (step.id === 'label' && labels) ||
            (step.id === 'template_select' && chosenTemplate) ||
            (step.id === 'relation' && relationIntent) ||
            (step.id === 'redact' && processedSegments && processedSegments.length > 0) ||
            (step.id === 'validate' && validationIssues !== null)
          );
          const isLast = idx === STEPS.length - 1;

          return (
            <div key={step.id} className="relative">
              {/* Vertical connector line */}
              {!isLast && (
                <div
                  className={`absolute left-[23px] top-[22px] w-px ${
                    status === 'error' ? 'bg-red-200'
                      : status === 'completed' || status === 'skipped' ? 'bg-emerald-200' : 'bg-border/40'
                  }`}
                  style={{ bottom: 0 }}
                />
              )}

              {/* Step header */}
              <button
                onClick={() => hasContent && toggleStep(step.id)}
                className={`relative z-10 w-full flex items-center gap-3 px-4 py-1.5 transition-colors ${
                  hasContent ? 'cursor-pointer hover:bg-surface/50' : 'cursor-default'
                }`}
              >
                <StepIndicator status={status} />
                <div className="flex-1 flex items-center gap-2 min-w-0">
                  <span className={`text-xs font-medium ${
                    status === 'running' ? 'text-accent'
                      : status === 'error' ? 'text-red-500'
                        : status === 'completed' ? 'text-text'
                          : status === 'skipped' ? 'text-text-secondary/40 line-through'
                            : 'text-text-secondary/60'
                  }`}>
                    {status === 'running' ? step.activeLabel : step.label}
                  </span>
                  {status === 'error' && (
                    <span className="text-[10px] px-1 py-0.5 rounded bg-red-50 text-red-500 font-medium">ERROR</span>
                  )}
                  {step.isLlm && status !== 'skipped' && (
                    <span className="text-[10px] px-1 py-0.5 rounded bg-violet-50 text-violet-500 font-medium">LLM</span>
                  )}
                  {status === 'skipped' && (
                    <span className="text-[10px] text-text-secondary/40">SKIP</span>
                  )}
                  {/* Badges for completed steps */}
                  {status === 'completed' && step.id === 'extract' && spans && spans.length > 0 && (
                    <span className="text-[10px] text-indigo-500 tabular-nums">{spans.length}개</span>
                  )}
                  {status === 'completed' && step.id === 'segment' && segments && (
                    <span className="text-[10px] text-slate-500 tabular-nums">{segments.length}개</span>
                  )}
                  {status === 'completed' && step.id === 'label' && labels && (
                    <span className="text-[10px] text-text-secondary tabular-nums">
                      G{labels.filter(l => l.tier === 'GREEN').length} Y{labels.filter(l => l.tier === 'YELLOW').length} R{labels.filter(l => l.tier === 'RED').length}
                    </span>
                  )}
                  {status === 'completed' && step.id === 'template_select' && chosenTemplate && (
                    <span className="text-[10px] text-text-secondary">{chosenTemplate.templateId}</span>
                  )}
                  {status === 'completed' && step.id === 'validate' && validationIssues && (
                    <span className={`text-[10px] ${validationIssues.length === 0 ? 'text-emerald-500' : 'text-amber-500'}`}>
                      {validationIssues.length === 0 ? 'PASS' : `${validationIssues.length} issues`}
                    </span>
                  )}
                </div>
                {hasContent && (
                  <svg
                    width="12"
                    height="12"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    className={`text-text-secondary/40 transition-transform shrink-0 ${isExpanded ? 'rotate-180' : ''}`}
                    aria-hidden="true"
                  >
                    <polyline points="6 9 12 15 18 9" />
                  </svg>
                )}
              </button>

              {/* Expanded content */}
              {isExpanded && hasContent && (
                <div className="relative z-10 ml-[35px] mr-4 mb-1.5 pl-2.5 border-l-2 border-accent/20">
                  <StepContent
                    stepId={step.id}
                    spans={spans}
                    segments={segments}
                    maskedText={maskedText}
                    labels={labels}
                    relationIntent={relationIntent}
                    processedSegments={processedSegments}
                    validationIssues={validationIssues}
                    chosenTemplate={chosenTemplate}
                  />
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
