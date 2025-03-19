package com.theleoborges.researchagent.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.DispatcherSelector;
import akka.actor.typed.javadsl.*;
import com.theleoborges.researchagent.models.Models;
import com.typesafe.config.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

public class ResearchCoordinator extends AbstractBehavior<ResearchCoordinator.Command> {



    // Command interface for the ResearchCoordinator
    public sealed interface Command {}

    // Command to start a new research process
    public record StartResearch(Models.SearchRequest request) implements Command {}

    // Internal message when search results are received
    record SearchCompleted(Models.SearchResults searchResults) implements Command {}

    // Internal message when research is completed
    record ResearchCompleted(Models.ResearchResult researchResult) implements Command {}

    // Internal message when writing content is completed
    record WriteContentCompleted(Models.WriteContentResult writeContentResult) implements Command {}

    // Internal message when analysis is completed
    record AnalysisCompleted(Models.AnalysisReport report) implements Command {}

    // Internal message when final report is ready
    record ReportCompleted(Models.FinalReport report) implements Command {}

    // State to track progress
    private Models.SearchRequest currentRequest;

    private ActorRef<SearchAgent.Command> searchAgent;
    private ActorRef<ResearchAgent.Command> researchAgent;
    private ActorRef<AnalysisAgent.Command> analysisAgent;
    private ActorRef<WriterAgent.Command> writerAgent;
    private ActorRef<ReportAgent.Command> reportAgent;

    // Factory method to create the actor behavior
    public static Behavior<Command> create() {
        return Behaviors.setup(ResearchCoordinator::new);
    }

    // Constructor
    private ResearchCoordinator(ActorContext<Command> context) {
        super(context);

        // Get the config
        Config config = context.getSystem().settings().config().getConfig("research-agent");
        Duration searchTimeout = config.getDuration("timeouts.search");
        Duration analysisTimeout = config.getDuration("timeouts.analysis");
        Duration reportTimeout = config.getDuration("timeouts.report-generation");


        List<Class<? extends AbstractBehavior<?>>> agents = Arrays.asList(SearchAgent.class, ResearchAgent.class);

        // Create the child agent actors with appropriate dispatchers
        this.searchAgent = context.spawn(
                SearchAgent.create(searchTimeout),
                "search-agent",
                DispatcherSelector.fromConfig("akka.actor.search-dispatcher")
        );

        this.researchAgent = context.spawn(
                ResearchAgent.create(analysisTimeout),
                "research-agent");

        this.analysisAgent = context.spawn(
                AnalysisAgent.create(analysisTimeout),
                "analysis-agent");

        this.writerAgent = context.spawn(
                WriterAgent.create(analysisTimeout),
                "writer-agent");

        this.reportAgent = context.spawn(
                ReportAgent.create(reportTimeout),
                "report-agent");

        context.getLog().info("Research Coordinator started with timeouts: search={}, analysis={}, report={}",
                searchTimeout, analysisTimeout, reportTimeout);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(StartResearch.class, this::onStartResearch)
                .onMessage(SearchCompleted.class, this::onSearchCompleted)
                .onMessage(ResearchCompleted.class, this::onResearchCompleted)
                .onMessage(AnalysisCompleted.class, this::onAnalysisCompleted)
                .onMessage(WriteContentCompleted.class, this::onWriteContentCompleted)
                .onMessage(ReportCompleted.class, this::onReportCompleted)
                .build();
    }

    private Behavior<Command> onStartResearch(StartResearch command) {
        currentRequest = command.request();
        getContext().getLog().info("Starting research for query: {}", currentRequest.query());

        // Send command to the search agent
        searchAgent.tell(new SearchAgent.PerformSearch(
                currentRequest,
                getContext().getSelf().narrow()
        ));

        return this;
    }

    private Behavior<Command> onSearchCompleted(SearchCompleted command) {
        getContext().getLog().info("Search completed");

        writeToDisk("01-searchResults.md", command.searchResults.searchContext(), "Failed to save search results to file");

        // Forward analysisReport to research agent
        researchAgent.tell(new ResearchAgent.ResearchTopic(
                command.searchResults(),
                getContext().getSelf().narrow()
        ));

        return this;
    }

    private Behavior<Command> onResearchCompleted(ResearchCompleted command) {
        getContext().getLog().info("Research completed");

        writeToDisk("02-researchResults.md", command.researchResult().research(), "Failed to save research to file");

        // Forward research to analysis agent
        analysisAgent.tell(new AnalysisAgent.AnalyzeResults(
                command.researchResult(),
                getContext().getSelf().narrow()
        ));

        return this;
    }

    private Behavior<Command> onAnalysisCompleted(AnalysisCompleted command) {
        getContext().getLog().info("Analysis completed");
        writeToDisk("03-analysisResults.md", command.report().analysis(), "Failed to save analysis report to file");

        // Send analysis to writer agent
        writerAgent.tell(new WriterAgent.WriteContent(
                command.report(),
                getContext().getSelf().narrow()
        ));

        return this;
    }

    private Behavior<Command> onWriteContentCompleted(WriteContentCompleted command) {
        getContext().getLog().info("Write Content completed");
        writeToDisk("04-writeResults.md", command.writeContentResult().content(), "Failed to save written article to file");

        // Send written article to report agent
        reportAgent.tell(new ReportAgent.GenerateReport(
                command.writeContentResult(),
                getContext().getSelf().narrow()
        ));

        return this;
    }

    private Behavior<Command> onReportCompleted(ReportCompleted command) {
        getContext().getLog().info("Research process completed!");
        writeToDisk("finalReport.md", command.report().detailedReport(), "Failed to save report to file");
        getContext().getLog().info("Final report created for query: {}", command.report().query());
        return this;
    }

    private void writeToDisk(String fileName, String content, String s) {
        try {
            Files.writeString(Paths.get("results").toAbsolutePath().resolve(fileName), content);
        } catch (IOException e) {
            getContext().getLog().error(s, e);
            throw new RuntimeException(e);
        }
    }
}