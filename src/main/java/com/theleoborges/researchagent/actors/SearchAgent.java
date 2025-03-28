package com.theleoborges.researchagent.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.*;
import com.theleoborges.researchagent.mcp.clients.BraveClient;
import com.theleoborges.researchagent.models.Models;
import com.theleoborges.researchagent.tools.BraveSearch;
import com.theleoborges.researchagent.tools.LLMService;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class SearchAgent extends AbstractBehavior<SearchAgent.Command> {

    private final LLMService llmService;
    private final BraveClient mcpClient;

    // Command interface for the SearchAgent
    public sealed interface Command {}

    // Command to perform a search
    public record PerformSearch(
            Models.SearchRequest request,
            ActorRef<SearchResultsAggregatorAgent.Command> replyTo
    ) implements Command {}

    // Internal message for results received
    private record SearchResultsReceived(
            Models.SearchResults results,
            ActorRef<SearchResultsAggregatorAgent.Command> replyTo
    ) implements Command {}

    // Internal message for handling failures
    private record SearchFailed(
            String query, String reason,
            ActorRef<SearchResultsAggregatorAgent.Command> replyTo
    ) implements Command {}


    // Factory method to create the actor behavior with timeout
    public static Behavior<Command> create(Duration timeout) {
        return Behaviors.setup(context -> new SearchAgent(context, timeout));
    }

    // Constructor
    private SearchAgent(ActorContext<Command> context, Duration timeout) {
        super(context);
        this.llmService = new LLMService(timeout);
        this.mcpClient = BraveClient.getInstance();
        context.getLog().info("Search Agent started with timeout: {}", timeout);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(PerformSearch.class, this::onPerformSearch)
                .onMessage(SearchResultsReceived.class, this::onSearchResultsReceived)
                .onMessage(SearchFailed.class, this::onSearchFailed)
                .build();
    }

    private Behavior<Command> onPerformSearch(PerformSearch command) {
        Logger logger = getContext().getLog();
        logger.info("Performing search for: {}", command.request().query());

        String systemPrompt = "You are a helpful assistant.";
        String query = command.request().query();
        int count = command.request().resultCount();



        // Schedule the HTTP request using the dispatcher
        CompletableFuture.supplyAsync(() -> {
                    try {
                        String context = llmService.getChatCompletion("gpt-4o",
                                systemPrompt,
                                query,
                                mcpClient.listTools(),
                                mcpClient);

                        return new Models.SearchResults(query, context);

                    } catch (Exception e) {
                        logger.error("Search failed: {}", e.getMessage());
                        throw new RuntimeException("Search failed", e);
                    }
                })
                .thenAccept(results -> {
//                    getContext().getSelf().tell(new SearchResultsReceived(results, command.replyTo()));
                    command.replyTo.tell(new SearchResultsAggregatorAgent.WorkerResult(results));
                })
                .exceptionally(ex -> {
//                    getContext().getSelf().tell(new SearchFailed(query, ex.getMessage(), command.replyTo()));
                    command.replyTo.tell(new SearchResultsAggregatorAgent.WorkerFailed(
                            new Models.SearchResults(query, ex.getMessage()), ex.getMessage()
                    ));
                    return null;
                });

        return this;
    }

    private Behavior<Command> onSearchResultsReceived(SearchResultsReceived message) {
        getContext().getLog().info("Search completed successfully");
        // Forward search results to the coordinator
//        message.replyTo().tell(new ResearchCoordinator.SearchCompleted(message.results()));
        message.replyTo().tell(new SearchResultsAggregatorAgent.WorkerResult(message.results()));

        return this;
    }

    private Behavior<Command> onSearchFailed(SearchFailed message) {
        getContext().getLog().error("Search failed for query {} - reason: {}", message.query(), message.reason());

        message.replyTo().tell(new SearchResultsAggregatorAgent.WorkerFailed(
                new Models.SearchResults(message.query(), message.reason()),
                message.reason()));

        return this;
    }

}