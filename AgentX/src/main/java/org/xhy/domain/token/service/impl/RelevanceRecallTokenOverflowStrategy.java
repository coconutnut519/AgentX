package org.xhy.domain.token.service.impl;

import org.xhy.domain.shared.enums.TokenOverflowStrategyEnum;
import org.xhy.domain.token.model.TokenMessage;
import org.xhy.domain.token.model.TokenProcessResult;
import org.xhy.domain.token.model.config.TokenOverflowConfig;
import org.xhy.domain.token.model.recall.RecallCandidate;
import org.xhy.domain.token.service.TokenOverflowStrategy;
import org.xhy.domain.token.service.recall.ConversationRecallMonitoringService;
import org.xhy.domain.token.service.recall.ConversationMessageRecallService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Token overflow strategy that preserves recent context and recalls semantically
 * relevant older messages.
 */
public class RelevanceRecallTokenOverflowStrategy implements TokenOverflowStrategy {

    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final int DEFAULT_TRIGGER_PERCENT = 80;
    private static final double DEFAULT_RECENT_RATIO = 0.4D;
    private static final int DEFAULT_RECALL_TOP_K = 4;

    private final TokenOverflowConfig config;
    private final ConversationMessageRecallService recallService;
    private final ConversationRecallMonitoringService monitoringService;

    public RelevanceRecallTokenOverflowStrategy(TokenOverflowConfig config,
            ConversationMessageRecallService recallService) {
        this(config, recallService, null);
    }

    public RelevanceRecallTokenOverflowStrategy(TokenOverflowConfig config,
            ConversationMessageRecallService recallService, ConversationRecallMonitoringService monitoringService) {
        this.config = config;
        this.recallService = recallService;
        this.monitoringService = monitoringService;
    }

    @Override
    public TokenProcessResult process(List<TokenMessage> messages, TokenOverflowConfig tokenOverflowConfig) {
        long startedAt = System.currentTimeMillis();
        TokenOverflowConfig effectiveConfig = mergeConfig(tokenOverflowConfig);
        List<TokenMessage> sortedMessages = sortMessages(messages);
        int totalTokensBefore = calculateTotalTokens(sortedMessages);
        int triggerThreshold = getTriggerTokenThreshold(effectiveConfig);

        if (!needsProcessing(sortedMessages, effectiveConfig)) {
            if (monitoringService != null) {
                monitoringService.recordStrategySkipped(effectiveConfig, totalTokensBefore, triggerThreshold,
                        "below_trigger_threshold");
            }
            return buildUnprocessedResult(sortedMessages);
        }
        if (monitoringService != null) {
            monitoringService.recordStrategyTriggered(effectiveConfig, totalTokensBefore, triggerThreshold);
        }

        if (isBlank(effectiveConfig.getRecallQuery())) {
            return fallbackToSlidingWindow(sortedMessages, effectiveConfig, "missing_query", totalTokensBefore, 0, 0,
                    startedAt);
        }

        RecentSelection recentSelection = selectRecentMessages(sortedMessages, effectiveConfig);
        List<TokenMessage> recallPool = new ArrayList<>(recentSelection.recallPool());
        if (recallPool.isEmpty()) {
            return fallbackToSlidingWindow(sortedMessages, effectiveConfig, "empty_recall_pool", totalTokensBefore,
                    recentSelection.recentMessages().size(), 0, startedAt);
        }

        List<RecallCandidate> recalled = recallService.recall(recallPool, effectiveConfig);
        if (recalled.isEmpty()) {
            return fallbackToSlidingWindow(sortedMessages, effectiveConfig, "empty_recall_result", totalTokensBefore,
                    recentSelection.recentMessages().size(), recallPool.size(), startedAt);
        }

        List<RecallCandidate> topKRecalled = recalled.stream().limit(getRecallTopK(effectiveConfig)).toList();
        List<TokenMessage> retainedMessages = composeRetainedMessages(topKRecalled, recentSelection.recentMessages(),
                effectiveConfig);

        if (retainedMessages.isEmpty()) {
            return fallbackToSlidingWindow(sortedMessages, effectiveConfig, "empty_retained_messages", totalTokensBefore,
                    recentSelection.recentMessages().size(), recallPool.size(), startedAt);
        }

        TokenProcessResult result = new TokenProcessResult();
        result.setRetainedMessages(retainedMessages);
        result.setStrategyName(getName());
        result.setProcessed(true);
        result.setTotalTokens(calculateTotalTokens(retainedMessages));
        if (monitoringService != null) {
            monitoringService.recordStrategySuccess(effectiveConfig, totalTokensBefore, result.getTotalTokens(),
                    recentSelection.recentMessages().size(), recallPool.size(), topKRecalled.size(),
                    topKRecalled.stream().mapToInt(candidate -> candidate.messages() != null ? candidate.messages().size() : 0)
                            .sum(),
                    System.currentTimeMillis() - startedAt);
        }
        return result;
    }

