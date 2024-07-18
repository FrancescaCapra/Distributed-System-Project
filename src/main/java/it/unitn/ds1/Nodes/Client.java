package it.unitn.ds1.Nodes;

import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import it.unitn.ds1.SupportingClasses.Messages;
import scala.concurrent.duration.Duration;
import java.util.Random;
import java.util.concurrent.TimeUnit;


public class Client extends Node {
    public Cancellable readTimeout;

    // settings for behaviour of clients operations.
    protected final boolean RANDOM_READ = true;
    protected final boolean RANDOM_WRITE = true;

    public Client(int id, int v){
        super(id, v);
    }

    static public Props props(int id, int v) {
        return Props.create(Client.class, () -> new Client(id, v));
    }

    public void onStartMessage(Messages.StartMessage msg){
        setGroup(msg);
        if(RANDOM_READ || RANDOM_WRITE){
            startRandomRequests();  //start sending requests randomly after receiving the start message if one of them is flagged as true
        }
    }

    private ActorRef expectedReadAckFrom;
    public void onReadRequest(Messages.ReadValueRequest msg) {
        // scegliamo una replica random per effettuare operazione di read
        int selectedRandReplica = new Random().nextInt(participants.size());
        ActorRef selectedReplica = participants.get(selectedRandReplica);
        expectedReadAckFrom = selectedReplica;
        selectedReplica.tell(new Messages.ReadValueRequest(), getSelf());
        System.out.println("Client " + this.id + " read req to replica " + selectedRandReplica);

        if (readTimeout != null) {
            readTimeout.cancel();
        }
        readTimeout = getContext().system().scheduler().scheduleOnce(
                Duration.create(10, TimeUnit.SECONDS),
                getSelf(),
                new Messages.Timeout("Read"),
                getContext().system().dispatcher(),
                getSelf()
        );
    }

    public void onReadResponse(int value) {
        System.out.println("Client " + this.id + " read done " + value);

        if (readTimeout != null) {
            readTimeout.cancel();
            readTimeout = null;
        }
    }

    public void onUnstableReadResponse(Messages.UnstableReadResponse msg) {
        System.out.println("Unstable read return, probably there is an election going on.");
        if (readTimeout != null) {
            readTimeout.cancel();
            readTimeout = null;
        }
    }

    public void requestWrite(int newValue) {
        int selectedReplicaIndex = random.nextInt(participants.size());
        ActorRef selectedReplica = participants.get(selectedReplicaIndex);
        //ActorRef selectedReplica = participants.get(0); // debug, force selection replica
        selectedReplica.tell(new Messages.UpdateValueRequest(newValue), getSelf());
        System.out.println("Client " + this.id + ": request a write to replica " + selectedReplicaIndex + " with new value " + newValue);
    }


    public void onTimeout(Messages.Timeout timeout) {
        switch (timeout.getType()) {
            case "Read":
                System.out.println("Timeout detected: No value read from the replica. Assuming "+ expectedReadAckFrom.path().name() +" has crashed.");
                participants.remove(expectedReadAckFrom);
                // da implementare se si volesse comunicare a tutte le repliche che ne ho individuato una crashata
                //multicast(new CrashReplicaDetectByClient(), getSelf());
                break;
            default:
                System.out.println("Unhandled client timeout for message type: " + timeout.getType());
                break;
        }
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(Messages.StartMessage.class, this::onStartMessage)
                .match(Messages.ReadValueRequest.class, this::onReadRequest)
                .match(Messages.ReadValueResponse.class, req -> onReadResponse(req.value))
                .match(Messages.UnstableReadResponse.class, this::onUnstableReadResponse)
                .match(Messages.UpdateValueRequest.class, req -> requestWrite(req.newValue))
                .match(Messages.Timeout.class, this::onTimeout)
                .build();
    }

    private Cancellable requestTask;  //manage the request sending task,

    private void startRandomRequests() { //start randomly sending read or write requests
        requestTask = getContext().system().scheduler().scheduleAtFixedRate(
                Duration.create(1, TimeUnit.SECONDS),
                Duration.create(5, TimeUnit.SECONDS), //ogni 5 secondi vedo se ho una % di triggerare una nuova azione di read o write.
                this::randomlySendRequests,
                getContext().system().dispatcher()
        );
    }

    private void randomlySendRequests() { //randomly decide to send read or write requests
        if (random.nextDouble() < RANDOMOPERATION_PROBABILITY) {
            boolean performRead = RANDOM_READ && random.nextBoolean();
            boolean performWrite = RANDOM_WRITE && !performRead;

            if (performRead) {
                onReadRequest(new Messages.ReadValueRequest());
            } else if (performWrite) {
                requestWrite(random.nextInt(100));
            }
        }
    }
}
