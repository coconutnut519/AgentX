package org.xhy.application.conversation.service.message.agent.analysis.dto;

public class AnalyzerMessageDTO {

    private boolean isQuestion;//isQuestion = true：普通问答
//isQuestion = false：复杂任务

    private String reply;

    public boolean getIsQuestion() {
        return isQuestion;
    }

    public void setIsQuestion(boolean question) {
        isQuestion = question;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }
}
