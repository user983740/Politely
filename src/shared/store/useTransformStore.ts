import { create } from 'zustand';
import type { Persona, Context, ToneLevel } from '@/shared/config/constants';

interface TransformState {
  persona: Persona | null;
  contexts: Context[];
  toneLevel: ToneLevel | null;
  originalText: string;
  userPrompt: string;
  transformedText: string;
  setPersona: (persona: Persona | null) => void;
  toggleContext: (context: Context) => void;
  setToneLevel: (toneLevel: ToneLevel | null) => void;
  setOriginalText: (text: string) => void;
  setUserPrompt: (prompt: string) => void;
  setTransformedText: (text: string) => void;
  reset: () => void;
}

const initialState = {
  persona: null as Persona | null,
  contexts: [] as Context[],
  toneLevel: null as ToneLevel | null,
  originalText: '',
  userPrompt: '',
  transformedText: '',
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
  reset: () => set(initialState),
}));
