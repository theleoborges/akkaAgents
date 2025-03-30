package com.theleoborges.researchagent.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.sendgrid.*;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import com.sendgrid.helpers.mail.objects.Attachments;
import com.theleoborges.researchagent.models.Models;
import com.theleoborges.researchagent.tools.LLMService;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.xhtmlrenderer.pdf.ITextRenderer;

import org.slf4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

//import com.sendgrid.*;

public class EmailAgent extends AbstractBehavior<EmailAgent.Command> {

    private final LLMService llmService;

    // Command interface for the ReportAgent
    public sealed interface Command {}

    // Command to generate a final report
    public record SendReport(
            String recipient, String subject, String body,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}

    // Internal message for when report generation is complete
    private record EmailSent(
            String recipient,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}

    // Internal message for handling failures
    private record SendFailed(
            String reason,
            ActorRef<ResearchCoordinator.Command> replyTo
    ) implements Command {}

    // Factory method to create the actor behavior with timeout
    public static Behavior<Command> create(Duration timeout) {
        return Behaviors.setup(context -> new EmailAgent(context, timeout));
    }

    // Constructor
    private EmailAgent(ActorContext<Command> context, Duration timeout) {
        super(context);
        this.llmService = new LLMService(timeout);
        context.getLog().info("Email Agent started with timeout: {}", timeout);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(SendReport.class, this::onSendReport)
                .onMessage(EmailSent.class, this::onEmailSent)
                .onMessage(SendFailed.class, this::onSendFailed)
                .build();
    }

    private Behavior<Command> onSendReport(SendReport command) {

        Logger logger = getContext().getLog();
        logger.info("Emailing final report");

        String model = getContext().getSystem().settings().config().getString("research-agent.apis.openai.model");

        CompletableFuture.supplyAsync(() -> {
                    try {
                        return sendReport(command, model, logger);
                    } catch (Exception e) {
                        logger.error("Failed to send email: {}", e.getMessage());
                        throw new RuntimeException("Failed to send email", e);
                    }
                })
                .thenAccept(finalReport -> {
                    getContext().getSelf().tell(new EmailSent(command.recipient(), command.replyTo()));
                })
                .exceptionally(ex -> {
                    getContext().getSelf().tell(new SendFailed(ex.getMessage(), command.replyTo()));
                    return null;
                });

        return this;
    }

    private Behavior<Command> onEmailSent(EmailSent message) {
        getContext().getLog().info("Email sent to: {}", message.recipient());

        // Notify coordinator send is complete
        message.replyTo().tell(new ResearchCoordinator.SendEmailCompleted(message.recipient()));

        return this;
    }

    private Behavior<Command> onSendFailed(SendFailed message) {
        getContext().getLog().error("Email send failed: {}", message.reason());

        message.replyTo().tell(new ResearchCoordinator.SendEmailCompleted(message.reason()));

        return this;
    }


    private Path createPDF(String markdownString, Logger logger) {

        // Convert Markdown to HTML
        Parser parser = Parser.builder().build();
        HtmlRenderer htmlRenderer = HtmlRenderer.builder().build();
        String htmlString = "<html>" + htmlRenderer.render(parser.parse(markdownString)) + "</html>";

        ITextRenderer pdfRenderer = new ITextRenderer();
        pdfRenderer.setDocumentFromString(htmlString);
        pdfRenderer.layout();

        // Create output file and write PDF
        Path path = Paths.get("results").toAbsolutePath().resolve("finalReport.pdf").toAbsolutePath();
        try (OutputStream outputStream = new FileOutputStream(path.toString())) {
            pdfRenderer.createPDF(outputStream);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        logger.info("PDF created successfully at: {}", path);

        return path;
    }

    private Attachments createPDFAttachment(String markdownString, Logger logger) {
        try {
            byte[] bytes = Files.readAllBytes(createPDF(markdownString, logger));

            return new Attachments.Builder("finalReport.pdf",
                    Base64.getEncoder().encodeToString(bytes))
                    .withType("text/pdf")
                    .withContentId("finalReport")
                    .withDisposition("attachment")
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private Models.EmailResult sendReport(SendReport command, String model, Logger logger) {
        try {
            SendGrid sendGrid = new SendGrid(System.getenv("SENDGRID_API_KEY"));

            Email from = new Email(System.getenv("SENDGRID_SENDER_EMAIL"), System.getenv("SENDGRID_SENDER_NAME"));
            Email to = new Email(command.recipient());

            Content content = new Content("text/plain", "Final report attached. Have fun!");

            Mail mail = new Mail(from, command.subject, to, content);
            mail.addAttachments(createPDFAttachment(command.body(), logger));

            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGrid.api(request);

            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                logger.info("Email sent successfully");
                return new Models.EmailResult(command.recipient(), "Email sent successfully");
            } else {
                logger.error("Failed to send email: {}", response.getBody());
                return new Models.EmailResult(command.recipient(), "Failed to send email: " + response.getBody());
            }
        } catch (Exception e) {
            logger.error("Exception while sending email", e);
            return new Models.EmailResult(command.recipient(), "Failed to send email: " + e.getMessage());
        }

    }

}