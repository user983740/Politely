import { apiClient } from '@/shared/api';
import type { Persona, Context, ToneLevel } from '@/shared/config/constants';

export interface TransformRequest {
  persona: Persona;
  contexts: Context[];
  toneLevel: ToneLevel;
  originalText: string;
  userPrompt?: string;
}

export interface TransformResponse {
  transformedText: string;
}

export interface TierInfo {
  tier: 'FREE' | 'PAID';
  maxTextLength: number;
  partialRewriteEnabled: boolean;
  promptEnabled: boolean;
}

export function transformText(req: TransformRequest): Promise<TransformResponse> {
  return apiClient.post('/v1/transform', req);
}

export function getTierInfo(): Promise<TierInfo> {
  return apiClient.get('/v1/transform/tier');
}
