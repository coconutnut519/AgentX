package org.xhy.application.conversation.service.message.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.service.tool.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.xhy.application.billing.service.BillingService;
import org.xhy.application.conversation.dto.AgentChatResponse;
import org.xhy.application.conversation.service.ChatSessionManager;
import org.xhy.application.conversation.service.handler.context.ChatContext;
import org.xhy.application.conversation.service.message.TracingMessageHandler;
import org.xhy.application.conversation.service.message.agent.analysis.MessageIntentHeuristics;
import org.xhy.application.conversation.service.message.agent.analysis.dto.AnalyzerMessageDTO;
import org.xhy.application.conversation.service.message.agent.analysis.dto.PlannedTaskDTO;
import org.xhy.application.conversation.service.message.agent.analysis.dto.TaskPlanDTO;
import org.xhy.application.conversation.service.message.agent.handler.SummarizeHandler;
import org.xhy.application.conversation.service.message.agent.handler.TaskExecutionHandler;
import org.xhy.application.conversation.service.message.agent.handler.TaskSplitHandler;
import org.xhy.application.conversation.service.message.agent.manager.TaskManager;
import org.xhy.application.conversation.service.message.agent.service.TaskPlanReviewSessionSupport;
import org.xhy.application.conversation.service.message.agent.template.AgentPromptTemplates;
import org.xhy.application.conversation.service.message.agent.workflow.AgentWorkflowContext;
import org.xhy.application.trace.collector.TraceCollector;
import org.xhy.domain.conversation.constant.MessageType;
import org.xhy.domain.conversation.model.MessageEntity;
import org.xhy.domain.conversation.model.SessionEntity;
import org.xhy.domain.conversation.service.MessageDomainService;
import org.xhy.domain.conversation.service.SessionDomainService;
import org.xhy.domain.llm.service.HighAvailabilityDomainService;
import org.xhy.domain.llm.service.LLMDomainService;
import org.xhy.domain.task.model.TaskEntity;
import org.xhy.domain.user.service.AccountDomainService;
import org.xhy.domain.user.service.UserSettingsDomainService;
import org.xhy.infrastructure.llm.LLMServiceFactory;
import org.xhy.infrastructure.transport.MessageTransport;
import org.xhy.infrastructure.utils.JsonUtils;
import org.xhy.infrastructure.utils.ModelResponseToJsonUtils;
import org.xhy.application.conversation.service.message.builtin.BuiltInToolRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Agent message handler that keeps simple Q&A on the existing direct-answer path
 * and routes complex tasks through a plan-review-execute flow.
 */
