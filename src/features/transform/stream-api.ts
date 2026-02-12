import type { Persona, Context, ToneLevel } from '@/shared/config/constants';
import { API_BASE_URL } from '@/shared/config/constants';
import { ApiError } from '@/shared/api/client';

export interface StreamTransformRequest {
  persona: Persona;
  contexts: Context[];
  toneLevel: ToneLevel;
  originalText: string;
  userPrompt?: string;
  senderInfo?: string;
  tierOverride?: string;
}

export interface StreamPartialRewriteRequest {
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

export interface LockedSpanInfo {
  placeholder: string;
  original: string;
}

export interface IntermediateData {
  model1: string;
  model2: string;
  model3: string;
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

export interface StreamCallbacks {
  onDelta?: (chunk: string) => void;
  onAnalysis?: (analysis: string) => void;
  onIntermediate?: (data: IntermediateData) => void;
  onUsage?: (usage: UsageInfo) => void;
  onDone?: (fullText: string) => void;
  onError?: (message: string) => void;
  onSpans?: (spans: LockedSpanInfo[]) => void;
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

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // Parse SSE events from buffer
      const lines = buffer.split('\n');
      buffer = lines.pop() ?? ''; // Keep incomplete line in buffer

      let currentEvent = '';
      let currentDataLines: string[] = [];

      for (const line of lines) {
        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          currentDataLines.push(line.slice(5));
        } else if (line === '' && currentEvent) {
          // Empty line = end of event
          dispatchEvent(currentEvent, currentDataLines.join('\n'), callbacks);
          currentEvent = '';
          currentDataLines = [];
        }
      }
    }

    // Process any remaining buffer
    if (buffer.trim()) {
      const remaining = buffer.split('\n');
      let currentEvent = '';
      const currentDataLines: string[] = [];
      for (const line of remaining) {
        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          currentDataLines.push(line.slice(5));
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
    case 'analysis':
      callbacks.onAnalysis?.(data);
      break;
    case 'done':
      callbacks.onDone?.(data);
      break;
    case 'error':
      callbacks.onError?.(data);
      break;
    case 'intermediate':
      try {
        callbacks.onIntermediate?.(JSON.parse(data));
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
  }
}

export function streamTransform(
  req: StreamTransformRequest,
  callbacks: StreamCallbacks,
  signal?: AbortSignal,
): Promise<void> {
  return streamSSE(`${API_BASE_URL}/v1/transform/stream`, req, callbacks, signal);
}

export function streamPartialRewrite(
  req: StreamPartialRewriteRequest,
  callbacks: StreamCallbacks,
  signal?: AbortSignal,
): Promise<void> {
  return streamSSE(`${API_BASE_URL}/v1/transform/partial/stream`, req, callbacks, signal);
}
