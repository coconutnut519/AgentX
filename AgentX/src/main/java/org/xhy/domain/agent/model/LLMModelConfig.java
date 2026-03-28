package org.xhy.domain.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.xhy.domain.shared.enums.TokenOverflowStrategyEnum;

/** LLM runtime config for an agent. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LLMModelConfig {

    private String modelId;
    private Double temperature = 0.7;
    private Double topP = 0.7;
    private Integer topK = 50;

    /** Max context token budget. */
    private Integer maxTokens;

    private TokenOverflowStrategyEnum strategyType = TokenOverflowStrategyEnum.NONE;

    /**
     * Ratio in [0, 1].
     * Sliding window uses it as headroom.
     * Summarize uses it as recent-message budget ratio.
     */
    private Double reserveRatio;

    /**
     * Summarize trigger threshold.
     * 1-100 means token usage percent of maxTokens.
     * Values above 100 are treated as legacy message-count thresholds.
     */
    private Integer summaryThreshold;

    private Integer recallTriggerThreshold;
    private Integer recallTopK;
    private Double recallMinScore;
    private Integer recallMaxCandidates;
    private Boolean enableRerank;

    public LLMModelConfig() {
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public TokenOverflowStrategyEnum getStrategyType() {
        return strategyType;
    }

    public void setStrategyType(TokenOverflowStrategyEnum strategyType) {
        this.strategyType = strategyType;
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
}
