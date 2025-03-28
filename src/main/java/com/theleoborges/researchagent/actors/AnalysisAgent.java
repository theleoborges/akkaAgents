package com.theleoborges.researchagent.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.*;
import com.theleoborges.researchagent.models.Models;
import com.theleoborges.researchagent.tools.LLMService;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class AnalysisAgent extends AbstractBehavior<AnalysisAgent.Command> {

    private final LLMService llmService;

    // Command interface for the AnalysisAgent
    public sealed interface Command {}

    // Command to analyze search analysisReport
    public record AnalyzeResults(
            Models.ResearchResult results,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}

    // Internal message for a completed analysis
    private record AnalysisResultReceived(
            Models.Analysis analysis,
            String query,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}

    // Internal message for handling failures
    private record AnalysisFailed(
            String reason,
            String query,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}


    // Factory method to create the actor behavior with timeout
    public static Behavior<Command> create(Duration timeout) {
        return Behaviors.setup(context -> new AnalysisAgent(context, timeout));
    }

    // Constructor
    private AnalysisAgent(ActorContext<Command> context, Duration timeout) {
        super(context);
        this.llmService = new LLMService(timeout);
        context.getLog().info("Analysis Agent started with timeout: {}", timeout);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(AnalyzeResults.class, this::onAnalyzeResults)
                .onMessage(AnalysisResultReceived.class, this::onAnalysisResultReceived)
                .onMessage(AnalysisFailed.class, this::onAnalysisFailed)
                .build();
    }

    private Behavior<Command> onAnalyzeResults(AnalyzeResults command) {
        Models.ResearchResult results = command.results;
        String query = results.query();
        String modelContext = results.research();

        String model = getContext().getSystem().settings().config().getString("research-agent.apis.openai.model");
        Logger logger = getContext().getLog();

        CompletableFuture.supplyAsync(() -> {
                    try {

                        return analyzeSearchResult(query, modelContext, model, logger);
                    } catch (Exception e) {
                        getContext().getLog().error("Analysis failed for {}: {}",
                                modelContext, e.getMessage());
                        throw new RuntimeException("Analysis failed", e);
                    }
                })
                .thenAccept(analysis -> {
                    getContext().getSelf().tell(new AnalysisResultReceived(
                            analysis, query, command.replyTo()));
                })
                .exceptionally(ex -> {
                    getContext().getSelf().tell(new AnalysisFailed(
                            ex.getMessage(), query, command.replyTo()));
                    return null;
                });


        return this;
    }

    private Behavior<Command> onAnalysisResultReceived(AnalysisResultReceived msg) {
        getContext().getLog().info("Analysis completed successfully.");

        Models.AnalysisReport report = new Models.AnalysisReport(msg.query(),
                msg.analysis().analysis());

        msg.replyTo().tell(new ResearchCoordinator.AnalysisCompleted(report));
        return this;
    }

    private Behavior<Command> onAnalysisFailed(AnalysisFailed msg) {
        getContext().getLog().info("Analysis failed.");

        Models.AnalysisReport report = new Models.AnalysisReport(msg.query(), msg.reason());

        msg.replyTo().tell(new ResearchCoordinator.AnalysisCompleted(report));
        return this;
    }

    private Models.Analysis analyzeSearchResult(String query, String modelContext, String model, Logger logger) {

        String userPrompt = """
            Analyze the following content related to the query: "%s"

            # CONTEXT:
            %s
            
            # Then perform this task:
            
            ## TASK:
            Perform an in-depth analysis of the context provided above and transform raw information into structured, valuable insights.

            Your analysis should:
            1. Identify major themes, patterns, and trends across the research
            2. Evaluate the significance, reliability, and implications of different information
            3. Connect related concepts and identify cause-effect relationships
            4. Highlight contradictions, knowledge gaps, or areas of uncertainty
            5. Compare and contrast different approaches, methodologies, or viewpoints
            6. Develop frameworks to organize and understand the information
            7. Draw evidence-based conclusions about current status and future directions
            
            Structure your analysis into these components:
            - Executive summary of key findings (500+ words)
            - Detailed analysis organized by major themes (3000+ words)
            - Evaluation of evidence quality and consensus levels
            - Identification of emerging patterns and their implications
            - Critical assessment of challenges and opportunities
            - Synthesis of interdisciplinary connections
            - Detailed future outlook with short and long-term projections
            
            Your analytical document should be in markdown format, at least 5,000 words and include visual frameworks (described in text)
            that help conceptualize the information. Maintain a balanced, objective perspective while
            providing insightful interpretation of the data.
            
            IMPORTANT: Include all links referenced in the context in markdown format. Use inline markdown links where appropriate.
            """.formatted(query, modelContext);
        String systemPrompt = """
            You are an expert analyst with extensive domain knowledge. You excel at synthesizing complex information
            and extracting valuable insights. You can connect dots across disparate data points and identify both
            obvious and subtle patterns. You pride yourself on transforming raw research into structured, actionable knowledge.
            """;


        try {
            String responseContent = llmService.getChatCompletion(model, systemPrompt, userPrompt);

            return new Models.Analysis(query, responseContent);
        } catch (Exception e) {
            logger.error("Error analyzing content: {}", e.getMessage());
            return new Models.Analysis(query, "Analysis failed: " + e.getMessage()
            );
        }
    }

}