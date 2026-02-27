import { create } from 'zustand';
import type { LabelData, StatsData, UsageInfo, SegmentData, SituationAnalysisData, ValidationIssueData, PipelinePhase, ProcessedSegmentsData, TemplateSelectedData, CushionStrategyData } from '@/features/transform/stream-api';

interface TransformState {
  originalText: string;
  userPrompt: string;
  senderInfo: string;
  transformedText: string;
  labels: LabelData[] | null;
  pipelineStats: StatsData | null;
  segments: SegmentData[] | null;
  maskedText: string | null;
  situationAnalysis: SituationAnalysisData | null;
  processedSegments: ProcessedSegmentsData | null;
  validationIssues: ValidationIssueData[] | null;
  chosenTemplate: TemplateSelectedData | null;
  currentPhase: PipelinePhase | null;
  isTransforming: boolean;
  transformError: string | null;
  usageInfo: UsageInfo | null;
  // A/B mode
  abMode: boolean;
  transformedTextB: string;
  validationIssuesA: ValidationIssueData[] | null;
  validationIssuesB: ValidationIssueData[] | null;
  pipelineStatsB: Record<string, number> | null;
  cushionStrategy: CushionStrategyData | null;
  setOriginalText: (text: string) => void;
  setUserPrompt: (prompt: string) => void;
  setSenderInfo: (info: string) => void;
  setTransformedText: (text: string) => void;
  setLabels: (labels: LabelData[] | null) => void;
  setPipelineStats: (stats: StatsData | null) => void;
  setSegments: (segments: SegmentData[] | null) => void;
  setMaskedText: (text: string | null) => void;
  setSituationAnalysis: (data: SituationAnalysisData | null) => void;
  setProcessedSegments: (data: ProcessedSegmentsData | null) => void;
  setValidationIssues: (issues: ValidationIssueData[] | null) => void;
  setChosenTemplate: (data: TemplateSelectedData | null) => void;
  setCurrentPhase: (phase: PipelinePhase | null) => void;
  setIsTransforming: (v: boolean) => void;
  setTransformError: (error: string | null) => void;
  setUsageInfo: (info: UsageInfo | null) => void;
  setAbMode: (v: boolean) => void;
  setTransformedTextB: (text: string) => void;
  setValidationIssuesA: (issues: ValidationIssueData[] | null) => void;
  setValidationIssuesB: (issues: ValidationIssueData[] | null) => void;
  setPipelineStatsB: (stats: Record<string, number> | null) => void;
  setCushionStrategy: (data: CushionStrategyData | null) => void;
  resetForNewInput: () => void;
  reset: () => void;
}

const initialState = {
  originalText: '',
  userPrompt: '',
  senderInfo: '',
  transformedText: '',
  labels: null as LabelData[] | null,
  pipelineStats: null as StatsData | null,
  segments: null as SegmentData[] | null,
  maskedText: null as string | null,
  situationAnalysis: null as SituationAnalysisData | null,
  processedSegments: null as ProcessedSegmentsData | null,
  validationIssues: null as ValidationIssueData[] | null,
  chosenTemplate: null as TemplateSelectedData | null,
  currentPhase: null as PipelinePhase | null,
  isTransforming: false,
  transformError: null as string | null,
  usageInfo: null as UsageInfo | null,
  // A/B mode
  abMode: false,
  transformedTextB: '',
  validationIssuesA: null as ValidationIssueData[] | null,
  validationIssuesB: null as ValidationIssueData[] | null,
  pipelineStatsB: null as Record<string, number> | null,
  cushionStrategy: null as CushionStrategyData | null,
};

export const useTransformStore = create<TransformState>((set) => ({
  ...initialState,
  setOriginalText: (originalText) => set({ originalText }),
  setUserPrompt: (userPrompt) => set({ userPrompt }),
  setSenderInfo: (senderInfo) => set({ senderInfo }),
  setTransformedText: (transformedText) => set({ transformedText }),
  setLabels: (labels) => set({ labels }),
  setPipelineStats: (pipelineStats) => set({ pipelineStats }),
  setSegments: (segments) => set({ segments }),
  setMaskedText: (maskedText) => set({ maskedText }),
  setSituationAnalysis: (situationAnalysis) => set({ situationAnalysis }),
  setProcessedSegments: (processedSegments) => set({ processedSegments }),
  setValidationIssues: (validationIssues) => set({ validationIssues }),
  setChosenTemplate: (chosenTemplate) => set({ chosenTemplate }),
  setCurrentPhase: (currentPhase) => set({ currentPhase }),
  setIsTransforming: (isTransforming) => set({ isTransforming }),
  setTransformError: (transformError) => set({ transformError }),
  setUsageInfo: (usageInfo) => set({ usageInfo }),
  setAbMode: (abMode) => set({ abMode }),
  setTransformedTextB: (transformedTextB) => set({ transformedTextB }),
  setValidationIssuesA: (validationIssuesA) => set({ validationIssuesA }),
  setValidationIssuesB: (validationIssuesB) => set({ validationIssuesB }),
  setPipelineStatsB: (pipelineStatsB) => set({ pipelineStatsB }),
  setCushionStrategy: (cushionStrategy) => set({ cushionStrategy }),
  resetForNewInput: () =>
    set({ originalText: '', userPrompt: '', senderInfo: '', transformedText: '', labels: null, pipelineStats: null, segments: null, maskedText: null, situationAnalysis: null, processedSegments: null, validationIssues: null, chosenTemplate: null, currentPhase: null, transformError: null, usageInfo: null, transformedTextB: '', validationIssuesA: null, validationIssuesB: null, pipelineStatsB: null, cushionStrategy: null }),
  reset: () => set(initialState),
}));
