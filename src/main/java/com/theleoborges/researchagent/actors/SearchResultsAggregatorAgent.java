package com.theleoborges.researchagent.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.theleoborges.researchagent.models.Models;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SearchResultsAggregatorAgent extends AbstractBehavior<SearchResultsAggregatorAgent.Command> {



    public interface Command {}

    public record StartAggregation(ActorRef<SearchAgent.Command> searchAgent, List<Models.SearchRequest> searchRequests,
                                   ActorRef<ResearchCoordinator.Command> replyTo) implements Command {}

    public record WorkerResult(Models.SearchResults results) implements Command {}
    public record WorkerFailed(Models.SearchResults results, String reason) implements Command {}



    public record AggregateResults(
            Models.SearchRequest request,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}



    private final Map<String, Models.SearchResults> results = new ConcurrentHashMap<>();
    private ActorRef<ResearchCoordinator.Command> replyTo;
    private ActorRef<SearchAgent.Command> searchAgent;
    private int totalOps;

    private SearchResultsAggregatorAgent(ActorContext<Command> context, Duration timeout) {
        super(context);
        context.getLog().info("Aggregator Agent started with timeout: {}", timeout);
    }

    public static Behavior<Command> create(Duration timeout) {
        //context -> new SearchAgent(context, timeout)
        return Behaviors.setup(context -> new SearchResultsAggregatorAgent(context, timeout));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(StartAggregation.class, this::onStartAggregation)
                .onMessage(WorkerResult.class, this::onWorkerResult)
                .onMessage(WorkerFailed.class, this::onWorkerFailed)
                .build();
    }



    private Behavior<Command> onStartAggregation(StartAggregation msg) {
        this.replyTo = msg.replyTo;
        this.searchAgent = msg.searchAgent;
        this.totalOps = msg.searchRequests.size();

        msg.searchRequests.forEach(searchRequest -> {
            searchAgent.tell(new SearchAgent.PerformSearch(
                    searchRequest,
                    getContext().getSelf().narrow()
            ));
        });

        return this;
    }

    private Behavior<Command> onWorkerResult(WorkerResult msg) {
        getContext().getLog().info("Search completed for query {}", msg.results().query());
        this.results.put(msg.results().query(), msg.results());

        if (this.results.size() == totalOps) {
            processResults(this.results);

        }
        return this;
    }

    private Behavior<Command> onWorkerFailed(WorkerFailed msg) {
        getContext().getLog().info("Search failed for query {} - reason {}", msg.results().query(), msg.reason());

        this.results.put(msg.results().query(), msg.results());

        if (this.results.size() == totalOps) {
            processResults(this.results);

        }
        return this;
    }

    private void processResults(Map<String, Models.SearchResults> results) {
        String aggregatedContext = results.values().stream().map(Models.SearchResults::searchContext).collect(Collectors.joining("\n"));
        ResearchCoordinator.writeToDisk("01-searchResults.md", aggregatedContext, "Failed to save search results to file");

        Models.AggregatedSearchResults aggregatedResults = new Models.AggregatedSearchResults(results.keySet(), aggregatedContext);
        replyTo.tell(new ResearchCoordinator.AggregatedResultsReceived(aggregatedResults));
    }
}