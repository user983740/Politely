package com.politeai.infrastructure.ai;

/**
 * Result of an LLM API call including token usage for cost tracking.
 */
public record LlmCallResult(String content, String analysisContext, long promptTokens, long completionTokens) {}
