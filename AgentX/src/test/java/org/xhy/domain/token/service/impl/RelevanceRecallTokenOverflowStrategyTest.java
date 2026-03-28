package org.xhy.domain.token.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xhy.domain.conversation.constant.Role;
import org.xhy.domain.token.model.TokenMessage;
import org.xhy.domain.token.model.TokenProcessResult;
import org.xhy.domain.token.model.config.TokenOverflowConfig;
import org.xhy.domain.token.service.recall.LexicalConversationMessageRecallService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelevanceRecallTokenOverflowStrategyTest {

    private TokenOverflowConfig config;
    private RelevanceRecallTokenOverflowStrategy strategy;

    @BeforeEach
    void setUp() {
        config = TokenOverflowConfig.createRelevanceRecallConfig(1000, 80, 2, 0.15D);
        config.setReserveRatio(0.4D);
        config.setRecallQuery("refund api timeout");
        strategy = new RelevanceRecallTokenOverflowStrategy(config, new LexicalConversationMessageRecallService());
    }

    @Test
    void shouldRecallRelevantOlderMessageAndKeepRecentMessages() {
        List<TokenMessage> messages = new ArrayList<>();
        messages.add(createMessage("refund-old", "customer asked for refund because api timeout happened yesterday", 300,
                LocalDateTime.now().minusMinutes(6)));
        messages.add(createMessage("plan-old", "roadmap planning for onboarding flow", 250,
                LocalDateTime.now().minusMinutes(5)));
        messages.add(createMessage("recent-1", "latest assistant reply with deployment status", 160,
                LocalDateTime.now().minusMinutes(2)));
        messages.add(createMessage("recent-2", "user asks to continue debugging production issue", 160,
                LocalDateTime.now().minusMinutes(1)));

        TokenProcessResult result = strategy.process(messages, config);

        assertTrue(result.isProcessed());
        assertTrue(result.getRetainedMessages().stream().anyMatch(message -> "refund-old".equals(message.getId())));
        assertTrue(result.getRetainedMessages().stream().anyMatch(message -> "recent-1".equals(message.getId())));
        assertTrue(result.getRetainedMessages().stream().anyMatch(message -> "recent-2".equals(message.getId())));
        assertTrue(result.getTotalTokens() <= config.getMaxTokens());
        assertEquals("RELEVANCE_RECALL", result.getStrategyName());
    }

    @Test
    void shouldNotProcessWhenBelowRecallThreshold() {
        List<TokenMessage> messages = List.of(
                createMessage("m1", "short message about billing", 120, LocalDateTime.now().minusMinutes(2)),
                createMessage("m2", "another short message", 120, LocalDateTime.now().minusMinutes(1)));

        TokenProcessResult result = strategy.process(messages, config);

        assertFalse(result.isProcessed());
        assertEquals(2, result.getRetainedMessages().size());
    }

    @Test
    void shouldFallbackToSlidingWindowWhenQueryMissing() {
        TokenOverflowConfig noQueryConfig = TokenOverflowConfig.createRelevanceRecallConfig(600, 70, 2, 0.15D);
        noQueryConfig.setReserveRatio(0.3D);
        RelevanceRecallTokenOverflowStrategy noQueryStrategy = new RelevanceRecallTokenOverflowStrategy(noQueryConfig,
                new LexicalConversationMessageRecallService());

        List<TokenMessage> messages = List.of(
                createMessage("m1", "alpha", 250, LocalDateTime.now().minusMinutes(3)),
                createMessage("m2", "beta", 250, LocalDateTime.now().minusMinutes(2)),
                createMessage("m3", "gamma", 250, LocalDateTime.now().minusMinutes(1)));

        TokenProcessResult result = noQueryStrategy.process(messages, noQueryConfig);

        assertTrue(result.isProcessed());
        assertTrue(result.getStrategyName().contains("FALLBACK_SLIDING_WINDOW"));
        assertTrue(result.getTotalTokens() <= noQueryConfig.getMaxTokens());
    }

    private TokenMessage createMessage(String id, String content, int tokens, LocalDateTime createdAt) {
        TokenMessage message = new TokenMessage();
        message.setId(id != null ? id : UUID.randomUUID().toString());
        message.setRole(Role.USER.name());
        message.setContent(content);
        message.setTokenCount(tokens);
        message.setBodyTokenCount(tokens);
        message.setCreatedAt(createdAt);
        return message;
    }
}
