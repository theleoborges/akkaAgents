package com.theleoborges.researchagent.mcp.clients;

import com.theleoborges.researchagent.config.AppConfig;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.ClientMcpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class BraveClient {

    McpSyncClient mcpClient;
    private static BraveClient instance;

    public static BraveClient getInstance() {
        synchronized (BraveClient.class) {
            if (instance == null) {
                instance = new BraveClient();
            }
        }
        return instance;
    }

    private BraveClient() {
        System.err.println("Starting Brave MCP Client...");

        String serverJar = Paths.get("build/libs/").toAbsolutePath().resolve("akkaAgents-1.0-SNAPSHOT-standalone.jar").toString();
        ServerParameters params = ServerParameters.builder("java")
                .args("-jar", serverJar, "--server")
                .addEnvVar("HTTP_PROXY", System.getenv("HTTP_PROXY"))
                .addEnvVar("HTTPS_PROXY", System.getenv("HTTPS_PROXY"))
                .addEnvVar("http_proxy",  System.getenv("http_proxy"))
                .addEnvVar("https_proxy", System.getenv("https_proxy"))
                .addEnvVar("BRAVE_API_KEY", AppConfig.getInstance().getBraveApiKey())
                .build();

        ClientMcpTransport transport = new StdioClientTransport(params);

        mcpClient = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .capabilities(McpSchema.ClientCapabilities.builder()
                        .roots(true)      // Enable roots capability
                        .sampling()       // Enable sampling capability
                        .build())
                .build();


        // Initialize connection
        mcpClient.initialize();
    }

    public List<McpSchema.Tool> listTools() {
        return mcpClient.listTools().tools();
    }

    public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest query) {
        return mcpClient.callTool(query);
    }
}
