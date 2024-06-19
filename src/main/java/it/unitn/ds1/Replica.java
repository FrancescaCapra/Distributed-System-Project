package it.unitn.ds1;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import scala.concurrent.duration.Duration;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;


import static it.unitn.ds1.TwoPhaseCommit.*;
/*-- Replica -----------------------------------------------------------*/
public class Replica extends Node {
    ActorRef coordinator;
    private boolean awaitingWriteOk = false;
    private Cancellable heartbeatTimeout;
    private Cancellable writeOkTimeout;

    public Replica(int id, int v) { super(id, v); }

    static public Props props(int id, int v) {
        return Props.create(Replica.class, () -> new Replica(id, v));
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(StartMessage.class, this::onStartMessage)
                .match(VoteRequest.class, this::onVoteRequest)
                .match(ReadValueRequest.class, this::onReadValueRequest)
                .match(WriteValueRequest.class, this::onWriteValueRequest)
                .match(UpdateMessage.class, this::onUpdateMessage)
                .match(WriteOk.class, this::onWriteOk)
                .match(DecisionRequest.class, this::onDecisionRequest)
                .match(DecisionResponse.class, this::onDecisionResponse)
                .match(Timeout.class, this::onTimeout)
                .match(Heartbeat.class, this::onHeartbeat)
                .build();
    }

    public void onHeartbeat(Heartbeat msg) {
        System.out.println("Replica " + this.id + ": Heartbeat received from Coordinator.");
        if (heartbeatTimeout != null) {
            heartbeatTimeout.cancel();
        }
        heartbeatTimeout = getContext().getSystem().scheduler().scheduleOnce(
                Duration.create(20, TimeUnit.SECONDS),  // Duration after which to send the message
                getSelf(),                              // The Actor to receive the message
                new Timeout("Heartbeat"),               // The message to send
                getContext().getSystem().dispatcher(),  // The execution context
                getSelf()                               // Sender of the message
        );
    }

    public void onStartMessage(StartMessage msg) {
        setGroup(msg);
    }

    public void onReadValueRequest(ReadValueRequest msg) {
        getSender().tell(new ReadValueResponse(v), getSelf()); // Assuming `v` is the value to be read
        print("Read request processed, value sent: " + v);
    }

    public void onWriteValueRequest(WriteValueRequest msg) {
        this.coordinator.tell(new UpdateRequest(msg.newValue), getSelf());
        System.out.println("Replica " + this.id + ": Update request sent to Coordinator with new value: " + msg.newValue);
        if (writeOkTimeout != null) {
            writeOkTimeout.cancel();
        }
        writeOkTimeout = getContext().system().scheduler().scheduleOnce(
                Duration.create(10, TimeUnit.SECONDS),
                getSelf(),
                new Timeout("WriteOk"),
                getContext().system().dispatcher(),
                getSelf()
        );
    }

    public void onUpdateMessage(UpdateMessage msg) {
        this.v = msg.newValue;
        getSender().tell(new Acknowledge(), getSelf());
        print("Value updated to " + msg.newValue + ", Ack sent.");
    }

    public void onWriteOk(WriteOk msg) {
        System.out.println("Replica " + this.id + ": Write OK confirmed by Coordinator.");
        if (writeOkTimeout != null) {
            writeOkTimeout.cancel();
            writeOkTimeout = null;
        }
    }

    public void onVoteRequest(VoteRequest msg) {
        this.coordinator = getSender();
        //if (id==2) {crash(5000); return;}    // simulate a crash
        //if (id==2) delay(4000);              // simulate a delay
        if (predefinedVotes[this.id] == Vote.NO) {
            fixDecision(Decision.ABORT);
        }
        print("sending vote " + predefinedVotes[this.id]);
        this.coordinator.tell(new VoteResponse(predefinedVotes[this.id]), getSelf());
        setTimeout(DECISION_TIMEOUT);
    }

    /*public void onTimeout(Timeout msg) {
      if (!hasDecided()) {
        print("Timeout. Asking around.");

        // ask other participants
        multicast(new DecisionRequest());

        // ask also the coordinator
        coordinator.tell(new DecisionRequest(), getSelf());
        setTimeout(DECISION_TIMEOUT);
      }
    }*/

    public void onTimeout(Timeout timeout) {
        switch (timeout.getType()) {
            case "Heartbeat":
                System.out.println("Timeout detected: No heartbeat from Coordinator. Assuming crash.");
                multicast(new DecisionRequest()); // Example action, adjust as needed.
                break;
            case "WriteOk":
                if (awaitingWriteOk) {
                    System.out.println("Timeout detected: No WriteOk received. Coordinator may be crashed.");
                    multicast(new DecisionRequest()); // Example action, adjust as needed.
                }
                break;
            default:
                System.out.println("Unhandled timeout for message type: " + timeout.getType());
                break;
        }
    }

    public void onDecisionResponse(DecisionResponse msg) { /* Decision Response */

        // store the decision
        fixDecision(msg.decision);
    }
}

