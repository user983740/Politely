import { create } from 'zustand';
import type { Persona, Context, ToneLevel } from '@/shared/config/constants';
import type { TierInfo } from '@/features/transform/api';

interface TransformState {
  persona: Persona | null;
  contexts: Context[];
  toneLevel: ToneLevel | null;
  originalText: string;
  userPrompt: string;
  transformedText: string;
  isTransforming: boolean;
  transformError: string | null;
  tierInfo: TierInfo | null;
  setPersona: (persona: Persona | null) => void;
  toggleContext: (context: Context) => void;
  setToneLevel: (toneLevel: ToneLevel | null) => void;
  setOriginalText: (text: string) => void;
  setUserPrompt: (prompt: string) => void;
  setTransformedText: (text: string) => void;
  setIsTransforming: (v: boolean) => void;
  setTransformError: (error: string | null) => void;
  setTierInfo: (info: TierInfo) => void;
  resetForNewInput: () => void;
  reset: () => void;
}

const initialState = {
  persona: null as Persona | null,
  contexts: [] as Context[],
  toneLevel: null as ToneLevel | null,
  originalText: '',
  userPrompt: '',
  transformedText: '',
  isTransforming: false,
  transformError: null as string | null,
  tierInfo: null as TierInfo | null,
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
  setTransformedText: (transformedText) => set({ transformedText }),
  setIsTransforming: (isTransforming) => set({ isTransforming }),
  setTransformError: (transformError) => set({ transformError }),
  setTierInfo: (tierInfo) => set({ tierInfo }),
  resetForNewInput: () =>
    set({ originalText: '', userPrompt: '', transformedText: '', transformError: null }),
  reset: () => set(initialState),
}));
