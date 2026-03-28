package org.xhy.domain.token.service.recall;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.xhy.domain.conversation.model.ConversationRecallResult;
import org.xhy.domain.conversation.service.ConversationVectorRecallDomainService;
import org.xhy.domain.rag.service.RerankDomainService;
import org.xhy.domain.token.model.TokenMessage;
import org.xhy.domain.token.model.config.TokenOverflowConfig;
import org.xhy.domain.token.model.recall.RecallCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Vector-backed recall service with lexical fallback. */
@Service
public class VectorConversationMessageRecallService implements ConversationMessageRecallService {

    private final ConversationVectorRecallDomainService conversationVectorRecallDomainService;
    private final RerankDomainService rerankDomainService;
    private final ConversationRecallMonitoringService monitoringService;
    private final LexicalConversationMessageRecallService lexicalFallback = new LexicalConversationMessageRecallService();

    @Autowired
    public VectorConversationMessageRecallService(
            ConversationVectorRecallDomainService conversationVectorRecallDomainService,
            ObjectProvider<RerankDomainService> rerankDomainServiceProvider,
            ObjectProvider<ConversationRecallMonitoringService> monitoringServiceProvider) {
        this.conversationVectorRecallDomainService = conversationVectorRecallDomainService;
        this.rerankDomainService = rerankDomainServiceProvider.getIfAvailable();
        this.monitoringService = monitoringServiceProvider.getIfAvailable();
    }

    VectorConversationMessageRecallService(ConversationVectorRecallDomainService conversationVectorRecallDomainService,
            RerankDomainService rerankDomainService) {
        this(conversationVectorRecallDomainService, rerankDomainService, null);
    }

    VectorConversationMessageRecallService(ConversationVectorRecallDomainService conversationVectorRecallDomainService,
            RerankDomainService rerankDomainService, ConversationRecallMonitoringService monitoringService) {
        this.conversationVectorRecallDomainService = conversationVectorRecallDomainService;
        this.rerankDomainService = rerankDomainService;
        this.monitoringService = monitoringService;
    }

    @Override
    public List<RecallCandidate> recall(List<TokenMessage> candidates, TokenOverflowConfig config) {
        if (candidates == null || candidates.isEmpty() || config == null) {
            return List.of();
        }

        if (isBlank(config.getUserId()) || isBlank(config.getSessionId()) || isBlank(config.getRecallQuery())) {
            return lexicalFallback.recall(candidates, config);
        }

        Map<String, TurnGroup> turnByMessageId = buildTurnGroups(candidates);
        if (turnByMessageId.isEmpty()) {
            return List.of();
        }

        long vectorSearchStartedAt = System.currentTimeMillis();
        List<ConversationRecallResult> vectorResults = conversationVectorRecallDomainService.searchRelevant(
                config.getUserId(), config.getSessionId(), config.getRecallQuery(), config.getRecallMaxCandidates(),
                config.getRecallMinScore());
        if (monitoringService != null) {
            monitoringService.recordVectorSearch(config, normalizeMaxCandidates(config),
                    normalizeMinScore(config), vectorResults.size(), System.currentTimeMillis() - vectorSearchStartedAt,
                    true);
        }

        List<RecallCandidate> recalled = mergeTurnResults(vectorResults, turnByMessageId, config);

        if (!recalled.isEmpty()) {
            return recalled;
        }
        return lexicalFallback.recall(candidates, config);
    }

    private List<RecallCandidate> mergeTurnResults(List<ConversationRecallResult> vectorResults,
            Map<String, TurnGroup> turnByMessageId, TokenOverflowConfig config) {
        if (vectorResults == null || vectorResults.isEmpty()) {
            return List.of();
        }

        Map<String, RecallCandidate> turnCandidates = new LinkedHashMap<>();
        for (ConversationRecallResult result : vectorResults) {
            if (result == null || isBlank(result.messageId())) {
                continue;
            }
            TurnGroup turnGroup = turnByMessageId.get(result.messageId());
            if (turnGroup == null) {
                continue;
            }
            turnCandidates.merge(turnGroup.id(), new RecallCandidate(turnGroup.messages(), result.score()),
                    (left, right) -> left.score() >= right.score() ? left : right);
        }

        List<RecallCandidate> recalled = new ArrayList<>(turnCandidates.values());
        recalled.sort(Comparator.comparingDouble(RecallCandidate::score).reversed()
                .thenComparing(RecallCandidate::firstCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));

        if (Boolean.TRUE.equals(config.getEnableRerank()) && rerankDomainService != null && recalled.size() > 1) {
            return rerankCandidates(recalled, config);
        }
        return recalled;
    }

