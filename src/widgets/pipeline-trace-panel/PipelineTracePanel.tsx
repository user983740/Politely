import { useState } from 'react';
import type { LockedSpanInfo, SegmentData, RelationIntentData, ValidationIssueData } from '@/features/transform/stream-api';

interface Props {
  spans: LockedSpanInfo[] | null;
  segments: SegmentData[] | null;
  maskedText: string | null;
  relationIntent: RelationIntentData | null;
  relationIntentFired: boolean;
  processedText: string | null;
  validationIssues: ValidationIssueData[] | null;
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

function ChevronIcon({ open }: { open: boolean }) {
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={`text-text-secondary/60 transition-transform shrink-0 ${open ? 'rotate-180' : ''}`}
      aria-hidden="true"
    >
      <polyline points="6 9 12 15 18 9" />
    </svg>
  );
}

function Section({ title, badge, defaultOpen, children }: {
  title: string;
  badge?: string;
  defaultOpen?: boolean;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen ?? false);

  return (
    <div className="border-t border-border/40 first:border-t-0">
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center justify-between px-4 py-2.5 cursor-pointer hover:bg-surface/50 transition-colors"
      >
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold text-text">{title}</span>
          {badge && (
            <span className="text-[10px] text-text-secondary/60 tabular-nums">{badge}</span>
          )}
        </div>
        <ChevronIcon open={open} />
      </button>
      {open && <div className="px-4 pb-3">{children}</div>}
    </div>
  );
}

function HighlightLocked({ text }: { text: string }) {
  const parts = text.split(/({{LOCKED_\d+}})/g);
  return (
    <span>
      {parts.map((part, i) =>
        /^{{LOCKED_\d+}}$/.test(part) ? (
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

function HighlightProcessed({ text }: { text: string }) {
  const parts = text.split(/(\[REDACTED:[^\]]+\]|\[SOFTEN:\s[^\]]*\])/g);
  return (
    <span>
      {parts.map((part, i) => {
        if (/^\[REDACTED:/.test(part)) {
          return (
            <span key={i} className="px-1 py-0.5 mx-0.5 rounded bg-red-100 text-red-700 text-[11px] font-medium">
              {part}
            </span>
          );
        }
        if (/^\[SOFTEN:/.test(part)) {
          return (
            <span key={i} className="px-1 py-0.5 mx-0.5 rounded bg-amber-100 text-amber-700 text-[11px] font-medium">
              {part}
            </span>
          );
        }
        return <span key={i}>{part}</span>;
      })}
    </span>
  );
}

export default function PipelineTracePanel({
  spans,
  segments,
  maskedText,
  relationIntent,
  relationIntentFired,
  processedText,
  validationIssues,
}: Props) {
  const [open, setOpen] = useState(false);

  const sectionCount = [spans, segments, maskedText, processedText, validationIssues].filter(Boolean).length
    + (relationIntentFired || relationIntent !== null ? 1 : 1); // relation section always shown

  if (!spans && !segments && !maskedText && !processedText) return null;

  return (
    <div className="border border-border/60 rounded-xl overflow-hidden">
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center justify-between px-4 py-3 bg-surface border-b border-border/60 cursor-pointer hover:bg-surface/80 transition-colors"
      >
        <div className="flex items-center gap-3">
          <h3 className="text-sm font-semibold text-text">파이프라인 트레이스</h3>
          <span className="text-xs text-text-secondary tabular-nums">{sectionCount}개 단계</span>
        </div>
        <svg
          width="16"
          height="16"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          className={`text-text-secondary transition-transform ${open ? 'rotate-180' : ''}`}
          aria-hidden="true"
        >
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>

      {open && (
        <div>
          {/* 1. Locked Spans */}
          {spans && spans.length > 0 && (
            <Section title="필수 표현 추출" badge={`${spans.length}개`}>
              <div className="space-y-1.5">
                {spans.map((span, i) => (
                  <div key={i} className="flex items-center gap-2 text-xs">
                    <span className="shrink-0 px-1.5 py-0.5 rounded bg-indigo-100 text-indigo-700 font-medium">
                      {SPAN_TYPE_LABELS[span.type] ?? span.type}
                    </span>
                    <span className="text-text-secondary font-mono text-[11px]">{span.placeholder}</span>
                    <span className="text-text">{span.original}</span>
                  </div>
                ))}
              </div>
            </Section>
          )}

          {/* 2. Segments */}
          {segments && segments.length > 0 && (
            <Section title="의미 분절" badge={`${segments.length}개`}>
              <div className="space-y-1.5">
                {segments.map((seg) => (
                  <div key={seg.id} className="flex items-start gap-2 text-xs">
                    <span className="shrink-0 px-1.5 py-0.5 rounded bg-slate-100 text-slate-600 font-medium font-mono">
                      {seg.id}
                    </span>
                    <span className="text-text leading-relaxed">{seg.text}</span>
                  </div>
                ))}
              </div>
            </Section>
          )}

          {/* 3. Masked Text */}
          {maskedText && (
            <Section title="마스킹 텍스트">
              <div className="text-xs text-text leading-relaxed whitespace-pre-wrap bg-surface/50 rounded-lg p-3">
                <HighlightLocked text={maskedText} />
              </div>
            </Section>
          )}

          {/* 4. Relation/Intent */}
          <Section title="관계/의도 분석">
            {relationIntent ? (
              <div className="space-y-1.5 text-xs">
                <div className="flex gap-2">
                  <span className="shrink-0 font-medium text-text-secondary w-10">관계</span>
                  <span className="text-text">{relationIntent.relation}</span>
                </div>
                <div className="flex gap-2">
                  <span className="shrink-0 font-medium text-text-secondary w-10">의도</span>
                  <span className="text-text">{relationIntent.intent}</span>
                </div>
                <div className="flex gap-2">
                  <span className="shrink-0 font-medium text-text-secondary w-10">태도</span>
                  <span className="text-text">{relationIntent.stance}</span>
                </div>
              </div>
            ) : (
              <p className="text-xs text-text-secondary/60">게이팅 조건 미충족 — 실행되지 않음</p>
            )}
          </Section>

          {/* 5. Processed Text */}
          {processedText && (
            <Section title="서버 검열 결과">
              <div className="text-xs text-text leading-relaxed whitespace-pre-wrap bg-surface/50 rounded-lg p-3">
                <HighlightProcessed text={processedText} />
              </div>
            </Section>
          )}

          {/* 6. Validation Issues */}
          <Section title="출력 검증">
            {validationIssues && validationIssues.length > 0 ? (
              <div className="space-y-1.5">
                {validationIssues.map((issue, i) => (
                  <div
                    key={i}
                    className={`flex items-start gap-2 px-3 py-2 rounded-lg text-xs ${
                      issue.severity === 'ERROR'
                        ? 'bg-red-50 text-red-700'
                        : 'bg-amber-50 text-amber-700'
                    }`}
                  >
                    <span className={`shrink-0 px-1.5 py-0.5 rounded font-medium ${
                      issue.severity === 'ERROR'
                        ? 'bg-red-100 text-red-700'
                        : 'bg-amber-100 text-amber-700'
                    }`}>
                      {issue.severity}
                    </span>
                    <div>
                      <span>{issue.message}</span>
                      {issue.matchedText && (
                        <span className="ml-1 font-mono opacity-70">"{issue.matchedText}"</span>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-xs text-emerald-600 flex items-center gap-1.5">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
                  <polyline points="22 4 12 14.01 9 11.01" />
                </svg>
                검증 통과 — 이슈 없음
              </p>
            )}
          </Section>
        </div>
      )}
    </div>
  );
}
