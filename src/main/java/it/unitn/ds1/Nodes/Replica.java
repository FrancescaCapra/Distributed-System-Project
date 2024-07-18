package it.unitn.ds1.Nodes;

import akka.actor.*;
import it.unitn.ds1.SupportingClasses.ElectionMessage;
import it.unitn.ds1.SupportingClasses.EpochSeqNumber;
import it.unitn.ds1.SupportingClasses.Messages;
import scala.concurrent.duration.Duration;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static it.unitn.ds1.Main.N_REPLICAS;

public class Replica extends Node {
    private static ActorRef coordinator;
    private Cancellable heartbeatTimeout;
    private Cancellable updateTimeout;
    private Cancellable writeOkTimeout;
    private Cancellable electionTimeout;
    private EpochSeqNumber esn;

    private boolean isEpochChanging = false; // Flag to indicate if an election is in progress
    private List<Messages.UpdateValueRequest> pendingUpdateMessages = new ArrayList<>(); // List for pending update messages
    public LinkedHashMap<EpochSeqNumber, Integer> epochSeq_valueMap = new LinkedHashMap<>();  // epochsequencenumber_value // mappa degli update di ciò che viene fatto su questa replica.

    public Replica(int id) {
        super(id); // Chiamata al costruttore della classe Node
        epochSeq_valueMap.put(new EpochSeqNumber(0,0), -1);
        esn = new EpochSeqNumber(0,0);
    }

    public Replica(int id, int v, ActorRef coord) {
        super(id, v); // Chiamata al costruttore della classe Node
        epochSeq_valueMap.put(new EpochSeqNumber(0,0), -1);
        esn = new EpochSeqNumber(0,0);
        coordinator = coord;
    }

    public static Props props(int id) { // coordinatore
        return Props.create(Replica.class, () -> new Replica(id));
    }

    public static Props props(ActorRef coord, int id, int v) {
        return Props.create(Replica.class, () -> new Replica(id, v, coord));
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(Messages.StartMessage.class, this::onStartMessage)
                .match(Messages.StartMessageCoord.class, this::onStartMessageCoord)
                .match(Messages.ReadValueRequest.class, this::onReadValueRequest)
                .match(Messages.UpdateValueRequest.class, this::onUpdateValueRequest) // update dal client
                .match(Messages.UpdateValueResponse.class, this::onUpdateValueResponse) //dal coordinatore
                .match(Messages.WriteOkResponse.class, this::onWriteOkResponse)
                //.match(CrashReplicaDetectByClient.class, this::onReplicaCrash) // nel caso si può implementare la ricezione del messaggio da parte del client che ci informa di una replica crashata, così da poterla rimuovere dai participants.
                .match(Messages.SynchronizationMessage.class, this::onSynchronizationMessage)
                .match(Messages.Timeout.class, this::onTimeout)
                .match(Messages.Heartbeat.class, this::onHeartbeat)
                .match(ElectionMessage.class, this::onElectionMessage)
                .match(Messages.AcknowledgeElectionReceived.class, this::onAcknowledgeElectionReceived)
                .match(Messages.CrashMsg.class, this::onCrashedMsg)
                .match(Messages.UpdateParticipants.class, this::onUpdateParticipants) // aggiorno la lista dei participants, cioè le repliche se non vedo ack ricevuto durante elezione
                //.match(ReplicaStatus.class, this::onRequestStatus)
                .build();
    }

    public Receive createReceiveCoord() {
        return receiveBuilder()
                .match(Messages.UpdateRequest.class, this::onUpdateRequest)
                .match(Messages.Acknowledge.class, this::onAcknowledge)
                .match(Messages.CrashMsg.class, this::onCrashedMsgCoord)
                .match(Messages.Timeout.class, this::onTimeoutCoord)
                .match(Messages.UpdateParticipants.class, this::onUpdateParticipants) // aggiorno la lista dei participants, cioè le repliche se non vedo l'acknoledgement dopo il mio update.
                .build();
    }

