package org.xhy.application.conversation.service.message.agent.handler;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import org.springframework.stereotype.Component;
import org.xhy.application.conversation.service.message.agent.Agent;
import org.xhy.application.conversation.service.message.agent.AgentToolManager;
import org.xhy.application.conversation.service.message.agent.analysis.TaskExecutionSupport;
import org.xhy.application.conversation.service.message.agent.analysis.dto.PlannedTaskDTO;
import org.xhy.application.conversation.service.message.agent.analysis.dto.TaskExecutionResultDTO;
import org.xhy.application.conversation.service.message.agent.analysis.dto.TaskPlanDTO;
import org.xhy.application.conversation.service.message.agent.event.AgentWorkflowEvent;
import org.xhy.application.conversation.service.message.agent.manager.TaskManager;
import org.xhy.application.conversation.service.message.agent.template.StructuredTaskExecutionPromptTemplate;
import org.xhy.application.conversation.service.message.agent.workflow.AgentWorkflowContext;
import org.xhy.application.conversation.service.message.agent.workflow.AgentWorkflowState;
import org.xhy.domain.conversation.constant.MessageType;
import org.xhy.domain.conversation.model.MessageEntity;
import org.xhy.domain.conversation.service.ContextDomainService;
import org.xhy.domain.conversation.service.MessageDomainService;
import org.xhy.domain.task.constant.TaskStatus;
import org.xhy.domain.task.model.TaskEntity;
import org.xhy.infrastructure.llm.LLMServiceFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes structured subtasks produced by the planner.
 */
@Component
public class TaskExecutionHandler extends AbstractAgentHandler {

    public static final String EXTRA_EXECUTION_RESULT_MAP_KEY = "taskExecutionResults";

    private final AgentToolManager toolManager;

    public TaskExecutionHandler(LLMServiceFactory llmServiceFactory, AgentToolManager toolManager,
            TaskManager taskManager, ContextDomainService contextDomainService,
            MessageDomainService messageDomainService) {
        super(llmServiceFactory, taskManager, contextDomainService, messageDomainService);
        this.toolManager = toolManager;
    }

    @Override
    protected boolean shouldHandle(AgentWorkflowEvent event) {
        return event.getToState() == AgentWorkflowState.TASK_SPLIT_COMPLETED;
    }

