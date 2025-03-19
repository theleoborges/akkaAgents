package com.theleoborges.researchagent.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.*;
import com.theleoborges.researchagent.models.Models;
import com.theleoborges.researchagent.tools.BraveSearch;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class SearchAgent extends AbstractBehavior<SearchAgent.Command> {

    private final BraveSearch braveSearchTool;

    // Command interface for the SearchAgent
    public sealed interface Command {}

    // Command to perform a search
    public record PerformSearch(
            Models.SearchRequest request,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}

    // Internal message for results received
    private record SearchResultsReceived(
            Models.SearchResults results,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}

    // Internal message for handling failures
    private record SearchFailed(
            String query, String reason,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}


    // Factory method to create the actor behavior with timeout
    public static Behavior<Command> create(Duration timeout) {
        return Behaviors.setup(context -> new SearchAgent(context, timeout));
    }

    // Constructor
    private SearchAgent(ActorContext<Command> context, Duration timeout) {
        super(context);
        this.braveSearchTool = new BraveSearch(timeout);
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
        getContext().getLog().info("Performing search for: {}", command.request().query());

        String query = command.request().query();
        int count = command.request().resultCount();

        // Schedule the HTTP request using the dispatcher
        CompletableFuture.supplyAsync(() -> {
                    try {
                        return braveSearchTool.search(query, count);
                    } catch (Exception e) {
                        getContext().getLog().error("Search failed: {}", e.getMessage());
                        throw new RuntimeException("Search failed", e);
                    }
                })
                .thenAccept(results -> {
                    getContext().getSelf().tell(new SearchResultsReceived(results, command.replyTo()));
                })
                .exceptionally(ex -> {
                    getContext().getSelf().tell(new SearchFailed(query, ex.getMessage(), command.replyTo()));
                    return null;
                });

        return this;
    }

    private Behavior<Command> onSearchResultsReceived(SearchResultsReceived message) {
        getContext().getLog().info("Search completed successfully");

        // Forward search results to the coordinator
        message.replyTo().tell(new ResearchCoordinator.SearchCompleted(message.results()));

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