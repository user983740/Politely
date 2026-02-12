import { apiClient } from '@/shared/api';

export interface TierInfo {
  tier: 'FREE' | 'PAID';
  maxTextLength: number;
  partialRewriteEnabled: boolean;
  promptEnabled: boolean;
}

export function getTierInfo(): Promise<TierInfo> {
  return apiClient.get('/v1/transform/tier');
}

export interface IntermediateAnalysis {
  model1Output: string;
  model2Output: string;
  model4Output: string;
}
