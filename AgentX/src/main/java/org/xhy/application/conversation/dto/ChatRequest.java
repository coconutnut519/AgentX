package org.xhy.application.conversation.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** 聊天请求DTO */
public class ChatRequest {

    /** 消息内容 */
    private String message;

    /** 会话ID */
    @NotBlank(message = "会话id不可为空")
    private String sessionId;

    private List<String> fileUrls = new ArrayList<>();

    /** plan action: EXECUTE_PLAN executes the latest confirmed plan */
    private String planAction;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public List<String> getFileUrls() {
        return fileUrls;
    }

    public void setFileUrls(List<String> fileUrls) {
        this.fileUrls = fileUrls;
    }

    public String getPlanAction() {
        return planAction;
    }

    public void setPlanAction(String planAction) {
        this.planAction = planAction;
    }

    public boolean isExecutePlanAction() {
        return "EXECUTE_PLAN".equalsIgnoreCase(planAction);
    }

    @AssertTrue(message = "消息内容不可为空（执行计划除外）")
    public boolean isRequestValid() {
        return StringUtils.hasText(message) || isExecutePlanAction();
    }
}

