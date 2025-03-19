package com.theleoborges.researchagent;

import akka.actor.typed.ActorSystem;
import com.theleoborges.researchagent.actors.ResearchCoordinator;
import com.theleoborges.researchagent.models.Models.SearchRequest;


import java.time.Duration;

public class Main {

    public static void main(String[] args) {
        String defaultTopic = "The impact to developer productivity and code quality of using AI coding copilots like Github Copilot, Cursor and Windsurf IDE.";
        String topic = args.length > 0 ? args[0] : defaultTopic;

        // Create the actor system with ResearchCoordinator as the guardian actor
        ActorSystem<ResearchCoordinator.Command> system =
                ActorSystem.create(ResearchCoordinator.create(), "ResearchAgentSystem");

        system.tell(new ResearchCoordinator.StartResearch(
                new SearchRequest(topic, 5)
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
}