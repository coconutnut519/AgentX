package org.xhy.domain.token.model.config;

import org.springframework.stereotype.Service;
import org.xhy.domain.shared.enums.TokenOverflowStrategyEnum;
import org.xhy.infrastructure.llm.config.ProviderConfig;

/** Base config for token overflow handling. */
@Service
public class TokenOverflowConfig {

    /** Overflow strategy type. */
    private TokenOverflowStrategyEnum strategyType;

    /** Max context token budget. */
    private Integer maxTokens;

    /**
     * Ratio in [0, 1].
     * Sliding window uses it as headroom for future input/output tokens.
     * Summarize uses it as the recent-message budget ratio.
     */
    private Double reserveRatio;

    /**
     * Summarize trigger threshold.
     * 1-100 means token usage percent of maxTokens.
     * Values above 100 are treated as legacy message-count thresholds.
     */
    private Integer summaryThreshold;

    /** Query used by relevance recall strategies. */
    private String recallQuery;

    /** Current user scope for relevance recall. */
    private String userId;

    /** Current session scope for relevance recall. */
    private String sessionId;

    /** Trigger threshold for relevance recall, interpreted as maxTokens percent. */
    private Integer recallTriggerThreshold;

    /** Maximum number of recalled message groups kept before token pruning. */
    private Integer recallTopK;

    /** Minimum acceptable recall score. */
    private Double recallMinScore;

    /** Maximum number of candidates scored during recall. */
    private Integer recallMaxCandidates;

    /** Reserved for future rerank integration. */
    private Boolean enableRerank;

    private ProviderConfig providerConfig;

    public TokenOverflowConfig() {
        this.strategyType = TokenOverflowStrategyEnum.NONE;
    }

    public TokenOverflowConfig(TokenOverflowStrategyEnum strategyType) {
        this.strategyType = strategyType;
    }

    public TokenOverflowStrategyEnum getStrategyType() {
        return strategyType;
    }

    public void setStrategyType(TokenOverflowStrategyEnum strategyType) {
        this.strategyType = strategyType;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Double getReserveRatio() {
        return reserveRatio;
    }

    public void setReserveRatio(Double reserveRatio) {
        this.reserveRatio = reserveRatio;
    }

    public Integer getSummaryThreshold() {
        return summaryThreshold;
    }

    public void setSummaryThreshold(Integer summaryThreshold) {
        this.summaryThreshold = summaryThreshold;
    }

    public String getRecallQuery() {
        return recallQuery;
    }

    public void setRecallQuery(String recallQuery) {
        this.recallQuery = recallQuery;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Integer getRecallTriggerThreshold() {
        return recallTriggerThreshold;
    }

    public void setRecallTriggerThreshold(Integer recallTriggerThreshold) {
        this.recallTriggerThreshold = recallTriggerThreshold;
    }

    public Integer getRecallTopK() {
        return recallTopK;
    }

    public void setRecallTopK(Integer recallTopK) {
        this.recallTopK = recallTopK;
    }

    public Double getRecallMinScore() {
        return recallMinScore;
    }

    public void setRecallMinScore(Double recallMinScore) {
        this.recallMinScore = recallMinScore;
    }

    public Integer getRecallMaxCandidates() {
        return recallMaxCandidates;
    }

    public void setRecallMaxCandidates(Integer recallMaxCandidates) {
        this.recallMaxCandidates = recallMaxCandidates;
    }

    public Boolean getEnableRerank() {
        return enableRerank;
    }

    public void setEnableRerank(Boolean enableRerank) {
        this.enableRerank = enableRerank;
    }

    public static TokenOverflowConfig createDefault() {
        return new TokenOverflowConfig(TokenOverflowStrategyEnum.NONE);
    }

    public static TokenOverflowConfig createSlidingWindowConfig(int maxTokens, Double reserveRatio) {
        TokenOverflowConfig config = new TokenOverflowConfig(TokenOverflowStrategyEnum.SLIDING_WINDOW);
        config.setMaxTokens(maxTokens);
        config.setReserveRatio(reserveRatio != null ? reserveRatio : 0.1D);
        return config;
    }

    public static TokenOverflowConfig createSummaryConfig(int maxTokens, Integer summaryThreshold) {
        TokenOverflowConfig config = new TokenOverflowConfig(TokenOverflowStrategyEnum.SUMMARIZE);
        config.setMaxTokens(maxTokens);
        config.setSummaryThreshold(summaryThreshold != null ? summaryThreshold : 80);
        return config;
    }

    public static TokenOverflowConfig createRelevanceRecallConfig(int maxTokens, Integer triggerThreshold,
            Integer recallTopK, Double recallMinScore) {
        TokenOverflowConfig config = new TokenOverflowConfig(TokenOverflowStrategyEnum.RELEVANCE_RECALL);
        config.setMaxTokens(maxTokens);
        config.setRecallTriggerThreshold(triggerThreshold != null ? triggerThreshold : 80);
        config.setRecallTopK(recallTopK != null ? recallTopK : 4);
        config.setRecallMinScore(recallMinScore != null ? recallMinScore : 0.15D);
        config.setRecallMaxCandidates(20);
        config.setReserveRatio(0.4D);
        config.setEnableRerank(Boolean.FALSE);
        return config;
    }

    public ProviderConfig getProviderConfig() {
        return providerConfig;
    }

    public void setProviderConfig(ProviderConfig providerConfig) {
        this.providerConfig = providerConfig;
    }
}
