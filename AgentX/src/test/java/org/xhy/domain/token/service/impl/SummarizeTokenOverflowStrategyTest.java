package org.xhy.domain.token.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xhy.domain.conversation.constant.Role;
import org.xhy.domain.token.model.TokenMessage;
import org.xhy.domain.token.model.TokenProcessResult;
import org.xhy.domain.token.model.config.TokenOverflowConfig;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SummarizeTokenOverflowStrategyTest {

    private TestableSummarizeTokenOverflowStrategy strategy;
    private TokenOverflowConfig config;
    private List<TokenMessage> messages;

    @BeforeEach
    void setUp() {
        config = TokenOverflowConfig.createSummaryConfig(1000, 80);
        config.setReserveRatio(0.3D);
        strategy = new TestableSummarizeTokenOverflowStrategy(config, "SUMMARY: compressed context");
        messages = createTestMessages(10, 150);
    }

    @Test
    void shouldSummarizeWhenTokenUsageExceedsThreshold() {
        TokenProcessResult result = strategy.process(messages, new TokenOverflowConfig());

        assertTrue(result.isProcessed());
        assertNotNull(result.getRetainedMessages());
        assertFalse(result.getRetainedMessages().isEmpty());
        assertEquals(Role.SUMMARY.name(), result.getRetainedMessages().get(0).getRole());
        assertEquals("SUMMARY: compressed context", result.getSummary());
        assertTrue(result.getTotalTokens() <= config.getMaxTokens());
        assertNotNull(result.getRetainedMessages().get(0).getId());
        assertTrue(strategy.getMessagesToSummarize().size() > 0);
    }

    @Test
    void shouldNotSummarizeWhenTokenUsageDoesNotReachThreshold() {
        List<TokenMessage> smallMessages = createTestMessages(3, 100);

        TokenProcessResult result = strategy.process(smallMessages, new TokenOverflowConfig());

        assertFalse(result.isProcessed());
        assertEquals(smallMessages.size(), result.getRetainedMessages().size());
        assertEquals(300, result.getTotalTokens());
    }

    @Test
    void shouldFallbackToLegacyMessageCountThreshold() {
        TokenOverflowConfig legacyConfig = TokenOverflowConfig.createSummaryConfig(1000, 200);
        legacyConfig.setReserveRatio(0.3D);
        TestableSummarizeTokenOverflowStrategy legacyStrategy = new TestableSummarizeTokenOverflowStrategy(legacyConfig,
                "SUMMARY: legacy mode");

        List<TokenMessage> smallMessages = createTestMessages(20, 20);
        TokenProcessResult noProcess = legacyStrategy.process(smallMessages, new TokenOverflowConfig());
        assertFalse(noProcess.isProcessed());

        List<TokenMessage> largeMessages = createTestMessages(220, 20);
        TokenProcessResult process = legacyStrategy.process(largeMessages, new TokenOverflowConfig());
        assertTrue(process.isProcessed());
        assertEquals(Role.SUMMARY.name(), process.getRetainedMessages().get(0).getRole());
    }

    @Test
    void shouldReturnOriginalMessagesForEmptyInput() {
        TokenProcessResult result = strategy.process(new ArrayList<>(), new TokenOverflowConfig());

        assertFalse(result.isProcessed());
        assertNotNull(result.getRetainedMessages());
        assertTrue(result.getRetainedMessages().isEmpty());
    }

    private List<TokenMessage> createTestMessages(int count, int tokensPerMessage) {
        List<TokenMessage> testMessages = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            TokenMessage message = new TokenMessage();
            message.setId(UUID.randomUUID().toString());
            message.setRole(i % 2 == 0 ? Role.USER.name() : Role.ASSISTANT.name());
            message.setContent("message-" + i);
            message.setTokenCount(tokensPerMessage);
            message.setBodyTokenCount(tokensPerMessage);
            message.setCreatedAt(LocalDateTime.now().minusMinutes(count - i));
            testMessages.add(message);
        }

        return testMessages;
    }

    private static class TestableSummarizeTokenOverflowStrategy extends SummarizeTokenOverflowStrategy {

        private final String summaryContent;

        private TestableSummarizeTokenOverflowStrategy(TokenOverflowConfig config, String summaryContent) {
            super(config);
            this.summaryContent = summaryContent;
        }

        @Override
        protected String generateSummaryContent(List<TokenMessage> messages, TokenOverflowConfig effectiveConfig,
                int summaryBudget) {
            return summaryContent;
        }
    }
}
