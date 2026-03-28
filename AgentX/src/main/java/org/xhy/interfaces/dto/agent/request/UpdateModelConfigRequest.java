package org.xhy.interfaces.dto.agent.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.xhy.domain.shared.enums.TokenOverflowStrategyEnum;

/** Request payload for saving model config. */
public class UpdateModelConfigRequest {

    @NotBlank(message = "模型ID不能为空")
    private String modelId;

    @Min(value = 0, message = "temperature最小值为0")
    @Max(value = 2, message = "temperature最大值为2")
    private Double temperature;

    @Min(value = 0, message = "topP最小值为0")
    @Max(value = 1, message = "topP最大值为1")
    private Double topP;

    private Integer topK;

    @Min(value = 1, message = "maxTokens最小值为1")
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

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }
}
