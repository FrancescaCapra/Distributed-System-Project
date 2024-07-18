package it.unitn.ds1;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import it.unitn.ds1.Nodes.Replica;
import it.unitn.ds1.SupportingClasses.Messages;
import it.unitn.ds1.Nodes.Client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


public class Main {
    public final static int N_REPLICAS = 8;
    public final static int N_CLIENT = 4;

    public static void main(String[] args) {

        // Create the actor system
        final ActorSystem system = ActorSystem.create("Quorum-based_Total_Order_Broadcast");

        // Create the coordinator
        ActorRef coordinator = system.actorOf(Replica.props(-1));

        // Create the replicas
        List<ActorRef> replicas = new ArrayList<>();
        for (int i = 0; i< N_REPLICAS; i++) {
            replicas.add(system.actorOf(Replica.props(coordinator, i, -1), "replica_" + i ));
        }

        // Create participants
        List<ActorRef> clients = new ArrayList<>();
        for (int i = 0; i< N_CLIENT; i++) {
            clients.add(system.actorOf(Client.props(i, -1), "client_" + i ));
        }

        // Send start messages to the replicas to inform them of the replicas
        Messages.StartMessage start = new Messages.StartMessage(replicas);
        for (ActorRef peer: replicas) {
            peer.tell(start, null);
        }

        // Send start messages to the clients to inform them of the replicas
        for (ActorRef peer: clients) {
            peer.tell(start, null);
        }

        // Send the start messages to the coordinator
        coordinator.tell(new Messages.StartMessageCoord(replicas), null);

        for (ActorRef c: clients) {
            c.tell(new Messages.ReadValueRequest(), null);
        }

        clients.get(0).tell(new Messages.UpdateValueRequest(15), clients.get(0));

        try {
            Thread.sleep(7000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        replicas.get(0).tell(new Messages.CrashMsg(), replicas.get(0));

        try {
            Thread.sleep(16000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        coordinator.tell(new Messages.CrashMsg(), coordinator);

        try {
            Thread.sleep(16000);
            clients.get(1).tell(new Messages.ReadValueRequest(), clients.get(1));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        try {
            Thread.sleep(7000);
            clients.get(1).tell(new Messages.UpdateValueRequest(23), clients.get(1));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        try {
            System.out.println(">>> Press ENTER to exit <<<");
            /*for (ActorRef replica: replicas) {
                replica.tell(new StatusResponse(), replica);
            }*/
            System.in.read();
        }
        catch (IOException ignored) {}
        system.terminate();
    }
}
