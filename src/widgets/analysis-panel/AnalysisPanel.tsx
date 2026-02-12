import { useState } from 'react';
import type { IntermediateAnalysis } from '@/features/transform/api';
import type { IntermediateData } from '@/features/transform/stream-api';

interface Props {
  intermediateAnalysis: IntermediateAnalysis | IntermediateData | null;
}

interface Model1Data {
  situation?: string;
  relationship?: string;
  factualContext?: string[];
  communicationChannel?: string;
}

interface LockedExpression {
  text: string;
  type: string;
  reason: string;
}

interface Model2Data {
  lockedExpressions?: LockedExpression[];
}

interface Model3Data {
  primaryIntent?: string;
  emotionalState?: string[];
  underlyingNeeds?: string;
  desiredOutcome?: string;
  toneImplications?: string;
}

function parseJsonSafe<T>(raw: string): T | null {
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

function getOutputs(analysis: IntermediateAnalysis | IntermediateData): {
  model1Output: string;
  model2Output: string;
  model3Output: string;
} {
  if ('model1Output' in analysis) {
    return analysis;
  }
  return {
    model1Output: analysis.model1,
    model2Output: analysis.model2,
    model3Output: analysis.model3,
  };
}

export default function AnalysisPanel({ intermediateAnalysis }: Props) {
  const [open, setOpen] = useState(false);

  if (!intermediateAnalysis) return null;

  const { model1Output, model2Output, model3Output } = getOutputs(intermediateAnalysis);

  const model1 = parseJsonSafe<Model1Data>(model1Output);
  const model2 = parseJsonSafe<Model2Data>(model2Output);
  const model3 = parseJsonSafe<Model3Data>(model3Output);

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
            {/* Model 1: Situation Analysis */}
            <div className="border border-border/40 rounded-lg p-3">
              <h4 className="text-xs font-semibold text-accent mb-2">상황 분석</h4>
              {model1 ? (
                <div className="space-y-2 text-xs text-text-secondary">
                  {model1.situation ? (
                    <div>
                      <span className="font-medium text-text">상황: </span>
                      {model1.situation}
                    </div>
                  ) : null}
                  {model1.relationship ? (
                    <div>
                      <span className="font-medium text-text">관계: </span>
                      {model1.relationship}
                    </div>
                  ) : null}
                  {model1.factualContext && model1.factualContext.length > 0 ? (
                    <div>
                      <span className="font-medium text-text">사실:</span>
                      <ul className="list-disc list-inside mt-1">
                        {model1.factualContext.map((fact, i) => (
                          <li key={i}>{fact}</li>
                        ))}
                      </ul>
                    </div>
                  ) : null}
                  {model1.communicationChannel ? (
                    <div>
                      <span className="font-medium text-text">채널: </span>
                      {model1.communicationChannel}
                    </div>
                  ) : null}
                </div>
              ) : (
                <p className="text-xs text-text-secondary whitespace-pre-wrap">{model1Output}</p>
              )}
            </div>

            {/* Model 2: Locked Expression Extraction */}
            <div className="border border-border/40 rounded-lg p-3">
              <h4 className="text-xs font-semibold text-accent mb-2">보존 필수 표현</h4>
              {model2 ? (
                <div className="space-y-1.5 text-xs text-text-secondary">
                  {model2.lockedExpressions && model2.lockedExpressions.length > 0 ? (
                    model2.lockedExpressions.map((expr, i) => (
                      <div key={i} className="flex items-start gap-2">
                        <span className="px-1.5 py-0.5 bg-accent/10 text-accent rounded text-[10px] font-medium shrink-0">
                          {expr.type}
                        </span>
                        <div>
                          <span className="font-medium text-text">{expr.text}</span>
                          <span className="text-text-secondary/70"> — {expr.reason}</span>
                        </div>
                      </div>
                    ))
                  ) : (
                    <p className="text-text-secondary/60">변경 불가 표현 없음</p>
                  )}
                </div>
              ) : (
                <p className="text-xs text-text-secondary whitespace-pre-wrap">{model2Output}</p>
              )}
            </div>

            {/* Model 3: Speaker Intent Analysis */}
            <div className="border border-border/40 rounded-lg p-3">
              <h4 className="text-xs font-semibold text-accent mb-2">화자 의도</h4>
              {model3 ? (
                <div className="space-y-2 text-xs text-text-secondary">
                  {model3.primaryIntent ? (
                    <div>
                      <span className="font-medium text-text">핵심 의도: </span>
                      {model3.primaryIntent}
                    </div>
                  ) : null}
                  {model3.emotionalState && model3.emotionalState.length > 0 ? (
                    <div className="flex items-center gap-1 flex-wrap">
                      <span className="font-medium text-text">감정: </span>
                      {model3.emotionalState.map((emotion, i) => (
                        <span key={i} className="px-1.5 py-0.5 bg-surface rounded text-[10px]">{emotion}</span>
                      ))}
                    </div>
                  ) : null}
                  {model3.underlyingNeeds ? (
                    <div>
                      <span className="font-medium text-text">숨겨진 욕구: </span>
                      {model3.underlyingNeeds}
                    </div>
                  ) : null}
                  {model3.desiredOutcome ? (
                    <div>
                      <span className="font-medium text-text">원하는 결과: </span>
                      {model3.desiredOutcome}
                    </div>
                  ) : null}
                  {model3.toneImplications ? (
                    <div>
                      <span className="font-medium text-text">어투 함의: </span>
                      {model3.toneImplications}
                    </div>
                  ) : null}
                </div>
              ) : (
                <p className="text-xs text-text-secondary whitespace-pre-wrap">{model3Output}</p>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
