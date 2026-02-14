import { apiClient } from '@/shared/api';

export interface TierInfo {
  tier: 'FREE' | 'PAID';
  maxTextLength: number;
  promptEnabled: boolean;
}

export function getTierInfo(): Promise<TierInfo> {
  return apiClient.get('/v1/transform/tier');
}
