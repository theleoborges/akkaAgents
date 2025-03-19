package com.theleoborges.researchagent.tools;

import com.theleoborges.researchagent.config.AppConfig;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.service.OpenAiService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class LLMService {
    private final OpenAiService openAiService;

    public LLMService(Duration timeout) {
        super();
        this.openAiService = new OpenAiService(
                AppConfig.getInstance().getOpenAIApiKey(),
                Duration.ofMillis(timeout.toMillis())
        );
    }


    public String getChatCompletion(String model, String systemPrompt, String userPrompt) {
        List<ChatMessage> messages = new ArrayList<>();

        messages.add(new ChatMessage("system", systemPrompt));
        messages.add(new ChatMessage("user", userPrompt));

        ChatCompletionRequest completionRequest = ChatCompletionRequest.builder()
                .messages(messages)
                .model(model)
                .build();

        return openAiService.createChatCompletion(completionRequest)
                .getChoices().getFirst().getMessage().getContent();
    }
}
