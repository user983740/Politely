import { create } from 'zustand';
import type { Persona, Context, ToneLevel } from '@/shared/config/constants';
import type { TierInfo, ABTestResponse } from '@/features/transform/api';

interface TransformState {
  persona: Persona | null;
  contexts: Context[];
  toneLevel: ToneLevel | null;
  originalText: string;
  userPrompt: string;
  senderInfo: string;
  transformedText: string;
  analysisContext: string | null;
  isTransforming: boolean;
  transformError: string | null;
  tierInfo: TierInfo | null;
  isABTestMode: boolean;
  abTestResult: ABTestResponse | null;
  setPersona: (persona: Persona | null) => void;
  toggleContext: (context: Context) => void;
  setToneLevel: (toneLevel: ToneLevel | null) => void;
  setOriginalText: (text: string) => void;
  setUserPrompt: (prompt: string) => void;
  setSenderInfo: (info: string) => void;
  setTransformedText: (text: string) => void;
  appendTransformedText: (chunk: string) => void;
  setAnalysisContext: (ctx: string | null) => void;
  setIsTransforming: (v: boolean) => void;
  setTransformError: (error: string | null) => void;
  setTierInfo: (info: TierInfo) => void;
  setIsABTestMode: (v: boolean) => void;
  setABTestResult: (result: ABTestResponse | null) => void;
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
  isTransforming: false,
  transformError: null as string | null,
  tierInfo: null as TierInfo | null,
  isABTestMode: false,
  abTestResult: null as ABTestResponse | null,
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
  setIsTransforming: (isTransforming) => set({ isTransforming }),
  setTransformError: (transformError) => set({ transformError }),
  setTierInfo: (tierInfo) => set({ tierInfo }),
  setIsABTestMode: (isABTestMode) => set({ isABTestMode }),
  setABTestResult: (abTestResult) => set({ abTestResult }),
  resetForNewInput: () =>
    set({ originalText: '', userPrompt: '', senderInfo: '', transformedText: '', analysisContext: null, transformError: null, abTestResult: null }),
  reset: () => set(initialState),
}));
