import { create } from 'zustand';
import type { Persona, Context, ToneLevel } from '@/shared/config/constants';
import type { TierInfo } from '@/features/transform/api';
import type { LabelData, StatsData, UsageInfo, SegmentData, RelationIntentData, ValidationIssueData, PipelinePhase } from '@/features/transform/stream-api';

interface TransformState {
  persona: Persona | null;
  contexts: Context[];
  toneLevel: ToneLevel | null;
  originalText: string;
  userPrompt: string;
  senderInfo: string;
  transformedText: string;
  analysisContext: string | null;
  labels: LabelData[] | null;
  pipelineStats: StatsData | null;
  segments: SegmentData[] | null;
  maskedText: string | null;
  relationIntent: RelationIntentData | null;
  processedText: string | null;
  validationIssues: ValidationIssueData[] | null;
  currentPhase: PipelinePhase | null;
  isTransforming: boolean;
  transformError: string | null;
  tierInfo: TierInfo | null;
  usageInfo: UsageInfo | null;
  setPersona: (persona: Persona | null) => void;
  toggleContext: (context: Context) => void;
  setToneLevel: (toneLevel: ToneLevel | null) => void;
  setOriginalText: (text: string) => void;
  setUserPrompt: (prompt: string) => void;
  setSenderInfo: (info: string) => void;
  setTransformedText: (text: string) => void;
  appendTransformedText: (chunk: string) => void;
  setAnalysisContext: (ctx: string | null) => void;
  setLabels: (labels: LabelData[] | null) => void;
  setPipelineStats: (stats: StatsData | null) => void;
  setSegments: (segments: SegmentData[] | null) => void;
  setMaskedText: (text: string | null) => void;
  setRelationIntent: (data: RelationIntentData | null) => void;
  setProcessedText: (text: string | null) => void;
  setValidationIssues: (issues: ValidationIssueData[] | null) => void;
  setCurrentPhase: (phase: PipelinePhase | null) => void;
  setIsTransforming: (v: boolean) => void;
  setTransformError: (error: string | null) => void;
  setTierInfo: (info: TierInfo) => void;
  setUsageInfo: (info: UsageInfo | null) => void;
  resetForNewInput: () => void;
  reset: () => void;
}

const initialState = {
  persona: null as Persona | null,
  contexts: [] as Context[],
  toneLevel: null as ToneLevel | null,
  originalText: '',
  userPrompt: '',
  senderInfo: '',
  transformedText: '',
  analysisContext: null as string | null,
  labels: null as LabelData[] | null,
  pipelineStats: null as StatsData | null,
  segments: null as SegmentData[] | null,
  maskedText: null as string | null,
  relationIntent: null as RelationIntentData | null,
  processedText: null as string | null,
  validationIssues: null as ValidationIssueData[] | null,
  currentPhase: null as PipelinePhase | null,
  isTransforming: false,
  transformError: null as string | null,
  tierInfo: null as TierInfo | null,
  usageInfo: null as UsageInfo | null,
};

export const useTransformStore = create<TransformState>((set) => ({
  ...initialState,
  setPersona: (persona) => set({ persona }),
  toggleContext: (context) =>
    set((state) => ({
      contexts: state.contexts.includes(context)
        ? state.contexts.filter((c) => c !== context)
        : [...state.contexts, context],
    })),
  setToneLevel: (toneLevel) => set({ toneLevel }),
  setOriginalText: (originalText) => set({ originalText }),
  setUserPrompt: (userPrompt) => set({ userPrompt }),
  setSenderInfo: (senderInfo) => set({ senderInfo }),
  setTransformedText: (transformedText) => set({ transformedText }),
  appendTransformedText: (chunk) =>
    set((state) => ({ transformedText: state.transformedText + chunk })),
  setAnalysisContext: (analysisContext) => set({ analysisContext }),
  setLabels: (labels) => set({ labels }),
  setPipelineStats: (pipelineStats) => set({ pipelineStats }),
  setSegments: (segments) => set({ segments }),
  setMaskedText: (maskedText) => set({ maskedText }),
  setRelationIntent: (relationIntent) => set({ relationIntent }),
  setProcessedText: (processedText) => set({ processedText }),
  setValidationIssues: (validationIssues) => set({ validationIssues }),
  setCurrentPhase: (currentPhase) => set({ currentPhase }),
  setIsTransforming: (isTransforming) => set({ isTransforming }),
  setTransformError: (transformError) => set({ transformError }),
  setTierInfo: (tierInfo) => set({ tierInfo }),
  setUsageInfo: (usageInfo) => set({ usageInfo }),
  resetForNewInput: () =>
    set({ originalText: '', userPrompt: '', senderInfo: '', transformedText: '', analysisContext: null, labels: null, pipelineStats: null, segments: null, maskedText: null, relationIntent: null, processedText: null, validationIssues: null, currentPhase: null, transformError: null, usageInfo: null }),
  reset: () => set(initialState),
}));
