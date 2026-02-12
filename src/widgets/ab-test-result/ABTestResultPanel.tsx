import { useState } from 'react';
import type { ABTestResponse } from '@/features/transform/api';

interface Props {
  result: ABTestResponse;
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

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    await navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <button
      onClick={handleCopy}
      className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-text-secondary border border-border/60 rounded-lg hover:bg-surface transition-colors cursor-pointer"
    >
      {copied ? (
        <>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="20 6 9 17 4 12" />
          </svg>
          복사됨
        </>
      ) : (
        <>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
            <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
          </svg>
          복사
        </>
      )}
    </button>
  );
}

export default function ABTestResultPanel({ result }: Props) {
  const { testA, testB, sharedAnalysisDurationMs, totalDurationMs, costInfo } = result;

  return (
    <div className="space-y-6">
      {/* Header: shared analysis info */}
      <div className="flex items-center gap-3 flex-wrap text-sm">
        <span className="px-2.5 py-1 bg-accent/10 text-accent font-medium rounded-full text-xs">
          공유 분석: {(sharedAnalysisDurationMs / 1000).toFixed(1)}s
        </span>
        <span className="px-2.5 py-1 bg-surface text-text-secondary font-medium rounded-full text-xs">
          총 소요: {(totalDurationMs / 1000).toFixed(1)}s
        </span>
      </div>

      {/* Two-column: Test A vs Test B */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* Test A: gpt-4o */}
        <div className="border border-border/60 rounded-xl overflow-hidden">
          <div className="flex items-center justify-between px-4 py-3 bg-surface border-b border-border/60">
            <div className="flex items-center gap-2">
              <span className="px-2 py-0.5 bg-accent text-white text-xs font-bold rounded">A</span>
              <span className="text-sm font-semibold text-text">{testA.label}</span>
            </div>
            <span className="text-xs text-text-secondary">
              {(testA.finalModelDurationMs / 1000).toFixed(1)}s
            </span>
          </div>
          <div className="p-4">
            {testA.error ? (
              <p className="text-sm text-error">{testA.error}</p>
            ) : (
              <>
                <p className="text-sm text-text leading-relaxed whitespace-pre-wrap">
                  {testA.transformedText}
                </p>
                {testA.transformedText && (
                  <div className="mt-3 flex justify-end">
                    <CopyButton text={testA.transformedText} />
                  </div>
                )}
              </>
            )}
          </div>
        </div>

        {/* Test B: gpt-4o-mini */}
        <div className="border border-border/60 rounded-xl overflow-hidden">
          <div className="flex items-center justify-between px-4 py-3 bg-surface border-b border-border/60">
            <div className="flex items-center gap-2">
              <span className="px-2 py-0.5 bg-text-secondary text-white text-xs font-bold rounded">B</span>
              <span className="text-sm font-semibold text-text">{testB.label}</span>
            </div>
            <span className="text-xs text-text-secondary">
              {(testB.finalModelDurationMs / 1000).toFixed(1)}s
            </span>
          </div>
          <div className="p-4">
            {testB.error ? (
              <p className="text-sm text-error">{testB.error}</p>
            ) : (
              <>
                <p className="text-sm text-text leading-relaxed whitespace-pre-wrap">
                  {testB.transformedText}
                </p>
                {testB.transformedText && (
                  <div className="mt-3 flex justify-end">
                    <CopyButton text={testB.transformedText} />
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </div>

      {/* Cost Table */}
      <div className="border border-border/60 rounded-xl overflow-hidden">
        <div className="px-4 py-3 bg-surface border-b border-border/60">
          <h3 className="text-sm font-semibold text-text">비용 분석</h3>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border/60 bg-surface/50">
                <th className="text-left px-4 py-2.5 text-xs font-medium text-text-secondary">항목</th>
                <th className="text-right px-4 py-2.5 text-xs font-medium text-text-secondary">공유 분석</th>
                <th className="text-right px-4 py-2.5 text-xs font-medium text-text-secondary">Test A (4o)</th>
                <th className="text-right px-4 py-2.5 text-xs font-medium text-text-secondary">Test B (mini)</th>
                <th className="text-right px-4 py-2.5 text-xs font-medium text-text-secondary">합계</th>
              </tr>
            </thead>
            <tbody>
              {/* This request */}
              <tr className="border-b border-border/40">
                <td className="px-4 py-2.5 font-medium text-text">이번 요청</td>
                <td className="px-4 py-2.5 text-right text-text-secondary tabular-nums">
                  {formatCost(costInfo.sharedAnalysisCostUsd)}
                </td>
                <td className="px-4 py-2.5 text-right text-text-secondary tabular-nums">
                  {formatCost(costInfo.testACostUsd)}
                </td>
                <td className="px-4 py-2.5 text-right text-text-secondary tabular-nums">
                  {formatCost(costInfo.testBCostUsd)}
                </td>
                <td className="px-4 py-2.5 text-right font-medium text-text tabular-nums">
                  {formatCost(costInfo.thisRequestTotalUsd)}
                </td>
              </tr>

              {/* Token usage */}
              {testA.tokenUsage && testB.tokenUsage && (
                <tr className="border-b border-border/40">
                  <td className="px-4 py-2.5 font-medium text-text">토큰 사용</td>
                  <td className="px-4 py-2.5 text-right text-text-secondary tabular-nums text-xs">-</td>
                  <td className="px-4 py-2.5 text-right text-text-secondary tabular-nums text-xs">
                    {testA.tokenUsage.totalTokens.toLocaleString()}
                  </td>
                  <td className="px-4 py-2.5 text-right text-text-secondary tabular-nums text-xs">
                    {testB.tokenUsage.totalTokens.toLocaleString()}
                  </td>
                  <td className="px-4 py-2.5 text-right text-text-secondary tabular-nums text-xs">-</td>
                </tr>
              )}

              {/* Monthly projections header */}
              <tr className="border-b border-border/60 bg-surface/30">
                <td colSpan={5} className="px-4 py-2 text-xs font-semibold text-text-secondary">
                  월간 예상 비용 (Test A: gpt-4o 기준)
                </td>
              </tr>
              <tr className="border-b border-border/40">
                <td className="px-4 py-2 text-text-secondary text-xs pl-6">1,500건 / 6,000건 / 20,000건</td>
                <td className="px-4 py-2" colSpan={4}>
                  <div className="flex justify-end gap-4 text-xs tabular-nums text-text-secondary">
                    <span>{formatMonthlyCost(costInfo.monthlyA.mvpInitial)}</span>
                    <span>{formatMonthlyCost(costInfo.monthlyA.growth)}</span>
                    <span className="font-medium text-text">{formatMonthlyCost(costInfo.monthlyA.mature)}</span>
                  </div>
                </td>
              </tr>

              <tr className="border-b border-border/60 bg-surface/30">
                <td colSpan={5} className="px-4 py-2 text-xs font-semibold text-text-secondary">
                  월간 예상 비용 (Test B: gpt-4o-mini 기준)
                </td>
              </tr>
              <tr>
                <td className="px-4 py-2 text-text-secondary text-xs pl-6">1,500건 / 6,000건 / 20,000건</td>
                <td className="px-4 py-2" colSpan={4}>
                  <div className="flex justify-end gap-4 text-xs tabular-nums text-text-secondary">
                    <span>{formatMonthlyCost(costInfo.monthlyB.mvpInitial)}</span>
                    <span>{formatMonthlyCost(costInfo.monthlyB.growth)}</span>
                    <span className="font-medium text-text">{formatMonthlyCost(costInfo.monthlyB.mature)}</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
