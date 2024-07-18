package it.unitn.ds1.SupportingClasses;

import akka.actor.*;

import java.io.Serializable;
import java.util.*;

public class Messages {
  //start message che manda la lista delle repliche a tutti
  public static class StartMessage implements Serializable {
    public final List<ActorRef> group;
    public StartMessage(List<ActorRef> group) {
      this.group = Collections.unmodifiableList(new ArrayList<>(group));
    }
  }

  public static class StartMessageCoord implements Serializable {
    public final List<ActorRef> group;
    public StartMessageCoord(List<ActorRef> group) {
      this.group = Collections.unmodifiableList(new ArrayList<>(group));
    }
  }

  public static class UpdateParticipants implements Serializable {
    public ActorRef actortoberemoved;
    public UpdateParticipants(ActorRef actortoberemoved) {
      this.actortoberemoved = actortoberemoved;
    }
  }

  public static class CrashMsg implements Serializable {}

  public static class ReadValueRequest implements Serializable {}

  public static class ReadValueResponse implements Serializable {
    public final int value;
    public ReadValueResponse(int value) {
      this.value = value;
    }
  }

  public static class UnstableReadResponse implements Serializable {}

  //richiesta update fatta dalla replica
  public static class UpdateValueRequest implements Serializable {
    public final int newValue;
    public UpdateValueRequest(int newValue) {
      this.newValue = newValue;
    }
      @Override
      public String toString() {
          return "UpdateValueRequest{" +
                  "newValue=" + newValue +
                  '}';
      }
  }
  //il coordinator gestisce la richiesta
  public static class UpdateRequest implements Serializable {
    public final int newValue;
    public UpdateRequest(int newValue) {
      this.newValue = newValue;
    }
  }
  //risposta inviata dal coordinator
  public static class UpdateValueResponse implements Serializable {
    public final int newValue;
    public int epoch;
    public int sequenceNum;
    public UpdateValueResponse(int newValue, int epoch, int sequenceNumber) {
      this.newValue = newValue;
      this.epoch = epoch;
      this.sequenceNum = sequenceNumber;
    }
  }
  //la replica dice "ok, l'ho ricevuta"
  public static class Acknowledge implements Serializable {}

  //il coordinator dice "allora ok, tutti fate un update"
  public static class WriteOkResponse implements Serializable {}

  //il coordinator dice "sono vivo"
  public static class Heartbeat implements Serializable {
    public final String message = "Coordinator is alive";
  }

  public static class CrashReplicaDetectByClient implements Serializable {
  }
  public static class AcknowledgeElectionReceived implements Serializable {
    private final int senderId;
    public AcknowledgeElectionReceived(int senderId) {
      this.senderId = senderId;
    }
    public int getSenderId() {
      return senderId;
    }
  }

  public static class ReplicaStatus implements Serializable {
    private final int value;
    private final int epoch;
    private final int sequenceNumber;
    private final boolean isCrashed;

    public ReplicaStatus(int value, int epoch, int sequenceNumber, boolean isCrashed) {
      this.value = value;
      this.epoch = epoch;
      this.sequenceNumber = sequenceNumber;
      this.isCrashed = isCrashed;
    }

    @Override
    public String toString() {
      return "ReplicaStatus{" +
              "value=" + value +
              ", epoch=" + epoch +
              ", sequenceNumber=" + sequenceNumber +
              ", isCrashed=" + isCrashed +
              '}';
    }
  }

  public static class StatusResponse implements Serializable {
    private final ReplicaStatus status;

    public StatusResponse(ReplicaStatus status) {
      this.status = status;
    }

    public ReplicaStatus getStatus() {
      return status;
    }
  }

  public static class Timeout implements Serializable {
    private String type; // Type of the timeout to identify the cause
    private ActorRef crashed; // node crashed
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

  public static class SynchronizationMessage implements Serializable {
    public final int coordinatorId;
    public final int epoch;
    public final int seqn;
    public final int currentValue;

    public SynchronizationMessage(int coordinatorId, int epoch, int seqn, int currentValue) {
      this.coordinatorId = coordinatorId;
      this.epoch = epoch;
      this.seqn = seqn;
      this.currentValue = currentValue;
    }
  }

}
