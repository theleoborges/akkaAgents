package com.theleoborges.researchagent.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.*;
import com.theleoborges.researchagent.models.Models;
import com.theleoborges.researchagent.tools.LLMService;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class ReportAgent extends AbstractBehavior<ReportAgent.Command> {

    private final LLMService llmService;

    // Command interface for the ReportAgent
    public sealed interface Command {}

    // Command to generate a final report
    public record GenerateReport(
            Models.WriteContentResult writeContentResult,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}

    // Internal message for when report generation is complete
    private record ReportGenerated(
            Models.FinalReport report,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}

    // Internal message for handling failures
    private record ReportFailed(
            String reason,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}

    // Factory method to create the actor behavior with timeout
    public static Behavior<Command> create(Duration timeout) {
        return Behaviors.setup(context -> new ReportAgent(context, timeout));
    }

    // Constructor
    private ReportAgent(ActorContext<Command> context, Duration timeout) {
        super(context);
        this.llmService = new LLMService(timeout);
        context.getLog().info("Report Agent started with timeout: {}", timeout);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(GenerateReport.class, this::onGenerateReport)
                .onMessage(ReportGenerated.class, this::onReportGenerated)
                .onMessage(ReportFailed.class, this::onReportFailed)
                .build();
    }

    private Behavior<Command> onGenerateReport(GenerateReport command) {

        Logger logger = getContext().getLog();
        logger.info("Generating final report");

        String model = getContext().getSystem().settings().config().getString("research-agent.apis.openai.model");

        CompletableFuture.supplyAsync(() -> {
                    try {
                        return generateFinalReport(command.writeContentResult(), model, logger);
                    } catch (Exception e) {
                        logger.error("Report generation failed: {}", e.getMessage());
                        throw new RuntimeException("Report generation failed", e);
                    }
                })
                .thenAccept(finalReport -> {
                    getContext().getSelf().tell(new ReportGenerated(finalReport, command.replyTo()));
                })
                .exceptionally(ex -> {
                    getContext().getSelf().tell(new ReportFailed(ex.getMessage(), command.replyTo()));
                    return null;
                });

        return this;
    }

    private Behavior<Command> onReportGenerated(ReportGenerated message) {
        getContext().getLog().info("Final report generated for query: {}", message.report().query());

        // Forward the report to the coordinator
        message.replyTo().tell(new ResearchCoordinator.ReportCompleted(message.report()));

        return this;
    }

    private Behavior<Command> onReportFailed(ReportFailed message) {
        getContext().getLog().error("Report generation failed: {}", message.reason());

        // In a real application, you might implement retry logic or fallback options
        // For now, we'll create a minimal error report
        Models.FinalReport errorReport = new Models.FinalReport(
                "Error occurred",
                message.reason()
        );

        message.replyTo().tell(new ResearchCoordinator.ReportCompleted(errorReport));

        return this;
    }

    private Models.FinalReport generateFinalReport(Models.WriteContentResult writeContentResult, String model, Logger logger) {
        String userPrompt = """
                # CONTEXT
                %s
                
                # TASK
                Thoroughly review and enhance the above comprehensive article to ensure it meets the highest standards of quality,
                depth, and authority.
                
                Your editorial review should be extensive and meticulous, focusing on both content substance and presentation quality.
                
                Content Enhancement:
                1. Verify the article comprehensively covers all important aspects of the topic
                2. Ensure proper depth for each section (at least 500 words per major section)
                3. Confirm the presence of specific examples, data, and evidence throughout
                4. Check that multiple perspectives are presented in a balanced manner
                5. Identify and fill any remaining content gaps or shallow areas
                6. Strengthen the argumentation and logical flow throughout
                7. Ensure conclusions are fully supported by the presented evidence
                
                Structural Improvement:
                1. Optimize the overall structure for logical progression and reader understanding
                2. Ensure appropriate balance between sections
                3. Verify that headings and subheadings effectively organize the content
                4. Add transitional elements where needed to improve flow
                
                Quality Assurance:
                1. Correct any grammatical, spelling, or punctuation errors
                2. Refine language for clarity, precision, and engagement
                3. Eliminate redundancies and tighten prose while maintaining depth
                4. Check citation format and completeness, using links provided in the context
                5. Ensure consistent tone and style throughout
                
                Final Enhancements:
                1. Strengthen the introduction to effectively hook readers
                2. Enhance the conclusion to leave a lasting impression
                3. Add clarifying elements where complex concepts are presented
                4. Ensure the article maintains reader interest despite its length and depth
                5. Ensure the article has appropriate citations and references with links from the provided context.
                
                Your final edit should result in a polished, authoritative piece of 5,000 words that stands as
                a definitive resource on the topic. The article should be publication-ready and meet the standards
                of respected industry publications or academic journals.
                """.formatted(writeContentResult.content());

        String systemPrompt = """
                 You are a research synthesis expert. Your task is to combine multiple
                 sources of information into a clear, comprehensive, and well-structured
                 synthesis. Focus on identifying patterns, extracting key insights, and
                 presenting a unified understanding of the topic. Include all citations and sources
                """;

        try {
            String responseContent = llmService.getChatCompletion(model, systemPrompt, userPrompt);


            return new Models.FinalReport(
                    writeContentResult.query(),
                    responseContent
            );
        } catch (Exception e) {
            logger.error("Error generating final report: {}", e.getMessage());
            throw new RuntimeException("Failed to generate final report", e);
        }
    }
}