    @Override
    public String getName() {
        return TokenOverflowStrategyEnum.RELEVANCE_RECALL.name();
    }

    @Override
    public boolean needsProcessing(List<TokenMessage> messages) {
        return needsProcessing(sortMessages(messages), mergeConfig(null));
    }

    private boolean needsProcessing(List<TokenMessage> messages, TokenOverflowConfig effectiveConfig) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        return calculateTotalTokens(messages) > getTriggerTokenThreshold(effectiveConfig);
    }

    private TokenProcessResult buildUnprocessedResult(List<TokenMessage> messages) {
        TokenProcessResult result = new TokenProcessResult();
        result.setRetainedMessages(messages);
        result.setStrategyName(getName());
        result.setProcessed(false);
        result.setTotalTokens(calculateTotalTokens(messages));
        return result;
    }

    private TokenProcessResult fallbackToSlidingWindow(List<TokenMessage> sortedMessages, TokenOverflowConfig effectiveConfig,
            String reason, int totalTokensBefore, int recentCount, int recallPoolSize, long startedAt) {
        SlidingWindowTokenOverflowStrategy fallback = new SlidingWindowTokenOverflowStrategy(effectiveConfig);
        TokenProcessResult result = fallback.process(sortedMessages, effectiveConfig);
        result.setStrategyName(getName() + "_FALLBACK_SLIDING_WINDOW");
        if (monitoringService != null) {
            monitoringService.recordStrategyFallback(effectiveConfig, reason, totalTokensBefore, result.getTotalTokens(),
                    recentCount, recallPoolSize, System.currentTimeMillis() - startedAt);
        }
        return result;
    }

    private RecentSelection selectRecentMessages(List<TokenMessage> sortedMessages, TokenOverflowConfig config) {
        int recentBudget = getRecentMessageBudget(config);
        List<TokenMessage> recentMessages = new ArrayList<>();
        int recentTokens = 0;

        for (int i = sortedMessages.size() - 1; i >= 0; i--) {
            TokenMessage message = sortedMessages.get(i);
            int messageTokens = getMessageTokens(message);
            if (recentBudget > 0 && (recentMessages.isEmpty() || recentTokens + messageTokens <= recentBudget)) {
                recentMessages.add(0, message);
                recentTokens += messageTokens;
                continue;
            }
            break;
        }

        if (recentMessages.isEmpty() && !sortedMessages.isEmpty()) {
            TokenMessage latestMessage = sortedMessages.get(sortedMessages.size() - 1);
            recentMessages.add(latestMessage);
            recentTokens = getMessageTokens(latestMessage);
        }

        int splitIndex = Math.max(0, sortedMessages.size() - recentMessages.size());
        List<TokenMessage> recallPool = new ArrayList<>(sortedMessages.subList(0, splitIndex));
        return new RecentSelection(recallPool, recentMessages, recentTokens);
    }

    private List<TokenMessage> composeRetainedMessages(List<RecallCandidate> recalledCandidates,
            List<TokenMessage> recentMessages, TokenOverflowConfig config) {
        Map<String, TokenMessage> unique = new LinkedHashMap<>();

        List<RecallCandidate> sortedRecalled = recalledCandidates.stream()
                .sorted(Comparator.comparing(RecallCandidate::firstCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());

        for (RecallCandidate candidate : sortedRecalled) {
            for (TokenMessage message : candidate.messages()) {
                if (message != null && message.getId() != null) {
                    unique.put(message.getId(), message);
                }
            }
        }

        for (TokenMessage message : recentMessages) {
            if (message != null && message.getId() != null) {
                unique.put(message.getId(), message);
            }
        }

        List<TokenMessage> retained = new ArrayList<>(unique.values());
        retained.sort(Comparator.comparing(TokenMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));

        int maxTokens = getMaxTokens(config);
        List<RecallCandidate> removableRecalled = new ArrayList<>(recalledCandidates);
        removableRecalled.sort(Comparator.comparingDouble(RecallCandidate::score));

        while (calculateTotalTokens(retained) > maxTokens && !removableRecalled.isEmpty()) {
            RecallCandidate lowest = removableRecalled.remove(0);
            if (lowest.messages() != null) {
                for (TokenMessage message : lowest.messages()) {
                    if (message != null) {
                        retained.removeIf(existing -> Objects.equals(existing.getId(), message.getId()) && recentMessages
                                .stream().noneMatch(recent -> Objects.equals(recent.getId(), existing.getId())));
                    }
                }
            }
        }

        if (calculateTotalTokens(retained) > maxTokens) {
            retained = trimToBudget(retained, maxTokens);
        }

        return retained;
    }

    private List<TokenMessage> trimToBudget(List<TokenMessage> messages, int maxTokens) {
        List<TokenMessage> trimmed = new ArrayList<>(messages);
        while (calculateTotalTokens(trimmed) > maxTokens && !trimmed.isEmpty()) {
            trimmed.remove(0);
        }
        return trimmed;
    }

    private List<TokenMessage> sortMessages(List<TokenMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }
        return messages.stream().sorted(Comparator.comparing(TokenMessage::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder()))).collect(Collectors.toList());
    }

    private int calculateTotalTokens(List<TokenMessage> messages) {
        return messages.stream().mapToInt(this::getMessageTokens).sum();
    }

    private int getMessageTokens(TokenMessage message) {
        if (message == null) {
            return 0;
        }
        if (message.getBodyTokenCount() != null && message.getBodyTokenCount() > 0) {
            return message.getBodyTokenCount();
        }
        return message.getTokenCount() != null ? Math.max(message.getTokenCount(), 0) : 0;
    }

    private int getTriggerTokenThreshold(TokenOverflowConfig config) {
        int percent = config.getRecallTriggerThreshold() != null ? config.getRecallTriggerThreshold()
                : DEFAULT_TRIGGER_PERCENT;
        percent = Math.min(Math.max(percent, 1), 100);
        return Math.max(1, (int) Math.floor(getMaxTokens(config) * (percent / 100.0D)));
    }

    private int getRecentMessageBudget(TokenOverflowConfig config) {
        double ratio = config.getReserveRatio() != null ? config.getReserveRatio() : DEFAULT_RECENT_RATIO;
        ratio = Math.min(Math.max(ratio, 0D), 1D);
        int budget = (int) Math.floor(getMaxTokens(config) * ratio);
        if (budget >= getMaxTokens(config)) {
            return Math.max(1, getMaxTokens(config) - 1);
        }
        return Math.max(budget, 1);
    }

    private int getRecallTopK(TokenOverflowConfig config) {
        return config.getRecallTopK() != null ? Math.max(config.getRecallTopK(), 1) : DEFAULT_RECALL_TOP_K;
    }

    private int getMaxTokens(TokenOverflowConfig config) {
        return config.getMaxTokens() == null || config.getMaxTokens() <= 0 ? DEFAULT_MAX_TOKENS : config.getMaxTokens();
    }

    private TokenOverflowConfig mergeConfig(TokenOverflowConfig runtimeConfig) {
        TokenOverflowConfig effectiveConfig = new TokenOverflowConfig();
        TokenOverflowConfig source = runtimeConfig == null ? config : runtimeConfig;
        TokenOverflowConfig fallback = config == null ? new TokenOverflowConfig() : config;

        effectiveConfig.setStrategyType(source.getStrategyType() != null ? source.getStrategyType() : fallback.getStrategyType());
        effectiveConfig.setMaxTokens(source.getMaxTokens() != null ? source.getMaxTokens() : fallback.getMaxTokens());
        effectiveConfig.setReserveRatio(
                source.getReserveRatio() != null ? source.getReserveRatio() : fallback.getReserveRatio());
        effectiveConfig.setRecallQuery(source.getRecallQuery() != null ? source.getRecallQuery() : fallback.getRecallQuery());
        effectiveConfig.setUserId(source.getUserId() != null ? source.getUserId() : fallback.getUserId());
        effectiveConfig.setSessionId(source.getSessionId() != null ? source.getSessionId() : fallback.getSessionId());
        effectiveConfig.setRecallTopK(
                source.getRecallTopK() != null ? source.getRecallTopK() : fallback.getRecallTopK());
        effectiveConfig.setRecallMinScore(
                source.getRecallMinScore() != null ? source.getRecallMinScore() : fallback.getRecallMinScore());
        effectiveConfig.setRecallMaxCandidates(source.getRecallMaxCandidates() != null ? source.getRecallMaxCandidates()
                : fallback.getRecallMaxCandidates());
        effectiveConfig.setRecallTriggerThreshold(
                source.getRecallTriggerThreshold() != null ? source.getRecallTriggerThreshold()
                        : fallback.getRecallTriggerThreshold());
        effectiveConfig.setEnableRerank(
                source.getEnableRerank() != null ? source.getEnableRerank() : fallback.getEnableRerank());
        effectiveConfig.setProviderConfig(
                source.getProviderConfig() != null ? source.getProviderConfig() : fallback.getProviderConfig());
        return effectiveConfig;
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private record RecentSelection(List<TokenMessage> recallPool, List<TokenMessage> recentMessages, int recentTokens) {
    }
}
