package org.xhy.domain.token.service.impl;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.xhy.application.conversation.service.handler.context.AgentPromptTemplates;
import org.xhy.domain.conversation.constant.Role;
import org.xhy.domain.shared.enums.TokenOverflowStrategyEnum;
import org.xhy.domain.token.model.TokenMessage;
import org.xhy.domain.token.model.TokenProcessResult;
import org.xhy.domain.token.model.config.TokenOverflowConfig;
import org.xhy.domain.token.service.TokenOverflowStrategy;
import org.xhy.infrastructure.llm.LLMProviderService;
import org.xhy.infrastructure.llm.config.ProviderConfig;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Token overflow strategy that summarizes old messages while keeping recent turns. */
public class SummarizeTokenOverflowStrategy implements TokenOverflowStrategy {

    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final int DEFAULT_TRIGGER_PERCENT = 80;
    private static final double DEFAULT_RECENT_RATIO = 0.2D;
    private static final int MIN_SUMMARY_BUDGET = 128;
    private static final int LEGACY_MESSAGE_COUNT_THRESHOLD = 100;
    private static final double APPROX_CHARS_PER_TOKEN = 2.0D;

    private final TokenOverflowConfig config;

    private List<TokenMessage> messagesToSummarize = new ArrayList<>();
    private TokenMessage summaryMessage;

    public SummarizeTokenOverflowStrategy(TokenOverflowConfig config) {
        this.config = config;
    }

    @Override
    public TokenProcessResult process(List<TokenMessage> messages, TokenOverflowConfig tokenOverflowConfig) {
        TokenOverflowConfig effectiveConfig = mergeConfig(tokenOverflowConfig);
        List<TokenMessage> sortedMessages = sortMessages(messages);

        if (!needsProcessing(sortedMessages, effectiveConfig)) {
            return buildUnprocessedResult(sortedMessages);
        }

        RecentMessageSelection selection = selectRecentMessages(sortedMessages, effectiveConfig);
        this.messagesToSummarize = new ArrayList<>(selection.messagesToSummarize());

        if (this.messagesToSummarize.isEmpty()) {
            return buildUnprocessedResult(sortedMessages);
        }

        int maxTokens = getMaxTokens(effectiveConfig);
        int summaryBudget = Math.max(1, maxTokens - selection.recentTokens());
        String summaryContent = generateSummaryContent(this.messagesToSummarize, effectiveConfig, summaryBudget);
        int summaryTokens = clampSummaryTokens(summaryContent, summaryBudget);

        TokenMessage newSummary = createSummaryMessage(summaryContent, summaryTokens, sortedMessages);
        List<TokenMessage> retainedMessages = new ArrayList<>();
        retainedMessages.add(newSummary);
        retainedMessages.addAll(selection.retainedRecentMessages());

        retainedMessages = shrinkToBudget(retainedMessages, maxTokens);
        this.summaryMessage = retainedMessages.get(0);

        TokenProcessResult result = new TokenProcessResult();
        result.setRetainedMessages(retainedMessages);
        result.setSummary(this.summaryMessage.getContent());
        result.setStrategyName(getName());
        result.setProcessed(true);
        result.setTotalTokens(calculateTotalTokens(retainedMessages));
        return result;
    }

    @Override
    public String getName() {
        return TokenOverflowStrategyEnum.SUMMARIZE.name();
    }

    @Override
    public boolean needsProcessing(List<TokenMessage> messages) {
        return needsProcessing(sortMessages(messages), mergeConfig(null));
    }

    public List<TokenMessage> getMessagesToSummarize() {
        return messagesToSummarize;
    }

    public TokenMessage getSummaryMessage() {
        return summaryMessage;
    }

