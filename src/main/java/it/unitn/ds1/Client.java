package it.unitn.ds1;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;

import java.io.Serializable;
import java.util.Random;

import static it.unitn.ds1.TwoPhaseCommit.*;

/*-- Client -----------------------------------------------------------*/
public class Client extends Node {
    public Client(int id, int v) { super(id, v); }

    static public Props props(int id, int v) {
        return Props.create(Client.class, () -> new Client(id, v));
    }

    public void onStartMessage(StartMessage msg) {
        //System.out.printf("ciao\n\n");
        // replica[random]
        setGroup(msg);
    }

    public void requestRead(ReadValueRequest msg) {
        int selectedRandReplica = new Random().nextInt(participants.size());  // replica selezionata per effettuare operazione di read o write.
        ActorRef selectedReplica = participants.get(selectedRandReplica);
        selectedReplica.tell(new ReadValueRequest(), getSelf());
        print("Requested read from replica " + selectedReplica);
    }

    public void requestWrite(int newValue) {
        int selectedReplicaIndex = new Random().nextInt(participants.size());
        ActorRef selectedReplica = participants.get(selectedReplicaIndex);
        selectedReplica.tell(new WriteValueRequest(newValue), getSelf());
        print("Write request sent to replica " + selectedReplicaIndex + " with new value: " + newValue);
    }


    public void onTimeout(Timeout msg) {
        if (!hasDecided()) {
            print("Timeout. Replica has crashed.");

            // ask other participants
            multicast(new DecisionRequest());

            // ask also the coordinator
            //coordinator.tell(new DecisionRequest(), getSelf());
            //setTimeout(DECISION_TIMEOUT);
        }
    }



    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(StartMessage.class, this::onStartMessage)
                .match(ReadValueRequest.class, this::requestRead)
                .match(WriteValueRequest.class, req -> requestWrite(req.newValue))
                .match(Timeout.class, this::onTimeout)
                .build();
    }


      /*
      public void onStartMessage(StartMessage msg) {
        setGroup(msg);
      }

        */

}
