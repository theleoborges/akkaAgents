package com.theleoborges.researchagent.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.theleoborges.researchagent.models.Models;
import com.theleoborges.researchagent.tools.LLMService;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class ResearchAgent extends AbstractBehavior<ResearchAgent.Command> {

    private final LLMService llmService;

    // Command interface for the ResearchAgent
    public sealed interface Command {}

    // Command to research the given topic
    public record ResearchTopic(
            Models.SearchResults results,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}

    // Internal message for a completed research
    private record ResearchResultCompleted(
            String query,
            Models.ResearchResult researchResult,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}

    // Internal message for handling failures
    private record ResearchTopicFailed(
            String reason,
            String query,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}


    // Factory method to create the actor behavior with timeout
    public static Behavior<Command> create(Duration timeout) {
        return Behaviors.setup(context -> new ResearchAgent(context, timeout));
    }

    // Constructor
    private ResearchAgent(ActorContext<Command> context, Duration timeout) {
        super(context);
        this.llmService = new LLMService(timeout);
        context.getLog().info("Analysis Agent started with timeout: {}", timeout);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(ResearchTopic.class, this::onResearchTopic)
                .onMessage(ResearchResultCompleted.class, this::onResearchResultCompleted)
                .onMessage(ResearchTopicFailed.class, this::onResearchTopicFailed)
                .build();
    }

    private Behavior<Command> onResearchTopic(ResearchTopic command) {
        Models.SearchResults results = command.results();
        String query = results.query();
        String modelContext = results.searchContext();

        getContext().getLog().info("Starting research for query: {}", query);



        String model = getContext().getSystem().settings().config().getString("research-agent.apis.openai.model");
        Logger logger = getContext().getLog();

        CompletableFuture.supplyAsync(() -> {
                    try {
                        return researchTopic(query, modelContext, model, logger);
                    } catch (Exception e) {
                        getContext().getLog().error("Research failed for {}: {}",
                                modelContext, e.getMessage());
                        throw new RuntimeException("Research failed", e);
                    }
                })
                .thenAccept(research -> {
                    getContext().getSelf().tell(new ResearchResultCompleted(
                            query, research, command.replyTo()));
                })
                .exceptionally(ex -> {
                    getContext().getSelf().tell(new ResearchTopicFailed(
                            ex.getMessage(), query, command.replyTo()));
                    return null;
                });


        return this;
    }

    private Models.ResearchResult researchTopic(String query, String modelContext, String model, Logger logger) {
        
        String userPrompt = """
                **CONTEXT**:
                %s
                
                
                
                **TASK**:
                Write a 4,000 word extensive research on the following topic: "%s".
                You must use the context to ensure relevance in the output.
                
                Your research must be comprehensive and in-depth. Search for and compile information from:
                - Recent academic publications and peer-reviewed journals
                - Industry reports and white papers
                - Expert opinions and interviews
                - Case studies and real-world applications
                - Statistical data and trends
                - Multiple perspectives including enthusiasts and critics
                
                For each subtopic or angle:
                1. Find at least 3-5 distinct authoritative sources and cite them
                2. Gather specific examples, statistics, quotes, and detailed information
                3. Note contradictory views or debates within the field
                4. Identify emerging trends and future predictions
                
                Organize your research into detailed sections, covering:
                - Current state and major developments
                - Technical aspects and implementation details
                - Real-world applications and case studies
                - Challenges and limitations
                - Ethical considerations and debates
                - Future prospects and trends
                
                IMPORTANT: When searching for information, use the context provided for more up to date information
                and to ensure relevant and accuracy. Remember to include links from the context to all references in markdown format.

                Your final research document should be extensive and in markdown format properly organized with headings
                and subheadings, and including full citations and links for all sources from the context.
                This will serve as the foundation for an authoritative, in-depth report.
                """.formatted(modelContext, query);
        String systemPrompt = """
                You are a world-class research analyst with a talent for finding detailed, reliable information
                from diverse sources. You're meticulous, thorough, and always verify your sources.
                You specialize in deep research that uncovers insights others might miss.
               """;


        try {
            String responseContent = llmService.getChatCompletion(model, systemPrompt, userPrompt);

            return new Models.ResearchResult(query, responseContent);
        } catch (Exception e) {
            logger.error("Error analyzing content: {}", e.getMessage());
            return new Models.ResearchResult(
                    query,
                    "Analysis failed: " + e.getMessage()
            );
        }
    }


    private Behavior<Command> onResearchResultCompleted(ResearchResultCompleted msg) {
        getContext().getLog().info("Research completed successfully.");

        msg.replyTo().tell(new ResearchCoordinator.ResearchCompleted(msg.researchResult()));
        return this;
    }

    private Behavior<Command> onResearchTopicFailed(ResearchTopicFailed msg) {
        getContext().getLog().warn("Research failed: {}", msg.reason());

        Models.AnalysisReport finalReport = new Models.AnalysisReport(
                msg.query(),
                "generateOverallSummary(msg.query(), msg.existingAnalyses(), logger)"
        );

        Models.ResearchResult result = new Models.ResearchResult(msg.query, "Research failed");
        msg.replyTo().tell(new ResearchCoordinator.ResearchCompleted(result));

        return this;
    }

}