package com.theleoborges.researchagent.mcp.servers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.theleoborges.researchagent.models.Models;
import com.theleoborges.researchagent.tools.BraveSearch;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerMcpTransport;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

public class BraveServer {

    private final BraveSearch braveSearch;
    public final McpSyncServer mcpServer;

    public BraveServer(ServerMcpTransport transport) {
        System.err.println("Starting Brave MCP server...");
        this.braveSearch = BraveSearch.getInstance(Duration.ofSeconds(30));


        // Sync tool registration
        var schema = """
            {
              "type" : "object",
              "id" : "urn:jsonschema:BraveSearch",
              "properties" : {
                "query" : {
                  "type" : "string"
                },
                "count" : {
                  "type" : "number",
                  "default" : 5
                },
                "offset" : {
                  "type" : "number",
                  "default" : 0
                }
              },
              "required" : ["query"]
            }
            """;
        String name = "brave_web_search";
        String description = """
                    Performs a web search using the Brave Search API, ideal for general queries, news, articles, and online content.
                    
                    Use this for broad information gathering, recent events, or when you need diverse web sources.
                    Supports pagination, content filtering, and freshness controls.
                    Maximum 20 results per request, with offset for pagination.
                    
                    Args:
                        query: Search query (max 400 chars, 50 words)
                        count: Number of results (1-20, default 10)
                        offset: Pagination offset (max 9, default 0)
                    """;


        var syncToolRegistration = new McpServerFeatures.SyncToolRegistration(
                new McpSchema.Tool(name, description, schema),
                arguments -> {
                    String query = (String) arguments.get("query");
                    int count = arguments.get("count") == null ? 5 : (int) arguments.get("count");
                    Models.SearchResults result = null;

                    try {
                        result = braveSearch.search(query, count);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(result.searchContext())), false);
                }
        );


        this.mcpServer = McpServer.sync(transport)
                .serverInfo("brave-mcp-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)         // Enable tool support
                        .logging()           // Enable logging support
                        .build())
                .build();
        mcpServer.addTool(syncToolRegistration);


    }

    public void close() {
        mcpServer.closeGracefully();
    }

}
