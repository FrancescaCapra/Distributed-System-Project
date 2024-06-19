package it.unitn.ds1;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import scala.concurrent.duration.Duration;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static it.unitn.ds1.TwoPhaseCommit.*;

/*-- Coordinator -----------------------------------------------------------*/

public class Coordinator extends Node {

    // here all the nodes that sent YES are collected
    private final Set<ActorRef> yesVoters = new HashSet<>();
    private Set<ActorRef> ackVoters = new HashSet<>();
    private int pendingValue = 0;  // This holds the value pending confirmation
    private boolean writeConfirmed = false;  // To track if the write confirmation has been sent
    private Cancellable heartbeatTask;  // To manage the heartbeat sending task

    boolean allVotedYes() { // returns true if all voted YES
        return yesVoters.size() >= N_REPLICAS;
    }

    public Coordinator() {
        super(-1, -1); // the coordinator has the id -1
    }

    static public Props props() {
        return Props.create(Coordinator.class, Coordinator::new);
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(StartMessage.class, this::onStartMessage)
                .match(UpdateRequest.class, this::onUpdateRequest)
                .match(Acknowledge.class, this::onAcknowledge)
                .match(VoteResponse.class, this::onVoteResponse)
                .match(Timeout.class, this::onTimeout)
                .match(DecisionRequest.class, this::onDecisionRequest)
                .build();
    }

    // This method sets up a task to send heartbeat messages
    private void startHeartbeat() {
        heartbeatTask = getContext().getSystem().scheduler().scheduleAtFixedRate(
                Duration.create(5, TimeUnit.SECONDS), // Start after 5 seconds
                Duration.create(5, TimeUnit.SECONDS), // Repeat every 5 seconds
                this::sendHeartbeat,
                getContext().getSystem().dispatcher()
        );
    }

    // Sends a heartbeat to all participants except itself
    private void sendHeartbeat() {
        for (ActorRef participant : participants) {
            if (!participant.equals(getSelf())) {
                participant.tell(new Heartbeat(), getSelf());
            }
        }
    }

    @Override
    public void preStart() {
        startHeartbeat();  // Start sending heartbeats when the actor starts
    }

    public void onStartMessage(StartMessage msg) {                   /* Start */
        setGroup(msg);
        print("Sending vote request");
        multicast(new VoteRequest());
        //multicastAndCrash(new VoteRequest(), 3000);
        setTimeout(VOTE_TIMEOUT);
        //crash(5000);
    }

    public void onUpdateRequest(UpdateRequest msg) {
        pendingValue = msg.newValue;
        ackVoters.clear();
        multicast(new UpdateMessage(msg.newValue));
        setTimeout(VOTE_TIMEOUT);
    }

    public void onAcknowledge(Acknowledge msg) {
        ackVoters.add(getSender());
        if (!writeConfirmed && ackVoters.size() >= (N_REPLICAS / 2) + 1) {
            // Iterate over the participants and send the message only to replicas
            for (ActorRef participant : participants) {
                if (!participant.equals(getSelf())) {  // Check to ensure not sending to self
                    participant.tell(new WriteOk(), getSelf());
                }
            }
            writeConfirmed = true;  // Set the flag to prevent further messages
            print("Write operation confirmed. Value updated to " + pendingValue);
        }
    }


    public void onVoteResponse(VoteResponse msg) {                    /* Vote */
        if (hasDecided()) {

            // we have already decided and sent the decision to the group,
            // so do not care about other votes
            return;
        }
        Vote v = (msg).vote;
        if (v == Vote.YES) {
            yesVoters.add(getSender());
            if (allVotedYes()) {
                fixDecision(Decision.COMMIT);
                //if (id==-1) {crash(3000); return;}
                multicast(new DecisionResponse(decision));
                //multicastAndCrash(new DecisionResponse(decision), 3000);
            }
        }
        else { // a NO vote

            // on a single NO we decide ABORT
            fixDecision(Decision.ABORT);
            multicast(new DecisionResponse(decision));
        }
    }

    public void onTimeout(Timeout msg) {
        if (!hasDecided()) {
            print("Timeout");

            // not decided in time means ABORT
            fixDecision(Decision.ABORT);
            multicast(new DecisionResponse(Decision.ABORT));
        }
    }

    /*@Override
    public void onRecovery(Recovery msg) {
      getContext().become(createReceive());
      if (!hasDecided()) {
        print("recovering, not decided");

        // store the decision
        fixDecision(Decision.ABORT);
      }
      else {
        print("recovering, decided before crash");
      }

      multicast(new DecisionResponse(decision));
    }*/
}


