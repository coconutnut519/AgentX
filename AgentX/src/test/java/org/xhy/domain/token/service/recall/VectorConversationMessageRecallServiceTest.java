package org.xhy.domain.token.service.recall;

import org.junit.jupiter.api.Test;
import org.xhy.domain.conversation.constant.Role;
import org.xhy.domain.conversation.model.ConversationRecallResult;
import org.xhy.domain.conversation.service.ConversationVectorRecallDomainService;
import org.xhy.domain.rag.service.RerankDomainService;
import org.xhy.domain.token.model.TokenMessage;
import org.xhy.domain.token.model.config.TokenOverflowConfig;
import org.xhy.domain.token.model.recall.RecallCandidate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorConversationMessageRecallServiceTest {

    @Test
    void shouldUseVectorResultsWhenMessageIdsMatchRecallPool() {
        ConversationVectorRecallDomainService vectorDomainService = mock(ConversationVectorRecallDomainService.class);
        when(vectorDomainService.searchRelevant(eq("user-1"), eq("session-1"), eq("refund api timeout"), eq(8),
                eq(0.2D))).thenReturn(List.of(new ConversationRecallResult("refund-old", 0.91D)));

        VectorConversationMessageRecallService service = new VectorConversationMessageRecallService(vectorDomainService,
                (RerankDomainService) null);
        TokenOverflowConfig config = createConfig("user-1", "session-1", "refund api timeout");
        config.setRecallMaxCandidates(8);
        config.setRecallMinScore(0.2D);

        List<RecallCandidate> results = service.recall(List.of(
                createMessage("refund-old", "customer refund blocked by api timeout", Role.USER.name(),
                        LocalDateTime.now().minusMinutes(3)),
                createMessage("refund-answer", "assistant investigated the refund timeout stack", Role.ASSISTANT.name(),
                        LocalDateTime.now().minusMinutes(2)),
                createMessage("other-old", "roadmap review for signup flow", Role.USER.name(),
                        LocalDateTime.now().minusMinutes(1))), config);

        assertEquals(1, results.size());
        assertEquals(List.of("refund-old", "refund-answer"),
                results.get(0).messages().stream().map(TokenMessage::getId).toList());
        assertEquals(0.91D, results.get(0).score());
        verify(vectorDomainService).searchRelevant("user-1", "session-1", "refund api timeout", 8, 0.2D);
    }

    @Test
    void shouldFallbackToLexicalRecallWhenVectorStoreReturnsEmpty() {
        ConversationVectorRecallDomainService vectorDomainService = mock(ConversationVectorRecallDomainService.class);
        when(vectorDomainService.searchRelevant(eq("user-1"), eq("session-1"), eq("refund api timeout"), eq(20),
                eq(0.15D))).thenReturn(List.of());

        VectorConversationMessageRecallService service = new VectorConversationMessageRecallService(vectorDomainService,
                (RerankDomainService) null);
        TokenOverflowConfig config = createConfig("user-1", "session-1", "refund api timeout");

        List<RecallCandidate> results = service.recall(List.of(
                createMessage("refund-old", "customer refund blocked by api timeout", Role.USER.name(),
                        LocalDateTime.now().minusMinutes(3)),
                createMessage("other-old", "roadmap review for signup flow", Role.USER.name(),
                        LocalDateTime.now().minusMinutes(2))), config);

        assertEquals(1, results.size());
        assertEquals("refund-old", results.get(0).messages().get(0).getId());
        assertTrue(results.get(0).score() > 0D);
    }

    @Test
    void shouldApplyRerankToTurnGroupsWhenEnabled() {
        ConversationVectorRecallDomainService vectorDomainService = mock(ConversationVectorRecallDomainService.class);
        RerankDomainService rerankDomainService = mock(RerankDomainService.class);
        when(vectorDomainService.searchRelevant(eq("user-1"), eq("session-1"), eq("refund api timeout"), eq(20),
                eq(0.15D))).thenReturn(List.of(
                        new ConversationRecallResult("billing-user", 0.95D),
                        new ConversationRecallResult("refund-user", 0.82D)));
        when(rerankDomainService.rerank(org.mockito.ArgumentMatchers.anyList(), eq("refund api timeout")))
                .thenReturn(List.of(1, 0));

        VectorConversationMessageRecallService service = new VectorConversationMessageRecallService(vectorDomainService,
                rerankDomainService);
        TokenOverflowConfig config = createConfig("user-1", "session-1", "refund api timeout");
        config.setEnableRerank(Boolean.TRUE);

        List<RecallCandidate> results = service.recall(List.of(
                createMessage("billing-user", "billing page is slow", Role.USER.name(),
                        LocalDateTime.now().minusMinutes(4)),
                createMessage("billing-ai", "assistant checked billing trace", Role.ASSISTANT.name(),
                        LocalDateTime.now().minusMinutes(3)),
                createMessage("refund-user", "refund api timeout blocks checkout", Role.USER.name(),
                        LocalDateTime.now().minusMinutes(2)),
                createMessage("refund-ai", "assistant found refund timeout root cause", Role.ASSISTANT.name(),
                        LocalDateTime.now().minusMinutes(1))), config);

        assertEquals(2, results.size());
        assertEquals("refund-user", results.get(0).messages().get(0).getId());
        verify(rerankDomainService).rerank(org.mockito.ArgumentMatchers.anyList(), eq("refund api timeout"));
    }

    private TokenOverflowConfig createConfig(String userId, String sessionId, String query) {
        TokenOverflowConfig config = TokenOverflowConfig.createRelevanceRecallConfig(1000, 80, 4, 0.15D);
        config.setUserId(userId);
        config.setSessionId(sessionId);
        config.setRecallQuery(query);
        return config;
    }

    private TokenMessage createMessage(String id, String content, String role, LocalDateTime createdAt) {
        TokenMessage message = new TokenMessage();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        message.setTokenCount(120);
        message.setBodyTokenCount(120);
        message.setCreatedAt(createdAt);
        return message;
    }
}
