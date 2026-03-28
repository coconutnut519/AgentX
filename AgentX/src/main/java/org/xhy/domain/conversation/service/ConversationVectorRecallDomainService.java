package org.xhy.domain.conversation.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.xhy.domain.conversation.constant.ConversationRecallMetadataConstant;
import org.xhy.domain.conversation.constant.MessageType;
import org.xhy.domain.conversation.model.ConversationRecallResult;
import org.xhy.domain.conversation.model.MessageEntity;
import org.xhy.infrastructure.exception.BusinessException;
import org.xhy.infrastructure.rag.factory.EmbeddingModelFactory;
import org.xhy.infrastructure.rag.service.UserModelConfigResolver;
import org.xhy.domain.token.service.recall.ConversationRecallMonitoringService;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Handles conversation-message indexing and vector recall search. */
@Service
public class ConversationVectorRecallDomainService implements ConversationRecallMetadataConstant {

    private static final Logger log = LoggerFactory.getLogger(ConversationVectorRecallDomainService.class);

    private static final int DEFAULT_MAX_CANDIDATES = 20;
    private static final double DEFAULT_MIN_SCORE = 0.15D;

    private final EmbeddingModelFactory embeddingModelFactory;
    private final UserModelConfigResolver userModelConfigResolver;
    private final EmbeddingStore<TextSegment> conversationEmbeddingStore;
    private final ConversationRecallMonitoringService monitoringService;

    public ConversationVectorRecallDomainService(EmbeddingModelFactory embeddingModelFactory,
            UserModelConfigResolver userModelConfigResolver,
            @Qualifier("conversationEmbeddingStore") EmbeddingStore<TextSegment> conversationEmbeddingStore,
            ConversationRecallMonitoringService monitoringService) {
        this.embeddingModelFactory = embeddingModelFactory;
        this.userModelConfigResolver = userModelConfigResolver;
        this.conversationEmbeddingStore = conversationEmbeddingStore;
        this.monitoringService = monitoringService;
    }

    public void upsertMessage(String userId, String sessionId, MessageEntity message) {
        if (isBlank(userId) || isBlank(sessionId) || !shouldIndex(message)) {
            return;
        }

        long startedAt = System.currentTimeMillis();
        try {
            removeMessage(message.getId());

            EmbeddingModelFactory.EmbeddingConfig embeddingConfig = buildEmbeddingConfig(userId);
            TextSegment segment = new TextSegment(message.getContent().trim(), buildMetadata(userId, sessionId, message));
            Embedding embedding = embeddingModelFactory.createEmbeddingModel(embeddingConfig).embed(segment).content();
            conversationEmbeddingStore.add(embedding, segment);
            if (monitoringService != null) {
                monitoringService.recordIndex(userId, sessionId, message.getId(), true,
                        System.currentTimeMillis() - startedAt);
            }
        } catch (Exception e) {
            if (monitoringService != null) {
                monitoringService.recordIndex(userId, sessionId, message != null ? message.getId() : null, false,
                        System.currentTimeMillis() - startedAt);
            }
            throw new BusinessException("会话消息向量索引失败: " + e.getMessage(), e);
        }
    }

    public List<ConversationRecallResult> searchRelevant(String userId, String sessionId, String query,
            Integer maxCandidates, Double minScore) {
        if (isBlank(userId) || isBlank(sessionId) || isBlank(query)) {
            return List.of();
        }

        try {
            EmbeddingModelFactory.EmbeddingConfig embeddingConfig = buildEmbeddingConfig(userId);
            Embedding queryEmbedding = embeddingModelFactory.createEmbeddingModel(embeddingConfig).embed(query).content();

            EmbeddingSearchResult<TextSegment> searchResult = conversationEmbeddingStore.search(EmbeddingSearchRequest
                    .builder().filter(new IsEqualTo(SESSION_ID, sessionId))
                    .maxResults(normalizeMaxCandidates(maxCandidates))
                    .minScore(normalizeMinScore(minScore)).queryEmbedding(queryEmbedding).build());

            List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
            if (matches == null || matches.isEmpty()) {
                return List.of();
            }

            Map<String, Double> deduplicated = new LinkedHashMap<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                if (match == null || match.embedded() == null || match.embedded().metadata() == null) {
                    continue;
                }
                Object metadataMessageId = match.embedded().metadata().toMap().get(MESSAGE_ID);
                if (metadataMessageId == null) {
                    continue;
                }
                String messageId = metadataMessageId.toString();
                deduplicated.merge(messageId, match.score(), Math::max);
            }

            List<ConversationRecallResult> results = new ArrayList<>(deduplicated.size());
            for (Map.Entry<String, Double> entry : deduplicated.entrySet()) {
                results.add(new ConversationRecallResult(entry.getKey(), entry.getValue()));
            }
            return results;
        } catch (Exception e) {
            log.warn("Conversation vector recall failed, userId={}, sessionId={}, err={}", userId, sessionId,
                    e.getMessage());
            return List.of();
        }
    }

    private Metadata buildMetadata(String userId, String sessionId, MessageEntity message) {
        Metadata metadata = new Metadata();
        metadata.put(USER_ID, userId);
        metadata.put(SESSION_ID, sessionId);
        metadata.put(MESSAGE_ID, message.getId());
        metadata.put(ROLE, message.getRole() != null ? message.getRole().name() : "");
        metadata.put(MESSAGE_TYPE, message.getMessageType() != null ? message.getMessageType().name() : "");
        if (message.getCreatedAt() != null) {
            metadata.put(CREATED_AT, String.valueOf(message.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli()));
        }
        return metadata;
    }

    private EmbeddingModelFactory.EmbeddingConfig buildEmbeddingConfig(String userId) {
        var embeddingCfg = userModelConfigResolver.getUserEmbeddingModelConfig(userId);
        return new EmbeddingModelFactory.EmbeddingConfig(embeddingCfg.getApiKey(), embeddingCfg.getBaseUrl(),
                embeddingCfg.getModelEndpoint());
    }

    private void removeMessage(String messageId) {
        if (isBlank(messageId)) {
            return;
        }
        conversationEmbeddingStore.removeAll(new IsEqualTo(MESSAGE_ID, messageId));
    }

    private int normalizeMaxCandidates(Integer maxCandidates) {
        if (maxCandidates == null) {
            return DEFAULT_MAX_CANDIDATES;
        }
        return Math.max(1, maxCandidates);
    }

    private double normalizeMinScore(Double minScore) {
        if (minScore == null) {
            return DEFAULT_MIN_SCORE;
        }
        return Math.max(0D, Math.min(1D, minScore));
    }

    private boolean shouldIndex(MessageEntity message) {
        if (message == null || isBlank(message.getId()) || isBlank(message.getContent())) {
            return false;
        }
        MessageType messageType = message.getMessageType();
        return messageType == null || Objects.equals(messageType, MessageType.TEXT);
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