@Component(value = "agentMessageHandler")
public class AgentMessageHandler extends TracingMessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(AgentMessageHandler.class);

    private final AgentToolManager agentToolManager;
    private final TaskManager taskManager;
    private final TaskSplitHandler taskSplitHandler;
    private final TaskExecutionHandler taskExecutionHandler;
    private final SummarizeHandler summarizeHandler;
    private final TaskPlanReviewSessionSupport taskPlanReviewSessionSupport;

    public AgentMessageHandler(LLMServiceFactory llmServiceFactory, MessageDomainService messageDomainService,
            HighAvailabilityDomainService highAvailabilityDomainService, SessionDomainService sessionDomainService,
            UserSettingsDomainService userSettingsDomainService, LLMDomainService llmDomainService,
            BuiltInToolRegistry builtInToolRegistry, BillingService billingService,
            AccountDomainService accountDomainService, ChatSessionManager chatSessionManager,
            TraceCollector traceCollector, AgentToolManager agentToolManager, TaskManager taskManager,
            TaskSplitHandler taskSplitHandler, TaskExecutionHandler taskExecutionHandler,
            SummarizeHandler summarizeHandler, TaskPlanReviewSessionSupport taskPlanReviewSessionSupport) {
        super(llmServiceFactory, messageDomainService, highAvailabilityDomainService, sessionDomainService,
                userSettingsDomainService, llmDomainService, builtInToolRegistry, billingService, accountDomainService,
                chatSessionManager, traceCollector);
        this.agentToolManager = agentToolManager;
        this.taskManager = taskManager;
        this.taskSplitHandler = taskSplitHandler;
        this.taskExecutionHandler = taskExecutionHandler;
        this.summarizeHandler = summarizeHandler;
        this.taskPlanReviewSessionSupport = taskPlanReviewSessionSupport;
    }

    @Override
    public <T> T chat(ChatContext chatContext, MessageTransport<T> transport) {
        if (chatContext == null || !chatContext.isStreaming()) {
            return super.chat(chatContext, transport);
        }

        SessionEntity sessionEntity = sessionDomainService.getSession(chatContext.getSessionId(), chatContext.getUserId());
        if (taskPlanReviewSessionSupport.getState(sessionEntity).isWaitingConfirmation()) {
            if (chatContext.isExecutePlanAction()) {
                return executeConfirmedPlan(chatContext, transport, sessionEntity);
            }
            return regeneratePlan(chatContext, transport, sessionEntity);
        }

        if (!shouldRouteToTaskPlanning(chatContext)) {
            return super.chat(chatContext, transport);
        }

        return generateTaskPlan(chatContext, transport, sessionEntity);
    }

    private <T> T generateTaskPlan(ChatContext runtimeChatContext, MessageTransport<T> transport,
            SessionEntity sessionEntity) {
        T connection = createWorkflowConnection(runtimeChatContext, transport);
        try {
            MessageEntity userMessageEntity = createUserMessage(runtimeChatContext);
            onUserMessageProcessed(runtimeChatContext, userMessageEntity);
            saveUserMessage(runtimeChatContext, userMessageEntity);

            AgentWorkflowContext<T> workflowContext = createWorkflowContext(runtimeChatContext, connection, transport,
                    userMessageEntity, createLlmMessage(runtimeChatContext), sessionEntity,
                    runtimeChatContext.getUserMessage());
            taskSplitHandler.plan(workflowContext);
            return connection;
        } catch (Exception e) {
            logger.error("Failed to generate task plan, sessionId={}, userId={}", runtimeChatContext.getSessionId(),
                    runtimeChatContext.getUserId(), e);
            taskPlanReviewSessionSupport.clear(sessionEntity, runtimeChatContext.getUserId());
            transport.sendMessage(connection, AgentChatResponse.buildEndMessage(e.getMessage(), MessageType.TEXT));
            transport.handleError(connection, e);
            return connection;
        }
    }

    private <T> T regeneratePlan(ChatContext runtimeChatContext, MessageTransport<T> transport,
            SessionEntity sessionEntity) {
        T connection = createWorkflowConnection(runtimeChatContext, transport);
        try {
            MessageEntity feedbackMessageEntity = createUserMessage(runtimeChatContext);
            onUserMessageProcessed(runtimeChatContext, feedbackMessageEntity);
            saveUserMessage(runtimeChatContext, feedbackMessageEntity);

            AgentWorkflowContext<T> workflowContext = createWorkflowContext(runtimeChatContext, connection, transport,
                    feedbackMessageEntity, createLlmMessage(runtimeChatContext), sessionEntity,
                    runtimeChatContext.getUserMessage());
            taskSplitHandler.plan(workflowContext);
            return connection;
        } catch (Exception e) {
            logger.error("Failed to regenerate task plan, sessionId={}, userId={}", runtimeChatContext.getSessionId(),
                    runtimeChatContext.getUserId(), e);
            transport.sendMessage(connection, AgentChatResponse.buildEndMessage(e.getMessage(), MessageType.TEXT));
            transport.handleError(connection, e);
            return connection;
        }
    }

    private <T> T executeConfirmedPlan(ChatContext runtimeChatContext, MessageTransport<T> transport,
            SessionEntity sessionEntity) {
        T connection = createWorkflowConnection(runtimeChatContext, transport);
        try {
            TaskPlanDTO taskPlanDTO = loadCurrentTaskPlan(sessionEntity);
            if (taskPlanDTO == null || !taskPlanDTO.hasTasks()) {
                throw new IllegalStateException("当前会话没有可执行的任务拆解计划");
            }

            taskPlanReviewSessionSupport.markExecuting(sessionEntity, runtimeChatContext.getUserId());

            String executionRequest = resolveExecutionRequest(runtimeChatContext, taskPlanDTO);
            AgentWorkflowContext<T> workflowContext = createWorkflowContext(runtimeChatContext, connection, transport,
                    null, createLlmMessage(runtimeChatContext), sessionEntity, executionRequest);
            workflowContext.addExtraData(TaskSplitHandler.EXTRA_TASK_PLAN_KEY, taskPlanDTO);

            TaskEntity parentTask = taskManager.createParentTask(workflowContext.getChatContext());
            workflowContext.setParentTask(parentTask);
            hydrateSubTasks(taskPlanDTO, workflowContext);

            if (workflowContext.getTasks().isEmpty()) {
                taskPlanReviewSessionSupport.clear(sessionEntity, runtimeChatContext.getUserId());
                throw new IllegalStateException("任务计划中没有可执行的子任务");
            }

            taskExecutionHandler.executePlannedTasks(workflowContext);
            if (workflowContext.getState() == org.xhy.application.conversation.service.message.agent.workflow.AgentWorkflowState.FAILED) {
                taskPlanReviewSessionSupport.clear(sessionEntity, runtimeChatContext.getUserId());
                return connection;
            }

            summarizeHandler.summarize(workflowContext);
            return connection;
        } catch (Exception e) {
            logger.error("Failed to execute confirmed task plan, sessionId={}, userId={}",
                    runtimeChatContext.getSessionId(), runtimeChatContext.getUserId(), e);
            taskPlanReviewSessionSupport.clear(sessionEntity, runtimeChatContext.getUserId());
            transport.sendMessage(connection, AgentChatResponse.buildEndMessage(e.getMessage(), MessageType.TEXT));
            transport.handleError(connection, e);
            return connection;
        }
    }

    private boolean shouldRouteToTaskPlanning(ChatContext chatContext) {
        String userMessage = chatContext.getUserMessage();
        if (!StringUtils.hasText(userMessage)) {
            return false;
        }

        if (MessageIntentHeuristics.shouldForceTaskIntent(userMessage)) {
            return true;
        }

        try {
            AnalyzerMessageDTO analyzerMessageDTO = analyzeIntent(chatContext);
            return analyzerMessageDTO != null && !Boolean.TRUE.equals(analyzerMessageDTO.getIsQuestion());
        } catch (Exception e) {
            logger.warn("Intent analysis failed, fallback to direct answer. sessionId={}, userId={}, error={}",
                    chatContext.getSessionId(), chatContext.getUserId(), e.getMessage());
            return false;
        }
    }

    private AnalyzerMessageDTO analyzeIntent(ChatContext chatContext) {
        ChatModel standardClient = llmServiceFactory.getStandardClient(chatContext.getProvider(), chatContext.getModel());
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(AgentPromptTemplates.getAnalyserMessagePrompt(chatContext.getUserMessage())));

        OpenAiChatRequestParameters.Builder parameters = new OpenAiChatRequestParameters.Builder();
        parameters.modelName(chatContext.getModel().getModelId());
        parameters.topP(chatContext.getLlmModelConfig().getTopP()).temperature(0.1);

        ChatRequest request = new ChatRequest.Builder()
                .messages(messages)
                .parameters(parameters.build())
                .build();
        ChatResponse chatResponse = standardClient.chat(request);
        return ModelResponseToJsonUtils.toJson(chatResponse.aiMessage().text(), AnalyzerMessageDTO.class);
    }

    private <T> T createWorkflowConnection(ChatContext chatContext, MessageTransport<T> transport) {
        T connection = transport.createConnection(CONNECTION_TIMEOUT);
        onChatStart(chatContext);
        checkBalanceBeforeChat(chatContext.getUserId(), transport, connection);
        return connection;
    }

    private void saveUserMessage(ChatContext runtimeChatContext, MessageEntity userMessageEntity) {
        messageDomainService.saveMessageAndUpdateContext(Collections.singletonList(userMessageEntity),
                runtimeChatContext.getContextEntity());
        runtimeChatContext.getMessageHistory().add(userMessageEntity);
    }

    private TaskPlanDTO loadCurrentTaskPlan(SessionEntity sessionEntity) {
        var sessionState = taskPlanReviewSessionSupport.getState(sessionEntity);
        if (sessionState.getTaskPlanReview() == null) {
            return null;
        }

        String planMessageId = sessionState.getTaskPlanReview().getPlanMessageId();
        if (!StringUtils.hasText(planMessageId)) {
            return null;
        }

        MessageEntity planMessageEntity = messageDomainService.getById(planMessageId);
        if (planMessageEntity == null || !StringUtils.hasText(planMessageEntity.getContent())) {
            return null;
        }

        return JsonUtils.parseObject(planMessageEntity.getContent(), TaskPlanDTO.class);
    }

    private String resolveExecutionRequest(ChatContext runtimeChatContext, TaskPlanDTO taskPlanDTO) {
        if (StringUtils.hasText(taskPlanDTO.getGoal())) {
            return taskPlanDTO.getGoal();
        }

        List<MessageEntity> history = runtimeChatContext.getMessageHistory();
        for (int index = history.size() - 1; index >= 0; index--) {
            MessageEntity messageEntity = history.get(index);
            if (messageEntity != null && messageEntity.isUserMessage() && StringUtils.hasText(messageEntity.getContent())) {
                return messageEntity.getContent();
            }
        }
        return "请执行当前任务计划";
    }

    private <T> void hydrateSubTasks(TaskPlanDTO taskPlanDTO, AgentWorkflowContext<T> workflowContext) {
        for (PlannedTaskDTO plannedTaskDTO : taskPlanDTO.getTasks()) {
            if (plannedTaskDTO == null) {
                continue;
            }

            String executionTask = plannedTaskDTO.toExecutionTask();
            if (!StringUtils.hasText(executionTask)) {
                continue;
            }

            TaskEntity subTask = taskManager.createSubTask(executionTask, workflowContext.getParentTask().getId(),
                    workflowContext.getChatContext());
            workflowContext.addSubTask(executionTask, subTask);
        }
    }

    private <T> AgentWorkflowContext<T> createWorkflowContext(ChatContext runtimeChatContext, T connection,
            MessageTransport<T> transport, MessageEntity userMessageEntity, MessageEntity llmMessageEntity,
            SessionEntity sessionEntity, String effectiveUserMessage) {
        org.xhy.application.conversation.service.handler.content.ChatContext workflowChatContext =
                new org.xhy.application.conversation.service.handler.content.ChatContext();
        workflowChatContext.setSessionId(runtimeChatContext.getSessionId());
        workflowChatContext.setUserId(runtimeChatContext.getUserId());
        workflowChatContext.setUserMessage(effectiveUserMessage);
        workflowChatContext.setPlanAction(runtimeChatContext.getPlanAction());
        workflowChatContext.setAgent(runtimeChatContext.getAgent());
        workflowChatContext.setModel(runtimeChatContext.getModel());
        workflowChatContext.setProvider(runtimeChatContext.getProvider());
        workflowChatContext.setLlmModelConfig(runtimeChatContext.getLlmModelConfig());
        workflowChatContext.setContextEntity(runtimeChatContext.getContextEntity());
        workflowChatContext.setMessageHistory(runtimeChatContext.getMessageHistory());

        AgentWorkflowContext<T> workflowContext = new AgentWorkflowContext<>();
        workflowContext.setChatContext(workflowChatContext);
        workflowContext.setConnection(connection);
        workflowContext.setMessageTransport(transport);
        workflowContext.setUserMessageEntity(userMessageEntity);
        workflowContext.setLlmMessageEntity(llmMessageEntity);
        workflowContext.addExtraData(TaskSplitHandler.EXTRA_SESSION_ENTITY_KEY, sessionEntity);
        return workflowContext;
    }

    @Override
    protected ToolProvider provideTools(ChatContext chatContext) {
        return agentToolManager.createToolProvider(agentToolManager.getAvailableTools(chatContext),
                chatContext.getAgent().getToolPresetParams(), chatContext.getUserId());
    }
}