    private List<RecallCandidate> rerankCandidates(List<RecallCandidate> recalled, TokenOverflowConfig config) {
        long rerankStartedAt = System.currentTimeMillis();
        List<String> documents = recalled.stream().map(this::joinTurnText).toList();
        List<Integer> rerankedIndices = rerankDomainService.rerank(documents, config.getRecallQuery());
        if (rerankedIndices == null || rerankedIndices.isEmpty()) {
            if (monitoringService != null) {
                monitoringService.recordRerank(config, recalled.size(),
                        System.currentTimeMillis() - rerankStartedAt, false);
            }
            return recalled;
        }

        List<RecallCandidate> reordered = new ArrayList<>();
        for (Integer index : rerankedIndices) {
            if (index != null && index >= 0 && index < recalled.size()) {
                reordered.add(recalled.get(index));
            }
        }

        if (reordered.size() != recalled.size()) {
            for (RecallCandidate candidate : recalled) {
                if (!reordered.contains(candidate)) {
                    reordered.add(candidate);
                }
            }
        }
        if (monitoringService != null) {
            monitoringService.recordRerank(config, recalled.size(),
                    System.currentTimeMillis() - rerankStartedAt, true);
        }
        return reordered;
    }

    private Map<String, TurnGroup> buildTurnGroups(List<TokenMessage> candidates) {
        List<TokenMessage> sorted = candidates.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(TokenMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        Map<String, TurnGroup> turnByMessageId = new LinkedHashMap<>();
        List<TokenMessage> currentTurn = new ArrayList<>();
        int turnIndex = 0;

        for (TokenMessage candidate : sorted) {
            if (shouldStartNewTurn(currentTurn, candidate)) {
                registerTurn(turnByMessageId, currentTurn, turnIndex++);
                currentTurn = new ArrayList<>();
            }
            currentTurn.add(candidate);
        }
        registerTurn(turnByMessageId, currentTurn, turnIndex);
        return turnByMessageId;
    }

    private boolean shouldStartNewTurn(List<TokenMessage> currentTurn, TokenMessage candidate) {
        if (candidate == null || currentTurn == null || currentTurn.isEmpty()) {
            return false;
        }

        String role = candidate.getRole();
        if (isBoundaryRole(role)) {
            return true;
        }

        TokenMessage last = currentTurn.get(currentTurn.size() - 1);
        return isStandaloneRole(last.getRole());
    }

    private void registerTurn(Map<String, TurnGroup> turnByMessageId, List<TokenMessage> turnMessages, int turnIndex) {
        if (turnMessages == null || turnMessages.isEmpty()) {
            return;
        }

        List<TokenMessage> snapshot = List.copyOf(turnMessages);
        String turnId = resolveTurnId(snapshot, turnIndex);
        TurnGroup turnGroup = new TurnGroup(turnId, snapshot);
        for (TokenMessage message : snapshot) {
            if (message != null && !isBlank(message.getId())) {
                turnByMessageId.put(message.getId(), turnGroup);
            }
        }
    }

    private String resolveTurnId(List<TokenMessage> turnMessages, int turnIndex) {
        for (TokenMessage message : turnMessages) {
            if (message != null && !isBlank(message.getRole()) && "USER".equals(message.getRole())
                    && !isBlank(message.getId())) {
                return message.getId();
            }
        }
        for (TokenMessage message : turnMessages) {
            if (message != null && !isBlank(message.getId())) {
                return message.getId();
            }
        }
        return "turn-" + turnIndex;
    }

    private boolean isBoundaryRole(String role) {
        return "USER".equals(role) || "SUMMARY".equals(role) || "SYSTEM".equals(role);
    }

    private boolean isStandaloneRole(String role) {
        return "SUMMARY".equals(role) || "SYSTEM".equals(role);
    }

    private String joinTurnText(RecallCandidate candidate) {
        StringBuilder builder = new StringBuilder();
        for (TokenMessage message : candidate.messages()) {
            if (message == null || isBlank(message.getContent())) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(message.getRole() != null ? message.getRole() : "MESSAGE").append(": ")
                    .append(message.getContent());
        }
        return builder.toString();
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private int normalizeMaxCandidates(TokenOverflowConfig config) {
        if (config == null || config.getRecallMaxCandidates() == null) {
            return 20;
        }
        return Math.max(config.getRecallMaxCandidates(), 1);
    }

    private double normalizeMinScore(TokenOverflowConfig config) {
        if (config == null || config.getRecallMinScore() == null) {
            return 0.15D;
        }
        return Math.max(0D, Math.min(1D, config.getRecallMinScore()));
    }

    private record TurnGroup(String id, List<TokenMessage> messages) {
    }
}