    protected String generateSummaryContent(List<TokenMessage> messages, TokenOverflowConfig effectiveConfig,
            int summaryBudget) {
        ProviderConfig providerConfig = effectiveConfig.getProviderConfig();
        if (providerConfig == null) {
            throw new IllegalArgumentException("ProviderConfig is required when summary generation is triggered");
        }

        String summaryPrefixPrompt = "最后请使用以下前缀输出摘要：" + AgentPromptTemplates.getSummaryPrefix();
        String systemPrompt = "你是一个对话压缩器。请严格遵守以下规则：\n"
                + "1. 只能基于给定对话生成摘要，不得杜撰。\n"
                + "2. 保留用户目标、约束、关键事实、未完成事项、重要结论。\n"
                + "3. 删除寒暄、重复表达和无关细节。\n"
                + "4. 如输入中已包含旧摘要，必须继承旧摘要中的有效事实。\n"
                + "5. 输出尽量控制在 " + summaryBudget + " tokens 以内。\n"
                + summaryPrefixPrompt;

        ChatModel chatLanguageModel = LLMProviderService.getStrand(providerConfig.getProtocol(), providerConfig);
        SystemMessage systemMessage = new SystemMessage(systemPrompt);
        List<Content> contents = messages.stream().map(TokenMessage::getContent).filter(Objects::nonNull)
                .map(TextContent::new).collect(Collectors.toList());
        UserMessage userMessage = new UserMessage(contents);
        ChatResponse chatResponse = chatLanguageModel.chat(List.of(systemMessage, userMessage));

        String summaryContent = chatResponse.aiMessage() != null ? chatResponse.aiMessage().text() : "";
        int modelReportedTokens = extractOutputTokens(chatResponse);
        if (modelReportedTokens > 0 && modelReportedTokens <= summaryBudget) {
            return summaryContent;
        }

        return truncateSummaryToBudget(summaryContent, summaryBudget);
    }

