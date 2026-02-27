import { useState } from 'react';
import type { StatsData } from '@/features/transform/stream-api';

interface Props {
  stats: StatsData;
}

export default function QualityReportPanel({ stats }: Props) {
  const [open, setOpen] = useState(false);

  return (
    <div className="border border-border/60 rounded-xl overflow-hidden">
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center justify-between px-4 py-3 bg-surface border-b border-border/60 cursor-pointer hover:bg-surface/80 transition-colors"
      >
        <div className="flex items-center gap-3">
          <h3 className="text-sm font-semibold text-text">품질 리포트</h3>
          <span className="text-xs text-text-secondary tabular-nums">
            {stats.segmentCount}개 세그먼트 · {(stats.latencyMs / 1000).toFixed(1)}s
          </span>
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
        <div className="p-4">
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-3">
            <StatItem label="세그먼트" value={stats.segmentCount} />
            <StatItem label="보존 (GREEN)" value={stats.greenCount} color="text-emerald-600" />
            <StatItem label="수정 (YELLOW)" value={stats.yellowCount} color="text-amber-600" />
            <StatItem label="제거 (RED)" value={stats.redCount} color="text-red-600" />
            <StatItem label="고정 표현" value={stats.lockedSpanCount} />
            <StatItem label="재시도" value={stats.retryCount} />
            <GatingItem label="상황 분석" fired={stats.situationAnalysisFired} />
          </div>
        </div>
      )}
    </div>
  );
}

function StatItem({ label, value, color }: { label: string; value: number; color?: string }) {
  return (
    <div className="text-center p-2 rounded-lg bg-surface/50">
      <div className={`text-lg font-bold tabular-nums ${color ?? 'text-text'}`}>{value}</div>
      <div className="text-xs text-text-secondary">{label}</div>
    </div>
  );
}

function GatingItem({ label, fired }: { label: string; fired: boolean }) {
  return (
    <div className="text-center p-2 rounded-lg bg-surface/50">
      <div className={`text-sm font-semibold ${fired ? 'text-accent' : 'text-text-secondary/40'}`}>
        {fired ? 'ON' : 'OFF'}
      </div>
      <div className="text-xs text-text-secondary">{label}</div>
    </div>
  );
}
