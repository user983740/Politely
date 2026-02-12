import { useState } from 'react';
import type { IntermediateAnalysis } from '@/features/transform/api';
import type { IntermediateData } from '@/features/transform/stream-api';

interface Props {
  intermediateAnalysis: IntermediateAnalysis | IntermediateData | null;
}

function getOutputs(analysis: IntermediateAnalysis | IntermediateData): {
  model1Output: string;
  model2Output: string;
  model4Output: string;
} {
  if ('model1Output' in analysis) {
    return analysis;
  }
  return {
    model1Output: analysis.model1,
    model2Output: analysis.model2,
    model4Output: analysis.model4,
  };
}

export default function AnalysisPanel({ intermediateAnalysis }: Props) {
  const [open, setOpen] = useState(false);

  if (!intermediateAnalysis) return null;

  const { model1Output, model2Output, model4Output } = getOutputs(intermediateAnalysis);

  return (
    <div className="border border-border/60 rounded-xl overflow-hidden">
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center justify-between px-4 py-3 bg-surface border-b border-border/60 cursor-pointer hover:bg-surface/80 transition-colors"
      >
        <h3 className="text-sm font-semibold text-text">사전 분석 결과</h3>
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
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {/* Model 1: Situation Analysis + Speaker Intent */}
            <div className="border border-border/40 rounded-lg p-3">
              <h4 className="text-xs font-semibold text-accent mb-2">상황 분석 및 화자 의도</h4>
              <p className="text-xs text-text-secondary whitespace-pre-wrap">{model1Output}</p>
            </div>

            {/* Model 2: Locked Expression Extraction */}
            <div className="border border-border/40 rounded-lg p-3">
              <h4 className="text-xs font-semibold text-accent mb-2">보존 필수 표현</h4>
              <p className="text-xs text-text-secondary whitespace-pre-wrap">{model2Output}</p>
            </div>

            {/* Model 3 (formerly 4): Source Text Deconstruction */}
            <div className="border border-border/40 rounded-lg p-3">
              <h4 className="text-xs font-semibold text-accent mb-2">원문 해체</h4>
              <p className="text-xs text-text-secondary whitespace-pre-wrap">{model4Output}</p>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