    @Override
    protected void transitionToNextState(AgentWorkflowContext<?> context) {
        // The live runtime path orchestrates execution directly.
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> void processEvent(AgentWorkflowContext<?> contextObj) {
        executePlannedTasks((AgentWorkflowContext<T>) contextObj);
    }

    public <T> void executePlannedTasks(AgentWorkflowContext<T> context) {
        TaskPlanDTO taskPlanDTO = (TaskPlanDTO) context.getExtraData(TaskSplitHandler.EXTRA_TASK_PLAN_KEY);

        try {
            ToolProvider toolProvider = createToolProvider(context);
            while (context.hasNextTask()) {
                String executionTaskName = context.getNextTask();
                if (executionTaskName == null) {
                    break;
                }

                TaskEntity subTask = context.getSubTaskMap().get(executionTaskName);
                PlannedTaskDTO plannedTaskDTO = TaskExecutionSupport.resolvePlannedTask(taskPlanDTO, executionTaskName);
                executeSubTask(context, subTask, executionTaskName, plannedTaskDTO, toolProvider);
                taskManager.updateTaskProgress(context.getParentTask(), context.getCompletedTaskCount(),
                        context.getTotalTaskCount());
            }
        } catch (Exception e) {
            context.handleError(e);
        }
    }

    private <T> ToolProvider createToolProvider(AgentWorkflowContext<T> context) {
        if (context == null || context.getChatContext() == null || context.getChatContext().getAgent() == null) {
            return null;
        }

        return toolManager.createToolProvider(
                context.getChatContext().getAgent().getToolIds(),
                context.getChatContext().getAgent().getToolPresetParams(),
                context.getChatContext().getUserId());
    }

    private <T> void executeSubTask(AgentWorkflowContext<T> context, TaskEntity subTask, String executionTaskName,
            PlannedTaskDTO plannedTaskDTO, ToolProvider toolProvider) {
        if (subTask == null) {
            context.handleError(new IllegalStateException("子任务不存在: " + executionTaskName));
            return;
        }

        try {
            String taskId = subTask.getId();
            taskManager.updateTaskStatus(subTask, TaskStatus.IN_PROGRESS);

            MessageEntity taskCallMessageEntity = createMessageEntity(context, MessageType.TASK_EXEC,
                    executionTaskName, 0);
            messageDomainService.saveMessage(Collections.singletonList(taskCallMessageEntity));

            context.sendEndMessage(executionTaskName, MessageType.TASK_EXEC);
            context.sendEndWithTaskIdMessage(taskId, MessageType.TASK_STATUS_TO_LOADING);

            String prompt = StructuredTaskExecutionPromptTemplate.buildPrompt(
                    context.getChatContext().getUserMessage(),
                    plannedTaskDTO,
                    TaskExecutionSupport.buildPreviousTaskContext(getExecutionResults(context)));

            ChatModel standardClient = llmServiceFactory.getStandardClient(context.getChatContext().getProvider(),
                    context.getChatContext().getModel());

            AiServices<Agent> builder = AiServices.builder(Agent.class).chatModel(standardClient);
            if (toolProvider != null) {
                builder.toolProvider(toolProvider);
            }
            Agent agent = builder.build();

            AiMessage aiMessage = agent.chat(prompt);
            if (aiMessage.hasToolExecutionRequests()) {
                handleToolCalls(aiMessage, context);
            }

            String taskResult = aiMessage.text();
            TaskExecutionResultDTO executionResult = buildSuccessResult(subTask, executionTaskName, plannedTaskDTO,
                    taskResult);
            addExecutionResult(context, executionResult);

            context.addTaskResult(executionTaskName, taskResult);
            taskManager.completeTask(subTask, taskResult);
            context.sendEndWithTaskIdMessage(taskId, MessageType.TASK_STATUS_TO_FINISH);
        } catch (Exception e) {
            TaskExecutionResultDTO executionResult = buildFailureResult(subTask, executionTaskName, plannedTaskDTO, e);
            addExecutionResult(context, executionResult);

            subTask.updateStatus(TaskStatus.FAILED);
            subTask.setTaskResult("执行失败: " + e.getMessage());
            taskManager.updateTaskStatus(subTask, TaskStatus.FAILED);

            context.sendEndMessage("任务 '" + executionTaskName + "' 执行失败: " + e.getMessage(), MessageType.TEXT);
            context.addTaskResult(executionTaskName, "执行失败: " + e.getMessage());
        }
    }

    private <T> void handleToolCalls(AiMessage aiMessage, AgentWorkflowContext<T> context) {
        MessageEntity toolCallMessageEntity = createMessageEntity(context, MessageType.TOOL_CALL, null, 0);
        StringBuilder toolCallsContent = new StringBuilder("工具调用:\n");

        aiMessage.toolExecutionRequests().forEach(toolExecutionRequest -> {
            String toolName = toolExecutionRequest.name();
            toolCallsContent.append("- ").append(toolName).append("\n");
            context.sendEndMessage(toolName, MessageType.TOOL_CALL);
        });

        toolCallMessageEntity.setContent(toolCallsContent.toString());
        messageDomainService.saveMessage(Collections.singletonList(toolCallMessageEntity));
        context.getChatContext().getContextEntity().getActiveMessages().add(toolCallMessageEntity.getId());
    }

    private TaskExecutionResultDTO buildSuccessResult(TaskEntity subTask, String executionTaskName,
            PlannedTaskDTO plannedTaskDTO, String taskResult) {
        TaskExecutionResultDTO resultDTO = buildBaseExecutionResult(subTask, executionTaskName, plannedTaskDTO);
        resultDTO.setSuccess(true);
        resultDTO.setResult(taskResult);
        return resultDTO;
    }

    private TaskExecutionResultDTO buildFailureResult(TaskEntity subTask, String executionTaskName,
            PlannedTaskDTO plannedTaskDTO, Exception e) {
        TaskExecutionResultDTO resultDTO = buildBaseExecutionResult(subTask, executionTaskName, plannedTaskDTO);
        resultDTO.setSuccess(false);
        resultDTO.setErrorMessage(e.getMessage());
        resultDTO.setResult("执行失败: " + e.getMessage());
        return resultDTO;
    }

    private TaskExecutionResultDTO buildBaseExecutionResult(TaskEntity subTask, String executionTaskName,
            PlannedTaskDTO plannedTaskDTO) {
        TaskExecutionResultDTO resultDTO = new TaskExecutionResultDTO();
        resultDTO.setTaskId(subTask.getId());
        resultDTO.setTaskName(executionTaskName);
        if (plannedTaskDTO != null) {
            resultDTO.setPlannedTaskId(plannedTaskDTO.getId());
            resultDTO.setTaskType(plannedTaskDTO.getType());
        }
        return resultDTO;
    }

    @SuppressWarnings("unchecked")
    private Map<String, TaskExecutionResultDTO> getExecutionResults(AgentWorkflowContext<?> context) {
        Object cachedResults = context.getExtraData(EXTRA_EXECUTION_RESULT_MAP_KEY);
        if (cachedResults instanceof Map<?, ?> resultMap) {
            return (Map<String, TaskExecutionResultDTO>) resultMap;
        }

        Map<String, TaskExecutionResultDTO> executionResults = new LinkedHashMap<>();
        context.addExtraData(EXTRA_EXECUTION_RESULT_MAP_KEY, executionResults);
        return executionResults;
    }

    private void addExecutionResult(AgentWorkflowContext<?> context, TaskExecutionResultDTO executionResult) {
        getExecutionResults(context).put(executionResult.getTaskName(), executionResult);
    }
}

