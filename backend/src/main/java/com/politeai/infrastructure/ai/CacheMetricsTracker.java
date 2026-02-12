package com.politeai.infrastructure.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class CacheMetricsTracker {

    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong cacheHitRequests = new AtomicLong();
    private final AtomicLong totalPromptTokens = new AtomicLong();
    private final AtomicLong totalCachedTokens = new AtomicLong();

    public void recordUsage(long promptTokens, long cachedTokens) {
        totalRequests.incrementAndGet();
        totalPromptTokens.addAndGet(promptTokens);

        if (cachedTokens > 0) {
            cacheHitRequests.incrementAndGet();
            totalCachedTokens.addAndGet(cachedTokens);
        }

        double cacheRatio = promptTokens > 0 ? (double) cachedTokens / promptTokens * 100 : 0;
        log.info("Cache metrics - request #{}: promptTokens={}, cachedTokens={}, cacheRatio={}%, " +
                        "cumulative: totalRequests={}, cacheHitRate={}%, tokenCacheRate={}%",
                totalRequests.get(), promptTokens, cachedTokens, String.format("%.1f", cacheRatio),
                totalRequests.get(), String.format("%.1f", getCacheHitRate()), String.format("%.1f", getTokenCacheRate()));
    }

    public double getCacheHitRate() {
        long total = totalRequests.get();
        return total > 0 ? (double) cacheHitRequests.get() / total * 100 : 0;
    }

    public double getTokenCacheRate() {
        long total = totalPromptTokens.get();
        return total > 0 ? (double) totalCachedTokens.get() / total * 100 : 0;
    }
}
