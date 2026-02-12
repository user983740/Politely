import { apiClient } from '@/shared/api';
import type { Persona, Context, ToneLevel } from '@/shared/config/constants';

export interface PartialRewriteRequest {
  selectedText: string;
  fullContext?: string;
  persona: Persona;
  contexts: Context[];
  toneLevel: ToneLevel;
  userPrompt?: string;
  senderInfo?: string;
  tierOverride?: string;
  analysisContext?: string | null;
}

export interface PartialRewriteResponse {
  rewrittenText: string;
}

export function partialRewrite(req: PartialRewriteRequest): Promise<PartialRewriteResponse> {
  return apiClient.post('/v1/transform/partial', req);
}
