package com.theleoborges.researchagent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonObject;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.theleoborges.researchagent.mcp.clients.BraveClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.Utils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.openai.core.ObjectMappers.jsonMapper;

public class LLMService {
    private final OpenAIClient openAIClient;
    private final JsonMapper jsonMapper;

    public LLMService(Duration timeout) {
        super();
        this.openAIClient = OpenAIOkHttpClient.fromEnv();
        this.jsonMapper = jsonMapper();
    }


    public String getChatCompletion(String model, String systemPrompt, String userPrompt) {

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .addSystemMessage(systemPrompt)
                .addUserMessage(userPrompt)
                .model(model)
                .putAdditionalQueryParam("max_tokens", System.getenv("MAX_TOKENS"))
                .putAdditionalQueryParam("max_completion_tokens", System.getenv("MAX_TOKENS"))
                .build();

        ChatCompletion completion = openAIClient.chat().completions().create(params);
        Optional<String> content = completion.choices().getFirst().message().content();
        return content.orElse("No completion found for user prompt: " + userPrompt.substring(0, 50));
    }

    public String getChatCompletion(String model, String systemPrompt, String userPrompt, List<McpSchema.Tool> tools, BraveClient client) {

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .addSystemMessage(systemPrompt)
                .addUserMessage(userPrompt)
                .model(model)
                .putAdditionalQueryParam("max_tokens", System.getenv("MAX_TOKENS"))
                .putAdditionalQueryParam("max_completion_tokens", System.getenv("MAX_TOKENS"))
                .tools(Utils.toOpenAITools(tools))
                .build();

        ChatCompletion completion = openAIClient.chat().completions().create(params);


        Optional<List<ChatCompletionMessageToolCall>> toolCalls = completion.choices().getFirst().message().toolCalls();

        // LLM didn't find relevant tools, return generated response
        if (toolCalls.isEmpty()) {
            Optional<String> content = completion.choices().getFirst().message().content();
            return content.orElse("No completion found for user prompt: " + userPrompt.substring(0, 50));
        }

        ChatCompletionMessageToolCall.Function function = toolCalls.stream().toList().getFirst().getFirst().function();
        String functionName = function.name();
        String functionArguments = function.arguments();

        try {
            JsonValue arguments = JsonValue.from(jsonMapper.readTree(functionArguments));
            String query = ((JsonObject) arguments).values().get("query").asStringOrThrow();
            McpSchema.CallToolResult callResult = client.callTool(
                    new McpSchema.CallToolRequest(functionName,
                            Map.of("query", query))
            );

            McpSchema.TextContent result = (McpSchema.TextContent) callResult.content().getFirst();

            return result.text();

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

    }
}