    public void onCrashedMsgCoord(Messages.CrashMsg crashMsg) {
        // Stop the heartbeat task
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            heartbeatTask.cancel();
            System.out.println("Heartbeat stopped due to coordinator crash.");
        }
        crash();
    }

    public void onHeartbeat(Messages.Heartbeat msg) {
        if(participants.size() > ((N_REPLICAS/2) + 1)){  // SE CI SONO ALMENO NREPLICHE / 2 + 1 RIMASTE IN VITA POSSO CRASHARE (anche se dovessero morire tutte contemporaneamente con una probabilità praticamente pari a 0 si gestisce nei metodi perchè non c'è il quorum.
            if(RANDOM_CRASH) {
                if(simulateCrash(CRASHREPLICA_PROBABILITY)) {  // Check for potential crash with a 4% chance on any message received
                    //System.out.println("RANDOM CRASH ACTIVATED ON REPLICA " + this.id + " participant size" + participants.size() + " NREPLICAS " + N_REPLICAS + " statement: " + (participants.size() > ((N_REPLICAS/2) + 1)) + " statement2" + (participants.size() > (N_REPLICAS/2 + 1)));
                    return;
                }
            }
        }

        System.out.println("Replica " + this.id + ": Heartbeat received from Coordinator.");
        if (heartbeatTimeout != null) {
            heartbeatTimeout.cancel();
        }

        int randtimeout = 12 + random.nextInt(9);
        heartbeatTimeout = getContext().getSystem().scheduler().scheduleOnce(
               // Duration.create(randtimeout, TimeUnit.SECONDS),
                Duration.create(randtimeout, TimeUnit.SECONDS),
                getSelf(),
                new Messages.Timeout("Heartbeat"),
                getContext().getSystem().dispatcher(),
                getSelf()
        );
    }

    public void onStartMessage(Messages.StartMessage msg) {  // replica
        setGroup(msg);
    }

    public void onStartMessageCoord(Messages.StartMessageCoord msgc) {  // coordinator
        getContext().become(createReceiveCoord());
        participants = new ArrayList<>();
        this.participants.addAll(msgc.group);
        System.out.println("Coordinator "+ this.id + ": starting with " + msgc.group.size() + " peer(s)");
        startHeartbeat();
    }

    private Set<ActorRef> ackVoters = new HashSet<>();
    private int pendingValue = 0;  // This holds the value pending confirmation
    private boolean writeConfirmed = false;  // To track if the write confirmation has been sent
    private Cancellable heartbeatTask;  // To manage the heartbeat sending task
    private Cancellable ackTimeout;

    // coordinator setta l'heartbeat
    private void startHeartbeat() {

        heartbeatTask = getContext().getSystem().scheduler().scheduleAtFixedRate(
                Duration.create(1, TimeUnit.SECONDS), // Start after 1 seconds
                Duration.create(10, TimeUnit.SECONDS), // Repeat every 10 seconds
                this::sendHeartbeat,
                getContext().getSystem().dispatcher()
        );
    }

    // coordinator manda heartbeat
    private void sendHeartbeat() {
        if(participants.size() > ((N_REPLICAS/2) + 1)) {  // SE CI SONO ALMENO NREPLICHE / 2 + 1 RIMASTE IN VITA io coordinator POSSO CRASHARE
            if (RANDOM_CRASH) {
                if (simulateCrash(CRASHCOORDINATOR_PROBABILITY)) { // Check for potential crash of the coordinator with a 2% chance on any message received
                    return;
                }
            }
        }
        for (ActorRef participant : participants) {
            if (!participant.equals(getSelf())) {
                participant.tell(new Messages.Heartbeat(), getSelf());
            }
        }
    }

    // ricevo la richiesta update da parte della replica e aggiorno il valore, poi comunico l'update da fare a tutte le repliche.
    public void onUpdateRequest(Messages.UpdateRequest msg) {
        pendingValue = msg.newValue;
        ackVoters.clear();
        writeConfirmed = false;

        esn.incrementSequenceNumber();

        //inserisco la nuova richiesta di update della replica nel mappa (log) come esn e pending value
        EpochSeqNumber esntmp= new EpochSeqNumber(esn.getEpoch(),esn.getSequenceNumber());
        epochSeq_valueMap.put(esntmp, pendingValue);

        System.out.println("Coordinator: update request reveiced - Sequence number: " + esntmp.getSequenceNumber() + ", Epoch: " + esntmp.getEpoch() + ", Pending value: " + pendingValue);

        multicast(new Messages.UpdateValueResponse(msg.newValue, esntmp.getEpoch(), esntmp.getSequenceNumber()), getSelf());

        if (ackTimeout != null) {
            ackTimeout.cancel();
        }
        ackTimeout = getContext().system().scheduler().scheduleOnce(
                Duration.create(10, TimeUnit.SECONDS),
                getSelf(),
                new Messages.Timeout("AckUpdate"),
                getContext().system().dispatcher(),
                getSelf()
        );
    }
    //coordinator controlla chi non ha mandato l'ack
    private void missingAckTO() {
        for (ActorRef participant : participants) {
            if ((!ackVoters.contains(participant)) && (!participant.equals(getSelf()))) {
                System.out.println("Coordinator: Replica "+ participant.path().name()+" did not send the acknowledge");
            }
        }
        writeConfirmed = true;
    }

    public void onAcknowledge(Messages.Acknowledge msg) {
        System.out.println("Coordinator: ACK received by replica");

        if (ackTimeout != null) {
            ackTimeout.cancel();
            ackTimeout = null;
        }

        ackVoters.add(getSender());
        if (!writeConfirmed && ackVoters.size() >= (N_REPLICAS / 2) + 1) { // quorum
            // Iterate over the participants and send the message only to replicas
            for (ActorRef participant : participants) {
                if (!participant.equals(getSelf())) {  // Check to ensure not sending to self
                    participant.tell(new Messages.WriteOkResponse(), getSelf());
                }
            }
            writeConfirmed = true;  // Write fatta, non è più da rifare
            System.out.println("Coordinator: Write operation confirmed. Value updated to " + pendingValue);
        }
    }

    public void onTimeoutCoord(Messages.Timeout timeout) {
        switch (timeout.getType()) {
            case "AckUpdate":
                System.out.println("TIMEOUT DETECTED ON ACK! Assuming crash.");
                missingAckTO();
                //multicast(new CrashCoordinatorDetect(), getSelf());
                break;
            default:
                System.out.println("Unhandled timeout for message type: " + timeout.getType());
                break;
        }
    }

    //risposta di lettura dalla replica per il client
    public void onReadValueRequest(Messages.ReadValueRequest msg) {
        // avoid transmitting while joining and during view changes
        if (isEpochChanging()) {
            System.out.println("Read request received but an election is in progress. Request is deferred.");
            //aggiungere messaggio per client
            getSender().tell(new Messages.UnstableReadResponse(), getSelf());
            return;
        }

        getSender().tell(new Messages.ReadValueResponse(v), getSelf()); // Assuming `v` is the value to be read
        System.out.println("Replica " + this.id + ": read request processed, value sent: " + v);
    }

    private boolean isEpochChanging() {
        return isEpochChanging;
    }
    private Messages.UpdateValueRequest lastUpdateValueRequest;
    //mex dalla replica al coordinatore per chiedere di update il valore
    public void onUpdateValueRequest(Messages.UpdateValueRequest msg) {
        if(participants.size() < ((N_REPLICAS/2) + 1)){  // SE NON CI SONO ALMENO NREPLICHE / 2   + 1 RIMASTE IN VITA NON POSSO PIU FARE DEGLI UPDATE
            return;
        }

        lastUpdateValueRequest = new Messages.UpdateValueRequest(msg.newValue);

        if (isEpochChanging) {
            System.out.println("Read request received but an election is in progress. Request is deferred.");
            Messages.UpdateValueRequest msg2 = new Messages.UpdateValueRequest(msg.newValue);
            pendingUpdateMessages.add(msg2);// Store the message to handle later
            System.out.println("Pending updates count: " + pendingUpdateMessages.size());
            return;
        }
        coordinator.tell(new Messages.UpdateRequest(msg.newValue), getSelf());
        System.out.println("Replica " + this.id + ": update request sent to coordinator with new value: " + msg.newValue);
        if (updateTimeout != null) {
            updateTimeout.cancel();
        }
        updateTimeout = getContext().system().scheduler().scheduleOnce(
                Duration.create(10, TimeUnit.SECONDS),
                getSelf(),
                new Messages.Timeout("Update"),
                getContext().system().dispatcher(),
                getSelf()
        );
    }

    //ricevo dal coordinatore il valore aggiornato e gli notifico l'ack di avvenuta ricezione.
    public void onUpdateValueResponse(Messages.UpdateValueResponse msg) {
        //  System.out.println("Replica " + this.id + ": update value " + msg.newValue + " received from the Coordinator.\n Epoch: "+ msg.epoch + " SEQN: " + msg.sequenceNum);
        //System.out.println("Replica " + this.id + ": updated value received from coordinator - New value: " + msg.newValue + ", Epoch: " + msg.epoch + ", Sequence number: " + msg.sequenceNum + ", ACK sent");
        System.out.println("Replica " + this.id + " update "+ msg.epoch + ":" + msg.sequenceNum + " " +  msg.newValue);

        if (updateTimeout != null) {
            updateTimeout.cancel();
            updateTimeout = null;
        }

        //this.v = msg.newValue;
        esn.setEpoch(msg.epoch); //io replica setto l'epoca
        esn.setSequenceNumber(msg.sequenceNum); //io replica setto il seqnumber
        //epochSeqMap.put(esn, this.v);
        EpochSeqNumber esnt = new EpochSeqNumber(esn.getEpoch(),esn.getSequenceNumber());
        epochSeq_valueMap.put(esnt, msg.newValue);

        getSender().tell(new Messages.Acknowledge(), getSelf());
        if (writeOkTimeout != null) {
            writeOkTimeout.cancel();
        }
        writeOkTimeout = getContext().system().scheduler().scheduleOnce(
                Duration.create(10, TimeUnit.SECONDS),
                getSelf(),
                new Messages.Timeout("WriteOK"),
                getContext().system().dispatcher(),
                getSelf()
        );
        //operazione di ack fatta subito dopo che ricevo il valore dal coordinator
        //print("Value updated to " + msg.newValue + ", Ack sent.");
    }

    public void onWriteOkResponse(Messages.WriteOkResponse msg) {
        System.out.println("Replica " + this.id + ": Received WRITEOK from Coordinator to confirm successful write");

        // Cancel the timeout since the WriteOkResponse is received on time
        if (writeOkTimeout != null) {
            writeOkTimeout.cancel();
            writeOkTimeout = null;
        }
    }

    public void onTimeout(Messages.Timeout timeout) {
        switch (timeout.getType()) {
            case "Heartbeat":
                System.out.println("Timeout detected: No Heartbeat from Coordinator. Coordinator may be crashed.");
                startElection();
                break;
            case "Update":
                System.out.println("Timeout detected: No Update received. Coordinator may be crashed.");
                pendingUpdateMessages.add(lastUpdateValueRequest);//store the message to handle later
                startElection();
                break;
            case "WriteOK":
                System.out.println("Timeout detected: No WriteOk received. Coordinator may be crashed.");
                startElection();
                break;
            case "AckElection": //Election
                System.out.println("Replica " + this.id + ": Timeout detected! No AckElection received from " + expectedElectionAckFrom.path().name() + ". Replica may be crashed.");
                multicastUpdateParticipants(expectedElectionAckFrom);
                sendElectionMessage(lastElectionMsg);
                break;
            default:
                System.out.println("Unhandled replica timeout for message type: " + timeout.getType());
                break;
        }
    }

    private void startElection() {
        if(participants.size() < ((N_REPLICAS/2) + 1)){  // SE NON CI SONO ALMENO NREPLICHE / 2   + 1 RIMASTE IN VITA NON POSSO PIU FARE DELLE NUOVE ELEZIONI PERCHè NON SI RAGGIUNGERA MAI IL QUORUM.
            return;
        }
        if(!isEpochChanging){
            List<Integer> initialVisited = new ArrayList<>();
            initialVisited.add(this.id);

            ElectionMessage electionMsg = new ElectionMessage(initialVisited);
            sendElectionMessage(electionMsg);
        }
    }

    private ActorRef expectedElectionAckFrom;
    private ElectionMessage lastElectionMsg;
    private void sendElectionMessage(ElectionMessage electionMsg) {
        int nextIndex = (participants.indexOf(getSelf()) + 1) % participants.size(); // next node, if it's the last start again

        ActorRef nextNode = participants.get(nextIndex);
        expectedElectionAckFrom = nextNode;
        lastElectionMsg=electionMsg;

        lastElectionMsg = new ElectionMessage(electionMsg.getVisitedNodes());
        electionMsg.setSeqID(this.id, esn.getSequenceNumber());
        isEpochChanging = true;

        nextNode.tell(electionMsg, getSelf());
        if (electionTimeout != null) {
            electionTimeout.cancel();
        }
        electionTimeout = getContext().system().scheduler().scheduleOnce(
                Duration.create(10, TimeUnit.SECONDS),
                getSelf(),
                new Messages.Timeout("AckElection"),
                getContext().system().dispatcher(),
                getSelf()
        );
        System.out.println("Replica " + this.id + " sent Election message to " + nextNode.path().name());
    }

    public void onElectionMessage(ElectionMessage msg) {
        System.out.println("Replica " + this.id + " received Election message with visited nodes: " + msg.getVisitedNodes());
        getSender().tell(new Messages.AcknowledgeElectionReceived(this.id), getSelf());  // mando ack al sender.

        if (!msg.hasVisited(this.id)) {
            msg.addVisitedNode(this.id);
            msg.setSeqID(this.id, esn.getSequenceNumber());
            //qua
            isEpochChanging = true; // Set the flag to indicate an election is in progress
            sendElectionMessage(msg);
        } else { // ho fatto il giro, ritorno al nodo -- si sta valutando se è il vincitore
            // Print out all entries for debugging
            /*int i = 0;
            for (Map.Entry<EpochSeqNumber, Integer> entry : epochSeq_valueMap.entrySet()) {
                System.out.println("Replica " + this.id + " [mapentry " + i + "] --- epoch: " + entry.getKey().getEpoch() + " seqn: " + entry.getKey().getSequenceNumber() + ", value: " + entry.getValue());
                i++;
            }*/
            evaluateElectionWinner(msg);
        }
    }

    private void evaluateElectionWinner(ElectionMessage msg) {
        int maxSeqNum = Collections.max(msg.getSeqID().values()); // max seq num

        List<Integer> candidates = msg.getSeqID().entrySet().stream() //    find all IDs that are associated with the maximum sequence number
                .filter(entry -> entry.getValue() == maxSeqNum)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
                //.toList();

        int winnerId = Collections.max(candidates); // max id of those with max seqn.

        // Print out all entries for debugging
        /*for (Map.Entry<Integer, Integer> entry : msg.getSeqID().entrySet()) {
            System.out.println("ID: " + entry.getKey() + ", SEQN: " + entry.getValue());
        }*/

        StringBuilder stringElection = new StringBuilder();
        //stringElection.append("Election Result: WinnerID: " + winnerId + " SeqNumber: " + maxSeqNum);
        stringElection.append("Election result: " + "the winner is " + winnerId+ " with Sequence Number: " + maxSeqNum);
        //System.out.println("WINNER ID " + winnerId + " with SEQNUM: " + maxSeqNum + " My ID: " + this.id);

        if (this.id == winnerId) { // winner = coordinator
            stringElection.append(". Replica "+ this.id + ": won the election!");
            System.out.println(stringElection);
            becomeCoordinator();
        } else { // loser = send next replica
            //stringElection.append(". LOSER with id "+ this.id +" , check next replicas...");
            stringElection.append(". Replica "+ this.id+ ": lost the Election, check for other replicas...");
            System.out.println(stringElection);
            ringCheckWinner(msg);
        }
    }

    private void ringCheckWinner(ElectionMessage electionmsg){
        int nextIndex = (participants.indexOf(getSelf()) + 1) % participants.size(); // next node, if it's the last start again
        ActorRef nextNode = participants.get(nextIndex);
        nextNode.tell(electionmsg, getSelf());
        //System.out.println("Replica" + this.id + ": sent the message to to evaluate the winner to " + nextNode.path().name());
    }

    public void onAcknowledgeElectionReceived(Messages.AcknowledgeElectionReceived ack) {
        if (electionTimeout != null) {
            electionTimeout.cancel();
            electionTimeout = null;
        }
        System.out.println("Replica " + this.id + ": Acknowledgment received from Node " + ack.getSenderId() + " for election message.");
    }
    private void becomeCoordinator() {
        System.out.println("Replica " + this.id + " is now the coordinator.");
        getContext().become(createReceiveCoord());
        isEpochChanging = false; // election completed

        coordinator = getSelf();
        startHeartbeat();

        int value=epochSeq_valueMap.get(esn);
        esn.setEpoch(esn.getEpoch()+1);
        esn.setSequenceNumber(0);

        EpochSeqNumber newEsn = new EpochSeqNumber(esn.getEpoch(), esn.getSequenceNumber());
        epochSeq_valueMap.put(newEsn, value); // Update the map with the new key and current value

        participants.remove(getSelf()); // io mi rimuovo dai partecipanti, ovvero le repliche.

        Messages.SynchronizationMessage syncMsg = new Messages.SynchronizationMessage(this.id, newEsn.getEpoch(), newEsn.getSequenceNumber(), value); // send sync to all replicas
        participants.forEach(participant -> {
            if (!participant.equals(getSelf())) { // Don't send to self
                participant.tell(syncMsg, getSelf());
            }
        });
    }

    private void processPendingMessages() {
        for (Messages.UpdateValueRequest updateMsg : new ArrayList<>(pendingUpdateMessages)) {
            onUpdateValueRequest(updateMsg);
        }

        pendingUpdateMessages.clear(); // clear the original list
    }

    public void onSynchronizationMessage(Messages.SynchronizationMessage msg) {
        System.out.println("Replica " + this.id + " received synchronization from new coordinator: " + msg.coordinatorId);
        isEpochChanging = false;
        participants.remove(getSender()); //tolgo il coordinator dai partecipanti
        processPendingMessages(); // Process any pending messages

        //update local state with the new coordinator
        EpochSeqNumber newEsn = new EpochSeqNumber(msg.epoch, msg.seqn);
        epochSeq_valueMap.put(newEsn, msg.currentValue);

        // debugging
        int i = 0;
        for (Map.Entry<EpochSeqNumber, Integer> entry : epochSeq_valueMap.entrySet()) {
            System.out.println("Replica " + this.id + " [mapEntry " + i + "] --- epoch: " + entry.getKey().getEpoch() + " seqn: " + entry.getKey().getSequenceNumber() + ", value: " + entry.getValue());
            i++;
        }
    }

}
