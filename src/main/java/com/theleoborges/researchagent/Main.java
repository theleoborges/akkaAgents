package com.theleoborges.researchagent;

import akka.actor.typed.ActorSystem;
import com.theleoborges.researchagent.actors.ResearchCoordinator;
import com.theleoborges.researchagent.mcp.servers.BraveServer;
import com.theleoborges.researchagent.models.Models;
import io.modelcontextprotocol.server.transport.StdioServerTransport;
import org.apache.commons.cli.*;


import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

public class Main {
    public static void main(String[] args) {
        String defaultTopic = "The impact to developer productivity and code quality of using AI coding copilots like Github Copilot, Cursor and Windsurf IDE.";

        CommandLine arguments = parseCommandLineArgs(args);
        System.err.println(Arrays.toString(args));

        String topic = arguments.hasOption("topic")  ?  arguments.getOptionValue("topic") : defaultTopic;
        String mode  = arguments.hasOption("server") ? "server" : "client";

        // Exit if the user provides the help argument
        if (arguments.hasOption("help")) {
            return;
        }

        System.err.println("System mode: " + mode);
        if (mode.equals("client")) {
            System.err.println("Starting system in client mode...");
            // Create the actor system with ResearchCoordinator as the guardian actor
            ActorSystem<ResearchCoordinator.Command> system =
                    ActorSystem.create(ResearchCoordinator.create(), "ResearchAgentSystem");

            system.tell(new ResearchCoordinator.StartResearch(
                    new Models.SearchRequest(topic, 5, Optional.empty())
            ));

            // Terminate after a timeout
            try {
                Thread.sleep(Duration.ofMinutes(10).toMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                system.terminate();
            }

        }

        if (mode.equals("server")) {
            System.err.println("Starting system in server mode...");
            StdioServerTransport serverTransport = new StdioServerTransport();
            BraveServer braveServer = new BraveServer(serverTransport);

            Runtime.getRuntime().addShutdownHook(new Thread(braveServer::close));

            while (true) {}


        }

    }

    private static CommandLine parseCommandLineArgs(String[] args) {
        Options options = new Options()
                .addOption(Option.builder()
                        .longOpt("help")
                        .desc("print this message")
                        .build())
                .addOption(Option.builder()
                        .longOpt("topic")
                        .hasArg()
                        .argName("researchTopic")
                        .desc("Topic to research")
                        .build())
                .addOption(Option.builder()
                        .longOpt("server")
                        .desc("Starts MCP Server. Ignores all other arguments")
                        .build());

        try {
            CommandLine parse = new DefaultParser().parse(options, args);
            if (parse.hasOption("help")) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp("akkaAgents", options);
            }
            return parse;
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }


}