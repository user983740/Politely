import { useState } from 'react';
import type { CushionStrategyData, ValidationIssueData } from '@/features/transform/stream-api';

interface Props {
  textA: string;
  textB: string;
  isTransforming: boolean;
  currentPhase: string | null;
  validationIssuesA: ValidationIssueData[] | null;
  validationIssuesB: ValidationIssueData[] | null;
  cushionStrategy: CushionStrategyData | null;
}

function CopyButton({ text, disabled }: { text: string; disabled: boolean }) {
  const [copied, setCopied] = useState(false);
  const handleCopy = async () => {
    await navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };
  return (
    <button
      onClick={handleCopy}
      disabled={disabled || !text}
      className="px-2.5 py-1 text-xs font-medium text-text-secondary border border-border/60 rounded-lg hover:text-text hover:border-border transition-colors cursor-pointer disabled:opacity-30 disabled:cursor-not-allowed"
    >
      {copied ? '복사됨!' : '복사'}
    </button>
  );
}

function ValidationBadge({ issues }: { issues: ValidationIssueData[] | null }) {
  if (!issues) return null;
  const errors = issues.filter((i) => i.severity === 'ERROR').length;
  const warnings = issues.filter((i) => i.severity === 'WARNING').length;
  if (errors === 0 && warnings === 0) {
    return <span className="text-[10px] text-emerald-600 font-medium">PASS</span>;
  }
  return (
    <span className="text-[10px] font-medium">
      {errors > 0 && <span className="text-red-500 mr-1">E{errors}</span>}
      {warnings > 0 && <span className="text-amber-500">W{warnings}</span>}
    </span>
  );
}

function VariantPanel({
  label,
  sublabel,
  text,
  isStreaming,
  isWaiting,
  validationIssues,
}: {
  label: string;
  sublabel: string;
  text: string;
  isStreaming: boolean;
  isWaiting: boolean;
  validationIssues: ValidationIssueData[] | null;
}) {
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <h4 className="text-sm font-semibold text-text">{label}</h4>
          <span className="text-[10px] text-text-secondary/60">{sublabel}</span>
          <ValidationBadge issues={validationIssues} />
        </div>
        <CopyButton text={text} disabled={isStreaming || isWaiting} />
      </div>
      <div className="rounded-xl bg-surface/50 border border-border p-4">
        {isWaiting ? (
          <div className="flex items-center gap-2 text-sm text-text-secondary">
            <svg className="animate-spin h-3.5 w-3.5" viewBox="0 0 24 24" aria-hidden="true">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
            대기 중...
          </div>
        ) : (
          <p className="text-sm text-text leading-relaxed whitespace-pre-wrap">
            {text}
            {isStreaming && (
              <span className="inline-block w-0.5 h-4 bg-accent animate-pulse ml-0.5 align-text-bottom" />
            )}
            {!text && isStreaming && (
              <span className="text-text-secondary animate-pulse">생성 중...</span>
            )}
          </p>
        )}
      </div>
    </div>
  );
}

export default function ABResultPanel({
  textA,
  textB,
  isTransforming,
  currentPhase,
  validationIssuesA,
  validationIssuesB,
  cushionStrategy,
}: Props) {
  const [showStrategy, setShowStrategy] = useState(false);

  const isStreamingA = isTransforming && (currentPhase === 'generating_a' || currentPhase === 'generating');
  const isStreamingB = isTransforming && currentPhase === 'generating_b';
  const isWaitingB = isTransforming && !textB && currentPhase !== 'generating_b' && currentPhase !== 'complete';

  return (
    <div className="flex flex-col gap-4">
      <h3 className="text-sm font-semibold text-text">A/B 비교 결과</h3>

      {/* Side-by-side on desktop, stacked on mobile */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <VariantPanel
          label="A"
          sublabel="기본 변환"
          text={textA}
          isStreaming={isStreamingA}
          isWaiting={false}
          validationIssues={validationIssuesA}
        />
        <VariantPanel
          label="B"
          sublabel="쿠션 전략 적용"
          text={textB}
          isStreaming={isStreamingB}
          isWaiting={isWaitingB}
          validationIssues={validationIssuesB}
        />
      </div>

      {/* Cushion Strategy Detail (collapsible) */}
      {cushionStrategy && cushionStrategy.strategies.length > 0 && (
        <div className="border border-border/60 rounded-xl overflow-hidden">
          <button
            onClick={() => setShowStrategy(!showStrategy)}
            className="w-full flex items-center justify-between px-4 py-2.5 bg-surface/50 hover:bg-surface transition-colors cursor-pointer"
          >
            <span className="text-xs font-semibold text-text-secondary">
              쿠션 전략 상세
            </span>
            <svg
              width="12"
              height="12"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              className={`text-text-secondary/40 transition-transform ${showStrategy ? 'rotate-180' : ''}`}
              aria-hidden="true"
            >
              <polyline points="6 9 12 15 18 9" />
            </svg>
          </button>
          {showStrategy && (
            <div className="px-4 py-3 space-y-3 text-[11px]">
              {cushionStrategy.overallTone && (
                <div>
                  <span className="font-medium text-text-secondary">전체 톤: </span>
                  <span className="text-text">{cushionStrategy.overallTone}</span>
                </div>
              )}
              {cushionStrategy.strategies.map((s, i) => (
                <div key={i} className="p-2 rounded-lg bg-surface/40 space-y-1">
                  <div className="flex items-center gap-1.5">
                    <span className="px-1 py-0.5 rounded bg-amber-50 text-amber-600 font-medium">
                      {s.segment_id}
                    </span>
                    <span className="text-text-secondary">{s.label}</span>
                  </div>
                  <div><span className="text-text-secondary">접근법: </span><span className="text-text">{s.approach}</span></div>
                  <div><span className="text-text-secondary">쿠션: </span><span className="text-violet-600 font-medium">"{s.cushion_phrase}"</span></div>
                  <div><span className="text-text-secondary">금지: </span><span className="text-red-500">{s.avoid}</span></div>
                </div>
              ))}
              {cushionStrategy.transitionNotes && (
                <div>
                  <span className="font-medium text-text-secondary">전환 힌트: </span>
                  <span className="text-text">{cushionStrategy.transitionNotes}</span>
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
