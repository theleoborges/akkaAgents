package com.theleoborges.researchagent.web;

import akka.NotUsed;
import akka.actor.typed.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.marshalling.sse.EventStreamMarshalling;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.sse.ServerSentEvent;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;

import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import akka.japi.Pair;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.*;
import akka.util.ByteString;

import akka.http.javadsl.marshallers.jackson.Jackson;
import com.theleoborges.researchagent.actors.ResearchCoordinator;
import com.theleoborges.researchagent.models.Models;

public class AkkaSSEServer extends AllDirectives {

    private final ActorSystem<ResearchCoordinator.Command> system;

    public AkkaSSEServer() {
        this.system = ActorSystem.create(ResearchCoordinator.create(), "ResearchAgentSystem");
    }

    public static void main(String[] args) throws Exception {
        // boot up server using the route as defined below
        //In order to access all directives we need an instance where the routes are define.
        AkkaSSEServer app = new AkkaSSEServer();



        final Http http = Http.get(app.system);


        final CompletionStage<ServerBinding> binding =
                http.newServerAt("localhost", 8080)
                        .bind(app.createRoute());

        System.out.println("Server online at http://localhost:8080/\nPress RETURN to stop...");
        System.in.read(); // let it run until user presses return

        binding
                .thenCompose(ServerBinding::unbind) // trigger unbinding from the port
                .thenAccept(unbound -> app.system.terminate()); // and shutdown when done
    }

    private record ResearchRequest(String topic) {}

    private Route createRoute() {
        // streams are re-usable so we can define it here
        // and use it for every request

        return concat(
                path("start", () ->
                        post(() ->
                                entity(Jackson.unmarshaller(ResearchRequest.class), request -> {
                                    // Create a source that will emit status updates
                                    Pair<SourceQueueWithComplete<ServerSentEvent>, Source<ServerSentEvent, NotUsed>> statusSource = createStatusSource();
                                    Source<ServerSentEvent, NotUsed> sseSource = statusSource.second();
                                    SourceQueueWithComplete<ServerSentEvent> queueSource = statusSource.first();


                                    String defaultTopic = "The impact to developer productivity and code quality of using AI coding copilots like Github Copilot, Cursor and Windsurf IDE.";
                                    system.tell(new ResearchCoordinator.StartResearch(
                                            new Models.SearchRequest(defaultTopic, 5, Optional.of(queueSource))
                                    ));

                                    // Return the streaming response

                                    return complete(
                                            StatusCodes.OK,
                                            sseSource,
                                            EventStreamMarshalling.toEventStream()
                                    );
                                })
                        )
                )
                );
    }

    private Pair<SourceQueueWithComplete<ServerSentEvent>, Source<ServerSentEvent, NotUsed>> createStatusSource() {
        return Source.<ServerSentEvent>queue(100, OverflowStrategy.dropHead())
                .toMat(BroadcastHub.of(ServerSentEvent.class), Keep.both()).run(this.system);
    }
}
