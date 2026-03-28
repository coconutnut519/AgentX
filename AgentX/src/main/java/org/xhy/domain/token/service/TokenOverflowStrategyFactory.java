package org.xhy.domain.token.service;

import org.springframework.stereotype.Service;
import org.xhy.domain.shared.enums.TokenOverflowStrategyEnum;
import org.xhy.domain.token.model.config.TokenOverflowConfig;
import org.xhy.domain.token.service.impl.NoTokenOverflowStrategy;
import org.xhy.domain.token.service.impl.RelevanceRecallTokenOverflowStrategy;
import org.xhy.domain.token.service.impl.SlidingWindowTokenOverflowStrategy;
import org.xhy.domain.token.service.impl.SummarizeTokenOverflowStrategy;
import org.xhy.domain.token.service.recall.ConversationRecallMonitoringService;
import org.xhy.domain.token.service.recall.ConversationMessageRecallService;

/** Factory for token overflow strategies. */
@Service
public class TokenOverflowStrategyFactory {

    private final ConversationMessageRecallService conversationMessageRecallService;
    private final ConversationRecallMonitoringService conversationRecallMonitoringService;

    public TokenOverflowStrategyFactory(ConversationMessageRecallService conversationMessageRecallService,
            ConversationRecallMonitoringService conversationRecallMonitoringService) {
        this.conversationMessageRecallService = conversationMessageRecallService;
        this.conversationRecallMonitoringService = conversationRecallMonitoringService;
    }

    public TokenOverflowStrategy createStrategy(TokenOverflowStrategyEnum strategyType, TokenOverflowConfig config) {
        if (strategyType == null) {
            return new NoTokenOverflowStrategy();
        }

        return switch (strategyType) {
            case SLIDING_WINDOW -> new SlidingWindowTokenOverflowStrategy(config);
            case SUMMARIZE -> new SummarizeTokenOverflowStrategy(config);
            case RELEVANCE_RECALL -> new RelevanceRecallTokenOverflowStrategy(config, conversationMessageRecallService,
                    conversationRecallMonitoringService);
            case NONE -> new NoTokenOverflowStrategy();
        };
    }

    public TokenOverflowStrategy createStrategy(String strategyName, TokenOverflowConfig config) {
        return createStrategy(TokenOverflowStrategyEnum.fromString(strategyName), config);
    }

    public TokenOverflowStrategy createStrategy(TokenOverflowConfig config) {
        if (config == null) {
            return new NoTokenOverflowStrategy();
        }
        return createStrategy(config.getStrategyType(), config);
    }
}
