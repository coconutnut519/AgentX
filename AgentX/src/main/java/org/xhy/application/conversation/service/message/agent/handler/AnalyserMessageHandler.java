package org.xhy.application.conversation.service.message.agent.handler;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xhy.application.conversation.service.handler.content.ChatContext;
import org.xhy.application.conversation.service.message.agent.analysis.MessageIntentHeuristics;
import org.xhy.application.conversation.service.message.agent.analysis.dto.AnalyzerMessageDTO;
import org.xhy.application.conversation.service.message.agent.event.AgentWorkflowEvent;
import org.xhy.application.conversation.service.message.agent.manager.TaskManager;
import org.xhy.application.conversation.service.message.agent.service.InfoRequirementService;
import org.xhy.application.conversation.service.message.agent.template.AgentPromptTemplates;
import org.xhy.application.conversation.service.message.agent.workflow.AgentWorkflowContext;
import org.xhy.application.conversation.service.message.agent.workflow.AgentWorkflowState;
import org.xhy.domain.conversation.constant.MessageType;
import org.xhy.domain.conversation.service.ContextDomainService;
import org.xhy.domain.conversation.service.MessageDomainService;
import org.xhy.infrastructure.llm.LLMServiceFactory;
import org.xhy.infrastructure.utils.ModelResponseToJsonUtils;

import java.util.Collections;

/**
 * Analyses whether the user message should be answered directly or sent to task flow.
 */
@Component
public class AnalyserMessageHandler extends AbstractAgentHandler {

    private static final Logger log = LoggerFactory.getLogger(AnalyserMessageHandler.class);
    private static final String EXTRA_ANALYZER_MESSAGE_KEY = "analyzerMessage";

    protected AnalyserMessageHandler(LLMServiceFactory llmServiceFactory, TaskManager taskManager,
            ContextDomainService contextDomainService, InfoRequirementService infoRequirementService,
            MessageDomainService messageDomainService) {
        super(llmServiceFactory, taskManager, contextDomainService, messageDomainService);
    }

    @Override
    protected boolean shouldHandle(AgentWorkflowEvent event) {
        return event.getToState() == AgentWorkflowState.ANALYSER_MESSAGE;
    }

    @Override
    protected void transitionToNextState(AgentWorkflowContext<?> context) {
        AnalyzerMessageDTO analyzerMessageDTO = (AnalyzerMessageDTO) context.getExtraData(EXTRA_ANALYZER_MESSAGE_KEY);
        if (analyzerMessageDTO.getIsQuestion()) {
            this.setBreak(true);
            return;
        }
        this.setBreak(false);
        context.transitionTo(AgentWorkflowState.TASK_SPLITTING);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> void processEvent(AgentWorkflowContext<?> contextObj) {
        AgentWorkflowContext<T> context = (AgentWorkflowContext<T>) contextObj;
        String userMessage = contextObj.getChatContext().getUserMessage();

        try {
            AnalyzerMessageDTO analyzerMessageDTO = analyzeMessageIntent(context, userMessage);
            context.addExtraData(EXTRA_ANALYZER_MESSAGE_KEY, analyzerMessageDTO);
            context.getChatContext().setUserMessage(userMessage);

            if (analyzerMessageDTO.getIsQuestion()) {
                context.sendEndMessage(analyzerMessageDTO.getReply(), MessageType.TEXT);
                context.getLlmMessageEntity().setContent(analyzerMessageDTO.getReply());
                saveMessageAndUpdateContext(Collections.singletonList(context.getLlmMessageEntity()),
                        context.getChatContext());
                context.completeConnection();
                return;
            }

            saveMessageAndUpdateContext(Collections.singletonList(context.getUserMessageEntity()),
                    context.getChatContext());
        } catch (Exception e) {
            log.error("Failed to analyse message intent, sessionId={}, userId={}",
                    context.getChatContext().getSessionId(), context.getChatContext().getUserId(), e);
            context.handleError(e);
        }
    }

    private <T> AnalyzerMessageDTO analyzeMessageIntent(AgentWorkflowContext<T> context, String userMessage) {
        if (MessageIntentHeuristics.shouldForceTaskIntent(userMessage)) {
            log.info("Force task workflow by local heuristics, sessionId={}, userId={}",
                    context.getChatContext().getSessionId(), context.getChatContext().getUserId());
            return buildTaskAnalyzerMessageDTO();
        }

        ChatModel standardClient = getStandardClient(context);
        ChatRequest request = buildRequest(context);
        ChatResponse chat = standardClient.chat(request);
        String text = chat.aiMessage().text();
        return ModelResponseToJsonUtils.toJson(text, AnalyzerMessageDTO.class);
    }

    private AnalyzerMessageDTO buildTaskAnalyzerMessageDTO() {
        AnalyzerMessageDTO analyzerMessageDTO = new AnalyzerMessageDTO();
        analyzerMessageDTO.setIsQuestion(false);
        analyzerMessageDTO.setReply("");
        return analyzerMessageDTO;
    }

    private <T> ChatRequest buildRequest(AgentWorkflowContext<T> context) {
        ChatContext chatContext = context.getChatContext();
        String userMessage = chatContext.getUserMessage();
        chatContext.setUserMessage(AgentPromptTemplates.getAnalyserMessagePrompt(userMessage));
        return chatContext.prepareChatRequest().build();
    }
}
