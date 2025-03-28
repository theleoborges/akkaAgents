package com.theleoborges.researchagent.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.theleoborges.researchagent.mcp.clients.BraveClient;
import com.theleoborges.researchagent.models.Models;
import com.theleoborges.researchagent.tools.LLMService;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class QueryOptimiserAgent extends AbstractBehavior<QueryOptimiserAgent.Command> {

    private final LLMService llmService;
    private final BraveClient mcpClient;
    private final JsonMapper jsonMapper;


    // Command interface for the SearchAgent
    public sealed interface Command {}

    // Command to perform a search
    public record OptimiseQuery(
            Models.SearchRequest request,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}

    // Internal message for results received
    private record OptimisedQueriesReceived(
            List<Models.SearchRequest> searchRequests,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}

    // Internal message for handling failures
    private record SearchFailed(
            String query, String reason,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}

    public record QueryWrapper(List<String> queries) {}

    // Factory method to create the actor behavior with timeout
    public static Behavior<Command> create(Duration timeout) {
        return Behaviors.setup(context -> new QueryOptimiserAgent(context, timeout));
    }

    // Constructor
    private QueryOptimiserAgent(ActorContext<Command> context, Duration timeout) {
        super(context);
        this.llmService = new LLMService(timeout);
        this.mcpClient = BraveClient.getInstance();
        this.jsonMapper = new JsonMapper();
        context.getLog().info("Search Agent started with timeout: {}", timeout);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(OptimiseQuery.class, this::onOptimiseQuery)
                .onMessage(OptimisedQueriesReceived.class, this::onOptimisedQueriesReceived)
                .onMessage(SearchFailed.class, this::onSearchFailed)
                .build();
    }

    private Behavior<Command> onOptimiseQuery(OptimiseQuery command) {
        getContext().getLog().info("Optimising query: {}", command.request().query());

        String systemPrompt = """
                You are a search query optimization expert. Your task is to transform natural language questions into highly effective search engine queries.
                
                For each user question:
                1. Identify the core concepts and entities
                2. Create multiple search queries that approach the topic from different angles
                3. Include specific technical terms, industry jargon, and authoritative sources where appropriate
                4. Format queries for maximum search engine effectiveness (remove unnecessary words, focus on keywords)
                5. Ensure queries will find recent, relevant information

                Output exactly 3 optimized search queries in JSON format like this:
                {
                  "queries": [
                    "query 1 here",
                    "query 2 here",
                    "query 3 here"
                  ]
                }

                Each query should be distinct and target different aspects of the user's question.
                """;
        String userPrompt = command.request().query();
        int count = command.request().resultCount();



        // Schedule the HTTP request using the dispatcher
        CompletableFuture.supplyAsync(() -> {
                    try {
                        String json = llmService.getChatCompletion("gpt-4o", // hard-coded to gpt4 as o3-mini doesn't deal with tools yet
                                systemPrompt,
                                userPrompt);
                        ObjectMapper mapper = new ObjectMapper();
                        QueryWrapper wrapper = mapper.readValue(json, QueryWrapper.class);

                        return wrapper.queries().stream().map(query -> new Models.SearchRequest(query, count)).toList();

                    } catch (Exception e) {
                        getContext().getLog().error("Query Optimiser failed: {}", e.getMessage());
                        throw new RuntimeException("Query Optimiser failed", e);
                    }
                })
                .thenAccept(results -> {
                    getContext().getSelf().tell(new OptimisedQueriesReceived(results, command.replyTo()));
                })
                .exceptionally(ex -> {
                    getContext().getSelf().tell(new SearchFailed(userPrompt, ex.getMessage(), command.replyTo()));
                    return null;
                });

        return this;
    }

    private Behavior<Command> onOptimisedQueriesReceived(OptimisedQueriesReceived message) {
        getContext().getLog().info("Queries optimised successfully");

        // Forward optimised queries to coordinator
        message.replyTo().tell(new ResearchCoordinator.QueryOptimiserCompleted(message.searchRequests()));

        return this;
    }

    private Behavior<Command> onSearchFailed(SearchFailed message) {
        getContext().getLog().error("Search failed: {}", message.reason());

        message.replyTo().tell(new ResearchCoordinator.SearchCompleted(
                new Models.SearchResults(message.query() , message.reason())
        ));

        return this;
    }

}