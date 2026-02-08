import { apiClient } from '@/shared/api';
import type { Persona, Context, ToneLevel } from '@/shared/config/constants';

export interface TransformRequest {
  persona: Persona;
  context: Context[];
  toneLevel: ToneLevel;
  originalText: string;
  optionalUserPrompt?: string;
}

export interface TransformResponse {
  transformedText: string;
}

export function transformText(req: TransformRequest): Promise<TransformResponse> {
  return apiClient.post('/v1/transform', req);
}
