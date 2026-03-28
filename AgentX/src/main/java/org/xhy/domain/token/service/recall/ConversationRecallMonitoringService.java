package org.xhy.domain.token.service.recall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xhy.domain.token.model.config.TokenOverflowConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Runtime monitoring for conversation recall strategy. */
@Service
public class ConversationRecallMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(ConversationRecallMonitoringService.class);

    private final LongAdder strategyTriggered = new LongAdder();
    private final LongAdder strategySkipped = new LongAdder();
    private final LongAdder strategyFallback = new LongAdder();
    private final LongAdder strategySucceeded = new LongAdder();
    private final LongAdder vectorSearchCount = new LongAdder();
    private final LongAdder vectorSearchEmpty = new LongAdder();
    private final LongAdder vectorSearchError = new LongAdder();
    private final LongAdder rerankEnabled = new LongAdder();
    private final LongAdder rerankSuccess = new LongAdder();
    private final LongAdder rerankFailure = new LongAdder();
    private final LongAdder indexSuccess = new LongAdder();
    private final LongAdder indexFailure = new LongAdder();

    public void recordStrategySkipped(TokenOverflowConfig config, int totalTokens, int triggerThreshold, String reason) {
        strategySkipped.increment();
        log.info(
                "conversation_recall_strategy result=skipped reason={} userId={} sessionId={} tokensBefore={} triggerThreshold={} counters={}",
                reason, safe(config != null ? config.getUserId() : null), safe(config != null ? config.getSessionId() : null),
                totalTokens, triggerThreshold, snapshot());
    }

    public void recordStrategyTriggered(TokenOverflowConfig config, int totalTokens, int triggerThreshold) {
        strategyTriggered.increment();
        log.info("conversation_recall_strategy result=triggered userId={} sessionId={} tokensBefore={} triggerThreshold={}",
                safe(config != null ? config.getUserId() : null), safe(config != null ? config.getSessionId() : null),
                totalTokens, triggerThreshold);
    }

    public void recordStrategyFallback(TokenOverflowConfig config, String reason, int totalTokensBefore,
            int totalTokensAfter, int recentCount, int recallPoolSize, long latencyMs) {
        strategyFallback.increment();
        log.info(
                "conversation_recall_strategy result=fallback reason={} userId={} sessionId={} tokensBefore={} tokensAfter={} recentCount={} recallPoolSize={} rerankEnabled={} latencyMs={} counters={}",
                reason, safe(config != null ? config.getUserId() : null), safe(config != null ? config.getSessionId() : null),
                totalTokensBefore, totalTokensAfter, recentCount, recallPoolSize,
                config != null && Boolean.TRUE.equals(config.getEnableRerank()), latencyMs, snapshot());
    }

    public void recordStrategySuccess(TokenOverflowConfig config, int totalTokensBefore, int totalTokensAfter,
            int recentCount, int recallPoolSize, int recalledGroupCount, int recalledMessageCount, long latencyMs) {
        strategySucceeded.increment();
        log.info(
                "conversation_recall_strategy result=success userId={} sessionId={} tokensBefore={} tokensAfter={} recentCount={} recallPoolSize={} recalledGroups={} recalledMessages={} rerankEnabled={} latencyMs={} counters={}",
                safe(config != null ? config.getUserId() : null), safe(config != null ? config.getSessionId() : null),
                totalTokensBefore, totalTokensAfter, recentCount, recallPoolSize, recalledGroupCount, recalledMessageCount,
                config != null && Boolean.TRUE.equals(config.getEnableRerank()), latencyMs, snapshot());
    }

    public void recordVectorSearch(TokenOverflowConfig config, int maxCandidates, double minScore, int hits,
            long latencyMs, boolean success) {
        vectorSearchCount.increment();
        if (!success) {
            vectorSearchError.increment();
        } else if (hits == 0) {
            vectorSearchEmpty.increment();
        }
        log.info(
                "conversation_recall_vector_search success={} userId={} sessionId={} queryLength={} maxCandidates={} minScore={} hits={} latencyMs={} counters={}",
                success, safe(config != null ? config.getUserId() : null), safe(config != null ? config.getSessionId() : null),
                config != null && config.getRecallQuery() != null ? config.getRecallQuery().length() : 0, maxCandidates,
                minScore, hits, latencyMs, snapshot());
    }

    public void recordRerank(TokenOverflowConfig config, int candidateCount, long latencyMs, boolean success) {
        rerankEnabled.increment();
        if (success) {
            rerankSuccess.increment();
        } else {
            rerankFailure.increment();
        }
        log.info(
                "conversation_recall_rerank success={} userId={} sessionId={} candidateCount={} queryLength={} latencyMs={} counters={}",
                success, safe(config != null ? config.getUserId() : null), safe(config != null ? config.getSessionId() : null),
                candidateCount, config != null && config.getRecallQuery() != null ? config.getRecallQuery().length() : 0,
                latencyMs, snapshot());
    }

    public void recordIndex(String userId, String sessionId, String messageId, boolean success, long latencyMs) {
        if (success) {
            indexSuccess.increment();
        } else {
            indexFailure.increment();
        }
        log.info(
                "conversation_recall_index success={} userId={} sessionId={} messageId={} latencyMs={} counters={}",
                success, safe(userId), safe(sessionId), safe(messageId), latencyMs, snapshot());
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("strategyTriggered", strategyTriggered.sum());
        counters.put("strategySkipped", strategySkipped.sum());
        counters.put("strategyFallback", strategyFallback.sum());
        counters.put("strategySucceeded", strategySucceeded.sum());
        counters.put("vectorSearchCount", vectorSearchCount.sum());
        counters.put("vectorSearchEmpty", vectorSearchEmpty.sum());
        counters.put("vectorSearchError", vectorSearchError.sum());
        counters.put("rerankEnabled", rerankEnabled.sum());
        counters.put("rerankSuccess", rerankSuccess.sum());
        counters.put("rerankFailure", rerankFailure.sum());
        counters.put("indexSuccess", indexSuccess.sum());
        counters.put("indexFailure", indexFailure.sum());
        return counters;
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
