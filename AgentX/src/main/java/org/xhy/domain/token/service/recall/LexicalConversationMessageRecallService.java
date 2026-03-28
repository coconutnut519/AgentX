package org.xhy.domain.token.service.recall;

import org.xhy.domain.token.model.TokenMessage;
import org.xhy.domain.token.model.config.TokenOverflowConfig;
import org.xhy.domain.token.model.recall.RecallCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First-stage recall implementation.
 * It uses deterministic lexical overlap so the relevance strategy can be wired
 * end-to-end before vector recall storage is added.
 */
public class LexicalConversationMessageRecallService implements ConversationMessageRecallService {

    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}_]+");

    @Override
    public List<RecallCandidate> recall(List<TokenMessage> candidates, TokenOverflowConfig config) {
        if (candidates == null || candidates.isEmpty() || config == null || isBlank(config.getRecallQuery())) {
            return List.of();
        }

        String normalizedQuery = config.getRecallQuery().toLowerCase(Locale.ROOT).trim();
        Set<String> queryTokens = tokenize(normalizedQuery);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        int maxCandidates = config.getRecallMaxCandidates() != null ? Math.max(config.getRecallMaxCandidates(), 1) : 20;
        double minScore = config.getRecallMinScore() != null ? config.getRecallMinScore() : 0.15D;

        List<RecallCandidate> scored = new ArrayList<>();
        for (TokenMessage candidate : candidates) {
            double score = scoreCandidate(candidate, queryTokens, normalizedQuery);
            if (score >= minScore) {
                scored.add(new RecallCandidate(List.of(candidate), score));
            }
        }

        scored.sort(Comparator.comparingDouble(RecallCandidate::score).reversed()
                .thenComparing(RecallCandidate::firstCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));

        if (scored.size() <= maxCandidates) {
            return scored;
        }
        return new ArrayList<>(scored.subList(0, maxCandidates));
    }

    protected double scoreCandidate(TokenMessage candidate, Set<String> queryTokens, String normalizedQuery) {
        if (candidate == null || isBlank(candidate.getContent())) {
            return 0D;
        }

        Set<String> candidateTokens = tokenize(candidate.getContent());
        if (candidateTokens.isEmpty()) {
            return 0D;
        }

        int overlap = 0;
        for (String token : queryTokens) {
            if (candidateTokens.contains(token)) {
                overlap++;
            }
        }

        if (overlap == 0) {
            return 0D;
        }

        double cosineLikeScore = overlap / Math.sqrt((double) queryTokens.size() * candidateTokens.size());
        String normalizedContent = candidate.getContent().toLowerCase(Locale.ROOT);
        double exactMatchBoost = normalizedContent.contains(normalizedQuery) ? 0.15D : 0D;
        return Math.min(1D, cosineLikeScore + exactMatchBoost);
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (isBlank(text)) {
            return tokens;
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        Matcher matcher = WORD_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }

        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (isCjk(ch)) {
                tokens.add(String.valueOf(ch));
            }
        }

        return tokens;
    }

    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
