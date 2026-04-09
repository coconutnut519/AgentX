package org.xhy.application.conversation.service.message.agent.handler;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.xhy.application.conversation.service.message.agent.analysis.TaskPlanParser;
import org.xhy.application.conversation.service.message.agent.analysis.dto.TaskPlanDTO;
import org.xhy.application.conversation.service.message.agent.event.AgentWorkflowEvent;
import org.xhy.application.conversation.service.message.agent.manager.TaskManager;
import org.xhy.application.conversation.service.message.agent.service.InfoRequirementService;
import org.xhy.application.conversation.service.message.agent.service.TaskPlanReviewSessionSupport;
import org.xhy.application.conversation.service.message.agent.template.StructuredTaskPlanningPromptTemplate;
import org.xhy.application.conversation.service.message.agent.workflow.AgentWorkflowContext;
import org.xhy.application.conversation.service.message.agent.workflow.AgentWorkflowState;
import org.xhy.domain.conversation.constant.MessageType;
import org.xhy.domain.conversation.constant.Role;
import org.xhy.domain.conversation.model.MessageEntity;
import org.xhy.domain.conversation.model.SessionEntity;
import org.xhy.domain.conversation.service.ContextDomainService;
import org.xhy.domain.conversation.service.MessageDomainService;
import org.xhy.infrastructure.llm.LLMServiceFactory;
import org.xhy.infrastructure.utils.JsonUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits a complex task into a structured task plan and pauses for user confirmation.
 */
@Component
public class TaskSplitHandler extends AbstractAgentHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskSplitHandler.class);

    public static final String EXTRA_TASK_PLAN_KEY = "taskPlan";
    public static final String EXTRA_SESSION_ENTITY_KEY = "sessionEntity";

    private final TaskPlanReviewSessionSupport taskPlanReviewSessionSupport;

    public TaskSplitHandler(LLMServiceFactory llmServiceFactory, TaskManager taskManager,
            ContextDomainService contextDomainService, InfoRequirementService infoRequirementService,
            MessageDomainService messageDomainService, TaskPlanReviewSessionSupport taskPlanReviewSessionSupport) {
        super(llmServiceFactory, taskManager, contextDomainService, messageDomainService);
        this.taskPlanReviewSessionSupport = taskPlanReviewSessionSupport;
    }

    @Override
    protected boolean shouldHandle(AgentWorkflowEvent event) {
        return event.getToState() == AgentWorkflowState.TASK_SPLITTING;
    }

    @Override
    protected void transitionToNextState(AgentWorkflowContext<?> context) {
        // The live runtime path orchestrates planning directly and intentionally stops here.
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> void processEvent(AgentWorkflowContext<?> contextObj) {
        plan((AgentWorkflowContext<T>) contextObj);
    }

    public <T> void plan(AgentWorkflowContext<T> context) {
        doTaskSplitting(context);
    }

    private <T> void doTaskSplitting(AgentWorkflowContext<T> context) {
        try {
            ChatModel standardClient = getStandardClient(context);
            ChatRequest splitTaskRequest = buildSplitTaskRequest(context);
            ChatResponse completeResponse = standardClient.chat(splitTaskRequest);

            TokenUsage tokenUsage = completeResponse.metadata().tokenUsage();
            String fullResponse = completeResponse.aiMessage().text();

            TaskPlanDTO taskPlanDTO = TaskPlanParser.parse(fullResponse, context.getChatContext().getUserMessage());
            if (taskPlanDTO == null || !taskPlanDTO.hasTasks()) {
                context.handleError(new IllegalStateException("任务拆解失败，未生成有效的子任务计划"));
                return;
            }

            SessionEntity sessionEntity = getSessionEntity(context);
            int version = taskPlanReviewSessionSupport.nextVersion(sessionEntity);
            taskPlanDTO.setVersion(version);

            context.getLlmMessageEntity().setContent(JsonUtils.toJsonString(taskPlanDTO));
            context.getLlmMessageEntity().setTokenCount(tokenUsage != null ? tokenUsage.outputTokenCount() : 0);
            context.getLlmMessageEntity().setMessageType(MessageType.TASK_PLAN);
            context.addExtraData(EXTRA_TASK_PLAN_KEY, taskPlanDTO);

            saveMessageAndUpdateContext(Collections.singletonList(context.getLlmMessageEntity()),
                    context.getChatContext());

            taskPlanReviewSessionSupport.markWaitingConfirmation(sessionEntity, context.getChatContext().getUserId(),
                    context.getLlmMessageEntity().getId(), version);

            context.sendEndMessage(context.getLlmMessageEntity().getContent(), MessageType.TASK_PLAN);
            context.transitionTo(AgentWorkflowState.WAITING_INPUT_FOR_TASK_SPLIT);
            context.completeConnection();
        } catch (Exception e) {
            log.error("Failed to split task into structured plan, sessionId={}, userId={}",
                    context.getChatContext().getSessionId(), context.getChatContext().getUserId(), e);
            context.handleError(e);
        }
    }

    private <T> SessionEntity getSessionEntity(AgentWorkflowContext<T> context) {
        Object sessionObj = context.getExtraData(EXTRA_SESSION_ENTITY_KEY);
        if (sessionObj instanceof SessionEntity sessionEntity) {
            return sessionEntity;
        }
        throw new IllegalStateException("Missing session entity for task planning workflow");
    }

    private <T> ChatRequest buildSplitTaskRequest(AgentWorkflowContext<T> context) {
        List<ChatMessage> messages = new ArrayList<>();
        for (MessageEntity messageEntity : context.getChatContext().getMessageHistory()) {
            if (messageEntity == null || messageEntity.getMessageType() == MessageType.TASK_PLAN) {
                continue;
            }

            String content = messageEntity.getContent();
            if (!StringUtils.hasText(content)) {
                continue;
            }

            if (messageEntity.getRole() == Role.SYSTEM) {
                messages.add(new SystemMessage(content));
            } else if (messageEntity.getRole() == Role.USER) {
                messages.add(new UserMessage(content));
            } else {
                messages.add(new dev.langchain4j.data.message.AiMessage(content));
            }
        }

        messages.add(new SystemMessage(StructuredTaskPlanningPromptTemplate.getPrompt()));
        messages.add(new UserMessage(context.getChatContext().getUserMessage()));
        return buildChatRequest(context, messages);
    }
}
