package org.xhy.application.conversation.service.message.agent.handler;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.xhy.application.conversation.service.message.agent.analysis.TaskSummarySupport;
import org.xhy.application.conversation.service.message.agent.analysis.dto.TaskExecutionResultDTO;
import org.xhy.application.conversation.service.message.agent.analysis.dto.TaskPlanDTO;
import org.xhy.application.conversation.service.message.agent.event.AgentWorkflowEvent;
import org.xhy.application.conversation.service.message.agent.manager.TaskManager;
import org.xhy.application.conversation.service.message.agent.service.TaskPlanReviewSessionSupport;
import org.xhy.application.conversation.service.message.agent.template.AgentPromptTemplates;
import org.xhy.application.conversation.service.message.agent.template.StructuredTaskSummaryPromptTemplate;
import org.xhy.application.conversation.service.message.agent.workflow.AgentWorkflowContext;
import org.xhy.application.conversation.service.message.agent.workflow.AgentWorkflowState;
import org.xhy.domain.conversation.constant.MessageType;
import org.xhy.domain.conversation.model.MessageEntity;
import org.xhy.domain.conversation.model.SessionEntity;
import org.xhy.domain.conversation.service.ContextDomainService;
import org.xhy.domain.conversation.service.MessageDomainService;
import org.xhy.infrastructure.llm.LLMServiceFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Summarizes structured task execution results into the final user-facing answer.
 */
@Component
public class SummarizeHandler extends AbstractAgentHandler {

    private final TaskPlanReviewSessionSupport taskPlanReviewSessionSupport;

    public SummarizeHandler(LLMServiceFactory llmServiceFactory, TaskManager taskManager,
            ContextDomainService contextDomainService, MessageDomainService messageDomainService,
            TaskPlanReviewSessionSupport taskPlanReviewSessionSupport) {
        super(llmServiceFactory, taskManager, contextDomainService, messageDomainService);
        this.taskPlanReviewSessionSupport = taskPlanReviewSessionSupport;
    }

    @Override
    protected boolean shouldHandle(AgentWorkflowEvent event) {
        return event.getToState() == AgentWorkflowState.TASK_EXECUTED;
    }

    @Override
    protected void transitionToNextState(AgentWorkflowContext<?> context) {
        // The live runtime path orchestrates summary directly.
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> void processEvent(AgentWorkflowContext<?> contextObj) {
        summarize((AgentWorkflowContext<T>) contextObj);
    }

    public <T> void summarize(AgentWorkflowContext<T> context) {
        try {
            MessageEntity summaryMessageEntity = createMessageEntity(context, MessageType.TEXT, null, 0);
            StreamingChatModel streamingClient = getStreamingClient(context);
            ChatRequest summaryRequest = buildSummaryRequest(context);

            streamingClient.doChat(summaryRequest, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    context.sendMessage(partialResponse, MessageType.TEXT);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    try {
                        TokenUsage tokenUsage = completeResponse.metadata().tokenUsage();
                        Integer outputTokenCount = tokenUsage != null ? tokenUsage.outputTokenCount() : 0;

                        String summary = completeResponse.aiMessage().text();
                        summaryMessageEntity.setContent(summary);
                        summaryMessageEntity.setTokenCount(outputTokenCount);

                        saveMessageAndUpdateContext(Collections.singletonList(summaryMessageEntity),
                                context.getChatContext());
                        taskManager.completeTask(context.getParentTask(), summary);

                        clearPlanReviewState(context);
                        context.sendEndMessage(MessageType.TEXT);
                        context.completeConnection();
                        context.transitionTo(AgentWorkflowState.COMPLETED);
                    } catch (Exception e) {
                        clearPlanReviewState(context);
                        context.handleError(e);
                    }
                }

                @Override
                public void onError(Throwable error) {
                    clearPlanReviewState(context);
                    context.handleError(error);
                }
            });
        } catch (Exception e) {
            clearPlanReviewState(context);
            context.handleError(e);
        }
    }

    private ChatRequest buildSummaryRequest(AgentWorkflowContext<?> context) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSummaryPrompt(context)));
        messages.add(new UserMessage("请给用户最终答复"));
        return buildChatRequest(context, messages);
    }

    @SuppressWarnings("unchecked")
    private String buildSummaryPrompt(AgentWorkflowContext<?> context) {
        TaskPlanDTO taskPlanDTO = (TaskPlanDTO) context.getExtraData(TaskSplitHandler.EXTRA_TASK_PLAN_KEY);
        Object executionResultsObj = context.getExtraData(TaskExecutionHandler.EXTRA_EXECUTION_RESULT_MAP_KEY);

        if (executionResultsObj instanceof Map<?, ?> rawExecutionResults) {
            Map<String, TaskExecutionResultDTO> executionResults =
                    (Map<String, TaskExecutionResultDTO>) rawExecutionResults;
            if (!CollectionUtils.isEmpty(executionResults)) {
                String payload = TaskSummarySupport.buildSummaryPayload(
                        context.getChatContext().getUserMessage(),
                        taskPlanDTO,
                        executionResults);
                return StructuredTaskSummaryPromptTemplate.buildPrompt(payload);
            }
        }

        return AgentPromptTemplates.getSummaryPrompt(context.buildTaskSummary());
    }

    private void clearPlanReviewState(AgentWorkflowContext<?> context) {
        Object sessionObj = context.getExtraData(TaskSplitHandler.EXTRA_SESSION_ENTITY_KEY);
        if (sessionObj instanceof SessionEntity sessionEntity) {
            taskPlanReviewSessionSupport.clear(sessionEntity, context.getChatContext().getUserId());
        }
    }
}
