package io.modelcontextprotocol.spec;

import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionTool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Utils {
    public static List<ChatCompletionTool> toOpenAITools(List<McpSchema.Tool> tools) {
        return tools.stream().map(tool -> {

            McpSchema.JsonSchema inputSchema = tool.inputSchema();

            HashMap<Object, Object> properties = new HashMap<>();
            inputSchema.properties().forEach((key, value) -> {
                Map spec = (Map) value;
                if (spec.get("default") != null) {
                    properties.put(key, Map.of(
                            "type", spec.get("type"),
                            "default", spec.get("default")
                    ));
                } else {
                    properties.put(key, Map.of("type", spec.get("type")));
                }
            });

            FunctionParameters functionParameters = FunctionParameters.builder()
                    .putAdditionalProperty("type", JsonValue.from("object"))
                    .putAdditionalProperty(
                            "properties", JsonValue.from(properties))
                    .putAdditionalProperty("required", JsonValue.from(inputSchema.required()))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build();

            return ChatCompletionTool.builder()
                    .function(FunctionDefinition.builder()
                            .name(tool.name())
                            .description(tool.description())
                            .parameters(functionParameters)
                            .build())
                    .build();
        }).toList();
    }
}
