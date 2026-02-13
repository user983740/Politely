import { useState } from 'react';
import type { UsageInfo } from '@/features/transform/stream-api';

interface Props {
  usageInfo: UsageInfo;
}

function formatCost(usd: number): string {
  if (usd < 0.001) return `$${usd.toFixed(6)}`;
  if (usd < 0.01) return `$${usd.toFixed(5)}`;
  if (usd < 1) return `$${usd.toFixed(4)}`;
  return `$${usd.toFixed(2)}`;
}

function formatMonthlyCost(usd: number): string {
  if (usd < 1) return `$${usd.toFixed(4)}`;
  return `$${usd.toFixed(2)}`;
}

export default function CostPanel({ usageInfo }: Props) {
  const [open, setOpen] = useState(false);

  const analysisCost =
    (usageInfo.analysisPromptTokens * 0.15 + usageInfo.analysisCompletionTokens * 0.60) / 1_000_000;
  const finalCost =
    (usageInfo.finalPromptTokens * 0.15 + usageInfo.finalCompletionTokens * 0.60) / 1_000_000;

  const analysisTotal = usageInfo.analysisPromptTokens + usageInfo.analysisCompletionTokens;
  const finalTotal = usageInfo.finalPromptTokens + usageInfo.finalCompletionTokens;
  const totalTokens = analysisTotal + finalTotal;

  return (
    <div className="border border-border/60 rounded-xl overflow-hidden">
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center justify-between px-4 py-3 bg-surface border-b border-border/60 cursor-pointer hover:bg-surface/80 transition-colors"
      >
        <div className="flex items-center gap-3">
          <h3 className="text-sm font-semibold text-text">비용 분석</h3>
          <span className="text-xs text-text-secondary tabular-nums">
            {formatCost(usageInfo.totalCostUsd)} · {totalTokens.toLocaleString()} tokens
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
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border/60 bg-surface/50">
                <th className="text-left px-4 py-2.5 text-xs font-medium text-text-secondary">항목</th>
                <th className="text-right px-4 py-2.5 text-xs font-medium text-text-secondary">분석 (구조+라벨)</th>
                <th className="text-right px-4 py-2.5 text-xs font-medium text-text-secondary">최종 변환</th>
                <th className="text-right px-4 py-2.5 text-xs font-medium text-text-secondary">합계</th>
              </tr>
            </thead>
            <tbody>
              {/* Cost row */}
              <tr className="border-b border-border/40">
                <td className="px-4 py-2.5 font-medium text-text">이번 요청</td>
                <td className="px-4 py-2.5 text-right text-text-secondary tabular-nums">
                  {formatCost(analysisCost)}
                </td>
                <td className="px-4 py-2.5 text-right text-text-secondary tabular-nums">
                  {formatCost(finalCost)}
                </td>
                <td className="px-4 py-2.5 text-right font-medium text-text tabular-nums">
                  {formatCost(usageInfo.totalCostUsd)}
                </td>
              </tr>

              {/* Token usage row */}
              <tr className="border-b border-border/40">
                <td className="px-4 py-2.5 font-medium text-text">토큰 사용</td>
                <td className="px-4 py-2.5 text-right text-text-secondary tabular-nums text-xs">
                  <span title="prompt">{usageInfo.analysisPromptTokens.toLocaleString()}</span>
                  {' + '}
                  <span title="completion">{usageInfo.analysisCompletionTokens.toLocaleString()}</span>
                </td>
                <td className="px-4 py-2.5 text-right text-text-secondary tabular-nums text-xs">
                  <span title="prompt">{usageInfo.finalPromptTokens.toLocaleString()}</span>
                  {' + '}
                  <span title="completion">{usageInfo.finalCompletionTokens.toLocaleString()}</span>
                </td>
                <td className="px-4 py-2.5 text-right text-text-secondary tabular-nums text-xs">
                  {totalTokens.toLocaleString()}
                </td>
              </tr>

              {/* Monthly projection */}
              <tr className="border-b border-border/60 bg-surface/30">
                <td colSpan={4} className="px-4 py-2 text-xs font-semibold text-text-secondary">
                  월간 예상 비용 (gpt-4o-mini 기준)
                </td>
              </tr>
              <tr>
                <td className="px-4 py-2.5 text-text-secondary text-xs pl-6">1,500건 / 6,000건 / 20,000건</td>
                <td className="px-4 py-2.5" colSpan={3}>
                  <div className="flex justify-end gap-4 text-xs tabular-nums text-text-secondary">
                    <span>{formatMonthlyCost(usageInfo.monthly.mvp)}</span>
                    <span>{formatMonthlyCost(usageInfo.monthly.growth)}</span>
                    <span className="font-medium text-text">{formatMonthlyCost(usageInfo.monthly.mature)}</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
