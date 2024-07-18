package it.unitn.ds1.Nodes;

import akka.actor.ActorRef;
import it.unitn.ds1.SupportingClasses.Messages;
import scala.concurrent.duration.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import akka.actor.AbstractActor;
import java.io.Serializable;


public abstract class Node extends AbstractActor {
    protected int id; // node ID
    protected int v;  // value replica
    protected boolean isCrashed = false;  // track crash state
    protected List<ActorRef> participants;      // list of participant nodes
    protected final Random random = new Random();

    // settings for behaviour and probability of nodes.
    protected final boolean RANDOM_CRASH = true;
    protected final double CRASHREPLICA_PROBABILITY = 0.03;
    protected final double CRASHCOORDINATOR_PROBABILITY = 0.02;
    protected final double RANDOMOPERATION_PROBABILITY = 0.05;

    public Node(int id) {
        super();
        this.id = id;
    }

    public Node(int id, int v) {
        super();
        this.id = id;
        this.v = v;
    }

    public Node(int id, int v,int epoch) {
        super();
        this.id = id;
        this.v = v;
        //this.epoch=epoch;
    }

    void setGroup(Messages.StartMessage sm) {
        participants = new ArrayList<>();
        this.participants.addAll(sm.group);
        print("starting with " + sm.group.size() + " peer(s)");
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .matchAny(msg -> {
                    if (isCrashed) {
                        System.out.println(id + ": Crashed, ignoring message.");
                    }
                })
                .build();
    }

    public List<ActorRef> getParticipants() {
        return participants;
    }

    // emulate a delay of d milliseconds
    void delay(int d) {
        try {Thread.sleep(d);} catch (Exception ignored) {}
    }

    void multicast(Serializable m, ActorRef self) {
        for (ActorRef p: participants)
            p.tell(m, getSelf());
    }

    // a multicast implementation that crashes after sending the first message
    /*void multicastAndCrash(Serializable m, int recoverIn) {
        for (ActorRef p: participants) {
            p.tell(m, getSelf());
            //crash(recoverIn); return;
        }
    }*/

    // schedule a Timeout message in specified time
    void setTimeout(int time) {
        getContext().system().scheduler().scheduleOnce(
                Duration.create(time, TimeUnit.MILLISECONDS),
                getSelf(),
                new Messages.Timeout(""), // the message to send
                getContext().system().dispatcher(), getSelf()
        );
    }

    void print(String s) {
        System.out.format("%2d: %s\n", id, s);
    }

    public void onCrashedMsg(Messages.CrashMsg crashMsg) {
        crash();
    }

    // emulate crash with no recover
    public void crash() {
        isCrashed = true;
        if(this.id<0)
            System.out.println("Coordinator: Sono crashato!");
        else
            System.out.println("Replica " + this.id +": Sono crashato!");

        getContext().become(crashed());
    }

    protected boolean simulateCrash(double probability) { //simulate potential crashes based on a probability
        if (random.nextDouble() < probability) {
            crash();
            return true;
        } else {
            return false;
        }
    }

    public Receive crashed() {
        return receiveBuilder()
                .matchAny(msg -> {})
                .build();
    }

    public boolean isCrashed() {
        return isCrashed;
    }

    public void setCrashed(boolean crashed) {
        isCrashed = crashed;
    }

    private void removeActor(ActorRef actor) {
        participants.remove(actor);
    }

    public void onUpdateParticipants(Messages.UpdateParticipants msg) {
        removeActor(msg.actortoberemoved);
        System.out.println("Replica " + this.id + ": Updated participants list by removing: " + msg.actortoberemoved.path().name());
    }

    public void multicastUpdateParticipants(ActorRef actorToBeRemoved) {
        Messages.UpdateParticipants updateMsg = new Messages.UpdateParticipants(actorToBeRemoved);
        for (ActorRef participant : participants) {
             participant.tell(updateMsg, getSelf());
        }
        System.out.println("Sent UpdateParticipants to all replicas.");
    }

    /*public void onRequestStatus(TwoPhaseCommit.ReplicaStatus msg) { // not implemented, a possible feature to request the status of the node/replica when this message arrive.
        TwoPhaseCommit.ReplicaStatus status = new TwoPhaseCommit.ReplicaStatus(v, epoch, seqn, isCrashed);
        //getSender().tell(new Replicastat(status), getSelf());
    }*/
}