    private boolean needsProcessing(List<TokenMessage> messages, TokenOverflowConfig effectiveConfig) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }

        if (usesLegacyMessageCountThreshold(effectiveConfig)) {
            return messages.size() > effectiveConfig.getSummaryThreshold();
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

    private List<TokenMessage> sortMessages(List<TokenMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }

        return messages.stream().sorted(Comparator.comparing(TokenMessage::getCreatedAt)).collect(Collectors.toList());
    }

    private RecentMessageSelection selectRecentMessages(List<TokenMessage> sortedMessages, TokenOverflowConfig config) {
        int recentBudget = getRecentMessageBudget(config);
        List<TokenMessage> retainedRecentMessages = new ArrayList<>();
        int recentTokens = 0;

        for (int i = sortedMessages.size() - 1; i >= 0; i--) {
            TokenMessage message = sortedMessages.get(i);
            int messageTokens = getMessageTokens(message);
            if (recentBudget > 0 && (retainedRecentMessages.isEmpty() || recentTokens + messageTokens <= recentBudget)) {
                retainedRecentMessages.add(0, message);
                recentTokens += messageTokens;
                continue;
            }
            break;
        }

        int splitIndex = sortedMessages.size() - retainedRecentMessages.size();
        List<TokenMessage> summarizeCandidates = new ArrayList<>(sortedMessages.subList(0, splitIndex));

        if (summarizeCandidates.isEmpty() && sortedMessages.size() > 1) {
            TokenMessage oldestRetainedMessage = retainedRecentMessages.remove(0);
            summarizeCandidates.add(oldestRetainedMessage);
            recentTokens -= getMessageTokens(oldestRetainedMessage);
        }

        return new RecentMessageSelection(summarizeCandidates, retainedRecentMessages, Math.max(recentTokens, 0));
    }

    private List<TokenMessage> shrinkToBudget(List<TokenMessage> retainedMessages, int maxTokens) {
        List<TokenMessage> result = new ArrayList<>(retainedMessages);

        while (calculateTotalTokens(result) > maxTokens && result.size() > 1) {
            result.remove(1);
        }

        if (calculateTotalTokens(result) > maxTokens && !result.isEmpty()) {
            TokenMessage summary = result.get(0);
            String truncatedSummary = truncateSummaryToBudget(summary.getContent(), maxTokens);
            int truncatedTokens = estimateTokenCount(truncatedSummary);
            summary.setContent(truncatedSummary);
            summary.setBodyTokenCount(truncatedTokens);
            summary.setTokenCount(truncatedTokens);
        }

        return result;
    }

    private TokenMessage createSummaryMessage(String summaryContent, int summaryTokens, List<TokenMessage> historyMessages) {
        TokenMessage newSummaryMessage = new TokenMessage();
        newSummaryMessage.setId(UUID.randomUUID().toString());
        newSummaryMessage.setRole(Role.SUMMARY.name());
        newSummaryMessage.setContent(summaryContent);
        newSummaryMessage.setBodyTokenCount(summaryTokens);
        newSummaryMessage.setTokenCount(summaryTokens);
        newSummaryMessage.setCreatedAt(resolveSummaryCreatedAt(historyMessages));
        return newSummaryMessage;
    }

    private LocalDateTime resolveSummaryCreatedAt(List<TokenMessage> historyMessages) {
        LocalDateTime earliestTime = historyMessages.stream()
                .filter(message -> !Role.SUMMARY.name().equals(message.getRole())).map(TokenMessage::getCreatedAt)
                .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(LocalDateTime.now());
        return earliestTime.minusSeconds(1);
    }

    private int calculateTotalTokens(List<TokenMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        return messages.stream().mapToInt(this::getMessageTokens).sum();
    }

    private int getMessageTokens(TokenMessage message) {
        if (message == null) {
            return 0;
        }

        Integer bodyTokenCount = message.getBodyTokenCount();
        if (bodyTokenCount != null && bodyTokenCount > 0) {
            return bodyTokenCount;
        }

        Integer tokenCount = message.getTokenCount();
        return tokenCount != null ? Math.max(tokenCount, 0) : 0;
    }

    private int getTriggerTokenThreshold(TokenOverflowConfig config) {
        int maxTokens = getMaxTokens(config);
        Integer summaryThreshold = config.getSummaryThreshold();
        int percent = summaryThreshold == null ? DEFAULT_TRIGGER_PERCENT : Math.min(Math.max(summaryThreshold, 1), 100);
        return Math.max(1, (int) Math.floor(maxTokens * (percent / 100.0D)));
    }

    private int getRecentMessageBudget(TokenOverflowConfig config) {
        int maxTokens = getMaxTokens(config);
        double reserveRatio = config.getReserveRatio() == null ? DEFAULT_RECENT_RATIO : config.getReserveRatio();
        double safeRatio = Math.min(Math.max(reserveRatio, 0D), 1D);
        int recentBudget = (int) Math.floor(maxTokens * safeRatio);

        if (recentBudget >= maxTokens) {
            return Math.max(0, maxTokens - MIN_SUMMARY_BUDGET);
        }
        return recentBudget;
    }

    private int getMaxTokens(TokenOverflowConfig config) {
        return config.getMaxTokens() == null || config.getMaxTokens() <= 0 ? DEFAULT_MAX_TOKENS : config.getMaxTokens();
    }

    private boolean usesLegacyMessageCountThreshold(TokenOverflowConfig config) {
        Integer summaryThreshold = config.getSummaryThreshold();
        return summaryThreshold != null && summaryThreshold > LEGACY_MESSAGE_COUNT_THRESHOLD;
    }

    private int clampSummaryTokens(String summaryContent, int summaryBudget) {
        return Math.min(summaryBudget, Math.max(1, estimateTokenCount(summaryContent)));
    }

    private int estimateTokenCount(String content) {
        if (content == null || content.isBlank()) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(content.length() / APPROX_CHARS_PER_TOKEN));
    }

    private int extractOutputTokens(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.tokenUsage() == null || chatResponse.tokenUsage().outputTokenCount() == null) {
            return 0;
        }
        return chatResponse.tokenUsage().outputTokenCount();
    }

    private String truncateSummaryToBudget(String content, int tokenBudget) {
        if (content == null || content.isBlank()) {
            return AgentPromptTemplates.getSummaryPrefix();
        }

        int safeTokenBudget = Math.max(1, tokenBudget);
        int currentEstimatedTokens = estimateTokenCount(content);
        if (currentEstimatedTokens <= safeTokenBudget) {
            return content;
        }

        int charLimit = Math.max(8, (int) Math.floor(safeTokenBudget * APPROX_CHARS_PER_TOKEN));
        String truncated = content.substring(0, Math.min(charLimit, content.length())).trim();
        if (truncated.isEmpty()) {
            return AgentPromptTemplates.getSummaryPrefix();
        }

        if (truncated.length() < content.length()) {
            return truncated + "...";
        }
        return truncated;
    }

    private TokenOverflowConfig mergeConfig(TokenOverflowConfig runtimeConfig) {
        TokenOverflowConfig effectiveConfig = new TokenOverflowConfig();
        TokenOverflowConfig source = runtimeConfig == null ? config : runtimeConfig;
        TokenOverflowConfig fallback = config == null ? new TokenOverflowConfig() : config;

        effectiveConfig.setStrategyType(source.getStrategyType() != null ? source.getStrategyType() : fallback.getStrategyType());
        effectiveConfig.setMaxTokens(source.getMaxTokens() != null ? source.getMaxTokens() : fallback.getMaxTokens());
        effectiveConfig.setReserveRatio(
                source.getReserveRatio() != null ? source.getReserveRatio() : fallback.getReserveRatio());
        effectiveConfig.setSummaryThreshold(
                source.getSummaryThreshold() != null ? source.getSummaryThreshold() : fallback.getSummaryThreshold());
        effectiveConfig.setProviderConfig(
                source.getProviderConfig() != null ? source.getProviderConfig() : fallback.getProviderConfig());
        return effectiveConfig;
    }

    private record RecentMessageSelection(List<TokenMessage> messagesToSummarize,
            List<TokenMessage> retainedRecentMessages, int recentTokens) {
    }
}
