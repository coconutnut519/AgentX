package org.xhy.domain.conversation.model;

/** Ranked conversation recall result from vector search. */
public record ConversationRecallResult(String messageId, double score) {
}
