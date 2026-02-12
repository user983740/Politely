import { apiClient } from '@/shared/api';
import type { Persona, Context, ToneLevel } from '@/shared/config/constants';

export interface TransformRequest {
  persona: Persona;
  contexts: Context[];
  toneLevel: ToneLevel;
  originalText: string;
  userPrompt?: string;
  senderInfo?: string;
  tierOverride?: string;
}

export interface TransformResponse {
  transformedText: string;
  analysisContext?: string | null;
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

// A/B Test types
export interface ABTestTokenUsage {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  estimatedCostUsd: number;
}

export interface ABTestPipelineEntry {
  label: string;
  transformedText: string | null;
  finalModelDurationMs: number;
  error: string | null;
  tokenUsage: ABTestTokenUsage | null;
}

export interface ABTestMonthlyCost {
  mvpInitial: number;
  growth: number;
  mature: number;
}

export interface ABTestCostInfo {
  sharedAnalysisCostUsd: number;
  testACostUsd: number;
  testBCostUsd: number;
  thisRequestTotalUsd: number;
  monthlyA: ABTestMonthlyCost;
  monthlyB: ABTestMonthlyCost;
}

export interface ABTestResponse {
  testA: ABTestPipelineEntry;
  testB: ABTestPipelineEntry;
  sharedAnalysisDurationMs: number;
  totalDurationMs: number;
  costInfo: ABTestCostInfo;
}

export function abTestTransform(req: TransformRequest): Promise<ABTestResponse> {
  return apiClient.post('/v1/transform/ab-test', req);
}
