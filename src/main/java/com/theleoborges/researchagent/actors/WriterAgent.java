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

public class WriterAgent extends AbstractBehavior<WriterAgent.Command> {

    private final LLMService llmService;

    // Command interface for the WriterAgent
    public sealed interface Command {}

    // Command to write article
    public record WriteContent(
            Models.AnalysisReport analysisReport,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}

    // Internal message for writing completed
    private record WriteContentCompleted(
            Models.WriteContentResult writeContentResult,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}

    // Internal message for handling failures
    private record WriteContentFailed(
            String query, String reason,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}


    // Factory method to create the actor behavior with timeout
    public static Behavior<Command> create(Duration timeout) {
        return Behaviors.setup(context -> new WriterAgent(context, timeout));
    }

    // Constructor
    private WriterAgent(ActorContext<Command> context, Duration timeout) {
        super(context);
        this.llmService = new LLMService(timeout);
        context.getLog().info("Writer Agent started with timeout: {}", timeout);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(WriteContent.class, this::onWriteContent)
                .onMessage(WriteContentCompleted.class, this::onWriteContentCompleted)
                .onMessage(WriteContentFailed.class, this::onWriteContentFailed)
                .build();
    }

    private Behavior<Command> onWriteContent(WriteContent command) {

        getContext().getLog().info("Starting write content");

        String model = getContext().getSystem().settings().config().getString("research-agent.apis.openai.model");
        Logger logger = getContext().getLog();
        String modelContext = command.analysisReport().analysis();

        CompletableFuture.supplyAsync(() -> {
                    try {
                        return writeContent(command.analysisReport().query(), modelContext, model, logger);
                    } catch (Exception e) {
                        getContext().getLog().error("Research failed for {}: {}",
                                modelContext, e.getMessage());
                        throw new RuntimeException("Research failed", e);
                    }
                })
                .thenAccept(content -> {
                    getContext().getSelf().tell(new WriteContentCompleted(content, command.replyTo()));
                })
                .exceptionally(ex -> {
                    getContext().getSelf().tell(new WriteContentFailed(
                            command.analysisReport().query(), ex.getMessage(), command.replyTo()));
                    return null;
                });


        return this;
    }

    private Models.WriteContentResult writeContent(String query, String modelContext, String model, Logger logger) {
        
        String userPrompt = """
                # CONTEXT
                %s
                
                # TASK
                Create an authoritative, in-depth article based on the research and analysis provided in the context.
                
                Your article should be comprehensive, engaging, and professionally structured. Aim for approximately 5,000-8,000 words
                of substantial content that thoroughly explores the topic and provides valuable insights to readers.
                
                The article should include:
                
                1. An engaging introduction that establishes context, significance, and scope (500+ words)
                
                2. A well-organized body with clear sections and subsections:
                   - Develop each major point with sufficient depth (500+ words per major section)
                   - Include concrete examples, case studies, and specific details
                   - Present multiple perspectives and balanced viewpoints
                   - Explain complex concepts thoroughly but accessibly
                   - Address counterarguments and limitations
                   - Use transitional elements to maintain flow between sections
                
                3. Rich supporting elements (described in text form):
                   - Statistical data with interpretation
                   - Expert viewpoints with direct quotes where available
                   - Detailed examples and case studies
                   - Frameworks for understanding complex concepts
                
                4. A substantial conclusion that synthesizes key insights and looks forward (500+ words)
                
                5. Consider adding these additional elements to enhance depth:
                   - Technical deep-dive sections for knowledgeable readers
                   - "Future implications" section with evidence-based predictions
                   - "Practical applications" section with real-world examples
                   - "Limitations and challenges" section with nuanced discussion
                
                Write in a professional, authoritative tone appropriate for an educated audience while keeping the content
                accessible and engaging. Balance breadth with depth, ensuring comprehensive coverage while maintaining
                reader interest through compelling writing and varied structure.
                Ensure all relevant links and citations from the context are included in the output in markdown format.
                """.formatted(modelContext);
        String systemPrompt = """
                You are an exceptional writer with expertise in creating long-form, in-depth content.
                You know how to structure complex information into readable, engaging narratives that maintain reader interest
                despite the depth and detail. You excel at explaining complex concepts clearly and compellingly,
                and you always ensure your writing is substantiated with evidence.
               """;


        try {
            String responseContent = llmService.getChatCompletion(model, systemPrompt, userPrompt);

            return new Models.WriteContentResult(query, responseContent);
        } catch (Exception e) {
            logger.error("Error writing content: {}", e.getMessage());
            return new Models.WriteContentResult(query, "Write content failed: " + e.getMessage()
            );
        }
    }


    private Behavior<Command> onWriteContentCompleted(WriteContentCompleted msg) {
        getContext().getLog().info("Write Content Agent completed successfully.");

        msg.replyTo().tell(new ResearchCoordinator.WriteContentCompleted(msg.writeContentResult()));
        return this;
    }

    private Behavior<Command> onWriteContentFailed(WriteContentFailed msg) {
        getContext().getLog().warn("Write Content Agent  failed: {}", msg.reason());

        msg.replyTo().tell(new ResearchCoordinator.WriteContentCompleted(
                new Models.WriteContentResult(msg.query(), "Research failed")
        ));

        return this;
    }

}