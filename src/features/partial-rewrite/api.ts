import { apiClient } from '@/shared/api';
import type { Persona, Context, ToneLevel } from '@/shared/config/constants';

export interface PartialRewriteRequest {
  selectedText: string;
  optionalContext?: string;
  persona: Persona;
  context: Context[];
  toneLevel: ToneLevel;
  optionalUserPrompt?: string;
}

export interface PartialRewriteResponse {
  rewrittenText: string;
}

export function partialRewrite(req: PartialRewriteRequest): Promise<PartialRewriteResponse> {
  return apiClient.post('/v1/transform/partial', req);
}
