import type { Persona, Context, ToneLevel, Topic, Purpose } from '@/shared/config/constants';
import { API_BASE_URL } from '@/shared/config/constants';
import { ApiError } from '@/shared/api/client';

export interface StreamTransformRequest {
  persona: Persona;
  contexts: Context[];
  toneLevel: ToneLevel;
  originalText: string;
  userPrompt?: string;
  senderInfo?: string;
  identityBoosterToggle?: boolean;
  topic?: Topic;
  purpose?: Purpose;
}

export interface LockedSpanInfo {
  placeholder: string;
  original: string;
  type: string;
}

export interface SegmentData {
  id: string;
  text: string;
  start: number;
  end: number;
}

export interface SituationAnalysisData {
  facts: { content: string; source: string }[];
  intent: string;
}

export interface ValidationIssueData {
  type: string;
  severity: 'ERROR' | 'WARNING';
  message: string;
  matchedText: string | null;
}

export interface LabelData {
  segmentId: string;
  label: string;
  tier: 'GREEN' | 'YELLOW' | 'RED';
  text: string;
}

export interface ProcessedSegmentItem {
  id: string;
  tier: 'GREEN' | 'YELLOW' | 'RED';
  label: string;
  text: string | null;
}

export type ProcessedSegmentsData = ProcessedSegmentItem[];

export interface TemplateSelectedData {
  templateId: string;
  templateName: string;
  metadataOverridden: boolean;
}

export interface StatsData {
  segmentCount: number;
  greenCount: number;
  yellowCount: number;
  redCount: number;
  lockedSpanCount: number;
  retryCount: number;
  identityBoosterFired: boolean;
  situationAnalysisFired: boolean;
  metadataOverridden: boolean;
  chosenTemplateId: string;
  latencyMs: number;
}

export interface UsageInfo {
  analysisPromptTokens: number;
  analysisCompletionTokens: number;
  finalPromptTokens: number;
  finalCompletionTokens: number;
  totalCostUsd: number;
  monthly: {
    mvp: number;
    growth: number;
    mature: number;
  };
}

export type PipelinePhase =
  | 'normalizing'
  | 'extracting'
  | 'identity_boosting'
  | 'identity_skipped'
  | 'segmenting'
  | 'segment_refining'
  | 'segment_refining_skipped'
  | 'labeling'
  | 'situation_analyzing'
  | 'template_selecting'
  | 'situation_skipped'
  | 'redacting'
  | 'generating'
  | 'validating'
  | 'complete';

export interface StreamCallbacks {
  onDelta?: (chunk: string) => void;
  onLabels?: (labels: LabelData[]) => void;
  onStats?: (stats: StatsData) => void;
  onUsage?: (usage: UsageInfo) => void;
  onDone?: (fullText: string) => void;
  onError?: (message: string) => void;
  onSpans?: (spans: LockedSpanInfo[]) => void;
  onSegments?: (segments: SegmentData[]) => void;
  onMaskedText?: (text: string) => void;
  onSituationAnalysis?: (data: SituationAnalysisData) => void;
  onProcessedSegments?: (data: ProcessedSegmentsData) => void;
  onValidationIssues?: (issues: ValidationIssueData[]) => void;
  onTemplateSelected?: (data: TemplateSelectedData) => void;
  onPhase?: (phase: PipelinePhase) => void;
  onRetry?: () => void;
}

function getHeaders(): HeadersInit {
  const headers: HeadersInit = { 'Content-Type': 'application/json' };
  const token = localStorage.getItem('token');
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  return headers;
}

async function streamSSE(
  url: string,
  body: unknown,
  callbacks: StreamCallbacks,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(url, {
    method: 'POST',
    headers: getHeaders(),
    body: JSON.stringify(body),
    signal,
  });

  if (!res.ok) {
    const data = await res.json().catch(() => null);
    throw new ApiError(
      res.status,
      data?.error ?? 'UNKNOWN_ERROR',
      data?.message ?? `API error: ${res.status}`,
    );
  }

  const reader = res.body?.getReader();
  if (!reader) throw new Error('Response body is not readable');

  const decoder = new TextDecoder();
  let buffer = '';
  let currentEvent = '';
  let currentDataLines: string[] = [];

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // Parse SSE events from buffer (split on \r\n or \n to handle CRLF from sse-starlette)
      const lines = buffer.split(/\r?\n/);
      buffer = lines.pop() ?? ''; // Keep incomplete line in buffer

      for (const line of lines) {
        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          // SSE spec: strip one leading space after "data:" if present
          const value = line.slice(5);
          currentDataLines.push(value.startsWith(' ') ? value.slice(1) : value);
        } else if (line === '' && currentEvent) {
          // Empty line = end of event
          dispatchEvent(currentEvent, currentDataLines.join('\n'), callbacks);
          currentEvent = '';
          currentDataLines = [];
        }
      }
    }

    // Dispatch any remaining event in buffer
    if (currentEvent && currentDataLines.length > 0) {
      dispatchEvent(currentEvent, currentDataLines.join('\n'), callbacks);
    } else if (buffer.trim()) {
      const remaining = buffer.split(/\r?\n/);
      for (const line of remaining) {
        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          const value = line.slice(5);
          currentDataLines.push(value.startsWith(' ') ? value.slice(1) : value);
        }
      }
      if (currentEvent && currentDataLines.length > 0) {
        dispatchEvent(currentEvent, currentDataLines.join('\n'), callbacks);
      }
    }
  } finally {
    reader.releaseLock();
  }
}

function dispatchEvent(event: string, data: string, callbacks: StreamCallbacks) {
  switch (event) {
    case 'delta':
      callbacks.onDelta?.(data);
      break;
    case 'done':
      callbacks.onDone?.(data);
      break;
    case 'error':
      callbacks.onError?.(data);
      break;
    case 'labels':
      try {
        callbacks.onLabels?.(JSON.parse(data));
      } catch {
        // ignore parse error
      }
      break;
    case 'stats':
      try {
        callbacks.onStats?.(JSON.parse(data));
      } catch {
        // ignore parse error
      }
      break;
    case 'spans':
      try {
        callbacks.onSpans?.(JSON.parse(data));
      } catch {
        // ignore parse error
      }
      break;
    case 'usage':
      try {
        callbacks.onUsage?.(JSON.parse(data));
      } catch {
        // ignore parse error
      }
      break;
    case 'segments':
      try {
        callbacks.onSegments?.(JSON.parse(data));
      } catch {
        // ignore parse error
      }
      break;
    case 'maskedText':
      callbacks.onMaskedText?.(data);
      break;
    case 'situationAnalysis':
      try {
        callbacks.onSituationAnalysis?.(JSON.parse(data));
      } catch {
        // ignore parse error
      }
      break;
    case 'processedSegments':
      try {
        callbacks.onProcessedSegments?.(JSON.parse(data));
      } catch {
        // ignore parse error
      }
      break;
    case 'validationIssues':
      try {
        callbacks.onValidationIssues?.(JSON.parse(data));
      } catch {
        // ignore parse error
      }
      break;
    case 'templateSelected':
      try {
        callbacks.onTemplateSelected?.(JSON.parse(data));
      } catch {
        // ignore parse error
      }
      break;
    case 'phase':
      callbacks.onPhase?.(data as PipelinePhase);
      break;
    case 'retry':
      callbacks.onRetry?.();
      break;
  }
}

export function streamTransform(
  req: StreamTransformRequest,
  callbacks: StreamCallbacks,
  signal?: AbortSignal,
): Promise<void> {
  return streamSSE(`${API_BASE_URL}/v1/transform/stream`, req, callbacks, signal);
}

