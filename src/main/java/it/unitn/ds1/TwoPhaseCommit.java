package it.unitn.ds1;

import akka.actor.*;
import scala.concurrent.duration.Duration;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class TwoPhaseCommit {
  final static int N_REPLICAS = 3;
  final static int VOTE_TIMEOUT = 1000;      // timeout for the votes, ms
  final static int DECISION_TIMEOUT = 2000;  // timeout for the decision, ms
  final static int N_CLIENT = 2;

  // the votes that the participants will send (for testing)
  final static Vote[] predefinedVotes =
          new Vote[] {Vote.YES, Vote.YES, Vote.YES}; // as many as N_PARTICIPANTS

  // Start message that sends the list of participants to everyone
  public static class StartMessage implements Serializable {
    public final List<ActorRef> group;
    public StartMessage(List<ActorRef> group) {
      this.group = Collections.unmodifiableList(new ArrayList<>(group));
    }
  }

  public static class ReadValueRequest implements Serializable {}

  public static class ReadValueResponse implements Serializable {
    public final int value;
    public ReadValueResponse(int value) {
      this.value = value;
    }
  }

  public static class WriteValueRequest implements Serializable {
    public final int newValue;
    public WriteValueRequest(int newValue) {
      this.newValue = newValue;
    }
  }

  public static class UpdateRequest implements Serializable {
    public final int newValue;
    public UpdateRequest(int newValue) {
      this.newValue = newValue;
    }
  }

  public static class UpdateMessage implements Serializable {
    public final int newValue;
    public UpdateMessage(int newValue) {
      this.newValue = newValue;
    }
  }

  public static class Acknowledge implements Serializable {}

  public static class WriteOk implements Serializable {}

  public static class Heartbeat implements Serializable {
    public final String message = "Coordinator is alive";
  }

 /* public static class RequestReadValue implements Serializable {
    int random = new Random().nextInt(N_REPLICAS + 1);         // replica selezionata per effettuare operazione di read o write.

    public RequestReadValue(ActorRef replica) {

    }
  }*/

  public enum Vote {NO, YES}
  public enum Decision {ABORT, COMMIT}

  public static class VoteRequest implements Serializable {}

  public static class VoteResponse implements Serializable {
    public final Vote vote;
    public VoteResponse(Vote v) { vote = v; }
  }

  public static class DecisionRequest implements Serializable {}

  public static class DecisionResponse implements Serializable {
    public final Decision decision;
    public DecisionResponse(Decision d) { decision = d; }
  }

  public static class Timeout implements Serializable {
    private String type; // Type of the timeout to identify the cause

    public Timeout(String type) {
      if (type == null) {
        throw new IllegalArgumentException("Timeout type cannot be null");
      }
      this.type = type;
    }

    public String getType() {
      return type;
    }
  }

  public static class Recovery implements Serializable {}

  /*-- Common functionality for both Coordinator and Participants ------------*/

  public abstract static class Node extends AbstractActor {
    protected int id; // node ID
    protected int v;  // value replica
    protected boolean isCrashed = false;  // track crash state
    protected List<ActorRef> participants;      // list of participant nodes
    protected Decision decision = null;         // decision taken by this node

    public Node(int id, int v) {
      super();
      this.id = id;
      this.v = v;
    }

    // abstract method to be implemented in extending classes
    //protected abstract void onRecovery(Recovery msg);

    void setGroup(StartMessage sm) {
      participants = new ArrayList<>();
      for (ActorRef b: sm.group) {
        if (!b.equals(getSelf())) {

          // copying all participant refs except for self
          this.participants.add(b);
        }
      }
      print("starting with " + sm.group.size() + " peer(s)");
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
              //.match(Recovery.class, this::onRecovery)
              .matchAny(msg -> {
                if (isCrashed) {
                  System.out.println(id + ": Crashed, ignoring message.");
                }
              })
              .build();
    }

    // emulate crash with no recover
    public void crash() {
      isCrashed = true;
      getContext().become(crashed());
      System.out.println(id + ": Node crashed.");
    }

    // emulate a crash and a recovery in a given time
    /*void crash(int recoverIn) {
      getContext().become(crashed());
      print("CRASH!!!");

      // setting a timer to "recover"
      getContext().system().scheduler().scheduleOnce(
          Duration.create(recoverIn, TimeUnit.MILLISECONDS),
          getSelf(),
          new Recovery(), // message sent to myself
          getContext().system().dispatcher(), getSelf()
          );
    }*/

    // emulate a delay of d milliseconds
    void delay(int d) {
      try {Thread.sleep(d);} catch (Exception ignored) {}
    }

    void multicast(Serializable m) {
      for (ActorRef p: participants)
        p.tell(m, getSelf());
    }

    // a multicast implementation that crashes after sending the first message
    void multicastAndCrash(Serializable m, int recoverIn) {
      for (ActorRef p: participants) {
        p.tell(m, getSelf());
        //crash(recoverIn); return;
      }
    }

    // schedule a Timeout message in specified time
    void setTimeout(int time) {
      getContext().system().scheduler().scheduleOnce(
              Duration.create(time, TimeUnit.MILLISECONDS),
              getSelf(),
              new Timeout(""), // the message to send
              getContext().system().dispatcher(), getSelf()
      );
    }

    // fix the final decision of the current node
    void fixDecision(Decision d) {
      if (!hasDecided()) {
        this.decision = d;
        print("decided " + d);
      }
    }

    boolean hasDecided() { return decision != null; } // has the node decided?

    // a simple logging function
    void print(String s) {
      System.out.format("%2d: %s\n", id, s);
    }

    /*@Override
    public Receive createReceive() {

      // Empty mapping: we'll define it in the inherited classes
      return receiveBuilder().build();
    }*/

    public Receive crashed() {
      return receiveBuilder()
              //  .match(Recovery.class, this::onRecovery)
              .matchAny(msg -> {})
              .build();
    }

    public void onDecisionRequest(DecisionRequest msg) {  /* Decision Request */
      if (hasDecided())
        getSender().tell(new DecisionResponse(decision), getSelf());

      // just ignoring if we don't know the decision
    }
  }

  /*-- Main ------------------------------------------------------------------*/
  public static void main(String[] args) {

    // Create the actor system
    final ActorSystem system = ActorSystem.create("helloakka");

    // Create the coordinator
    ActorRef coordinator = system.actorOf(Coordinator.props(), "coordinator");

    // Create participants
    List<ActorRef> replicas = new ArrayList<>();
    for (int i = 0; i< N_REPLICAS; i++) {
      replicas.add(system.actorOf(Replica.props(i, -1), "participant" + i));
    }

    // Create participants
    List<ActorRef> clients = new ArrayList<>();
    for (int i = 0; i< N_CLIENT; i++) {
      clients.add(system.actorOf(Client.props(i, -1), "client" + i));
    }

    // Send start messages to the replicas to inform them of the replicas
    StartMessage start = new StartMessage(replicas);
    for (ActorRef peer: replicas) {
      peer.tell(start, null);
    }

    // Send start messages to the clients to inform them of the replicas
    //  StartMessage startclient = new StartMessage(clients);
    for (ActorRef peer: clients) {
      peer.tell(start, null);
    }

    // Send the start messages to the coordinator
    coordinator.tell(start, null);


    /*clients.forEach(client -> {
      ((ActorRef) client).tell(new ReadValueRequest(), null);
    });*/


    for (ActorRef c: clients) {
      c.tell(new ReadValueRequest(), null);
    }

    clients.get(0).tell(new WriteValueRequest(15), clients.get(0));



    try {
      System.out.println(">>> Press ENTER to exit <<<");
      System.in.read();
    }
    catch (IOException ignored) {}
    system.terminate();
  }
}
