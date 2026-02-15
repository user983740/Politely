import { useState } from 'react';
import type { LabelData } from '@/features/transform/stream-api';

interface Props {
  labels: LabelData[] | null;
}

const LABEL_DISPLAY: Record<string, string> = {
  // GREEN (5)
  CORE_FACT: '핵심사실',
  CORE_INTENT: '핵심의도',
  REQUEST: '요청',
  APOLOGY: '사과',
  COURTESY: '예의',
  // YELLOW (5)
  ACCOUNTABILITY: '책임소재',
  SELF_JUSTIFICATION: '자기변호',
  NEGATIVE_FEEDBACK: '부정적 평가',
  EMOTIONAL: '감정표현',
  EXCESS_DETAIL: '과잉설명',
  // RED (4)
  AGGRESSION: '공격/비꼬기',
  PERSONAL_ATTACK: '인신공격',
  PRIVATE_TMI: '사적정보',
  PURE_GRUMBLE: '순수넋두리',
};

const TIER_STYLES: Record<string, { bg: string; badge: string; text: string }> = {
  GREEN: { bg: 'bg-emerald-50', badge: 'bg-emerald-100 text-emerald-700', text: 'text-emerald-900' },
  YELLOW: { bg: 'bg-amber-50', badge: 'bg-amber-100 text-amber-700', text: 'text-amber-900' },
  RED: { bg: 'bg-red-50', badge: 'bg-red-100 text-red-700', text: 'text-red-900' },
};

export default function AnalysisPanel({ labels }: Props) {
  const [open, setOpen] = useState(false);

  if (!labels || labels.length === 0) return null;

  const greenCount = labels.filter((l) => l.tier === 'GREEN').length;
  const yellowCount = labels.filter((l) => l.tier === 'YELLOW').length;
  const redCount = labels.filter((l) => l.tier === 'RED').length;

  return (
    <div className="border border-border/60 rounded-xl overflow-hidden">
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center justify-between px-4 py-3 bg-surface border-b border-border/60 cursor-pointer hover:bg-surface/80 transition-colors"
      >
        <div className="flex items-center gap-3">
          <h3 className="text-sm font-semibold text-text">구조 분석</h3>
          <div className="flex items-center gap-2 text-xs">
            <span className="px-1.5 py-0.5 rounded bg-emerald-100 text-emerald-700 tabular-nums">
              보존 {greenCount}
            </span>
            {yellowCount > 0 && (
              <span className="px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 tabular-nums">
                수정 {yellowCount}
              </span>
            )}
            {redCount > 0 && (
              <span className="px-1.5 py-0.5 rounded bg-red-100 text-red-700 tabular-nums">
                제거 {redCount}
              </span>
            )}
          </div>
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
        <div className="p-4 space-y-2">
          {labels.map((label, i) => {
            const style = TIER_STYLES[label.tier];
            const displayName = LABEL_DISPLAY[label.label] ?? label.label;

            return (
              <div key={`${label.segmentId}-${i}`} className={`flex items-start gap-2 px-3 py-2 rounded-lg ${style.bg}`}>
                <span className={`shrink-0 px-1.5 py-0.5 text-xs font-medium rounded ${style.badge}`}>
                  {displayName}
                </span>
                <span className={`text-xs leading-relaxed ${label.tier === 'RED' ? 'line-through text-red-400' : style.text}`}>
                  {label.text}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
