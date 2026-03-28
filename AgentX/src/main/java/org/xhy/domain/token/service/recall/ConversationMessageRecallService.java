package org.xhy.domain.token.service.recall;

import org.xhy.domain.token.model.TokenMessage;
import org.xhy.domain.token.model.config.TokenOverflowConfig;
import org.xhy.domain.token.model.recall.RecallCandidate;

import java.util.List;

/** Recall service used by relevance-based token overflow strategies. */
public interface ConversationMessageRecallService {

    List<RecallCandidate> recall(List<TokenMessage> candidates, TokenOverflowConfig config);
}
