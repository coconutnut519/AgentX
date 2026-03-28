package org.xhy.domain.token.model.recall;

import org.xhy.domain.token.model.TokenMessage;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/** Ranked recall candidate for relevance-based context selection. */
public record RecallCandidate(List<TokenMessage> messages, double score) {

    public LocalDateTime firstCreatedAt() {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        return messages.stream().map(TokenMessage::getCreatedAt).filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
    }
}
