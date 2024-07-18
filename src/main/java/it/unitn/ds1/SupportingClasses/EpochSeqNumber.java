package it.unitn.ds1.SupportingClasses;
import java.util.Objects;

//classe per la coppia <epoch, seqNumber>
public class EpochSeqNumber {
    private int epoch;
    private int sequenceNumber;
    private int id;

    public EpochSeqNumber(int epoch, int sequenceNumber) {
        this.epoch = epoch;
        this.sequenceNumber = sequenceNumber;
    }

    public int getEpoch() {
        return epoch;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void setEpoch(int epoch) {
        this.epoch = epoch;
    }

    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public void incrementSequenceNumber() { this.sequenceNumber++; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EpochSeqNumber that = (EpochSeqNumber) o;
        return epoch == that.epoch && sequenceNumber == that.sequenceNumber;
    }

    @Override
    public int hashCode() {
        return Objects.hash(epoch, sequenceNumber);
    }

    @Override
    public String toString() {
        return "EpochSeqNumber{" +
                "epoch=" + epoch +
                ", sequenceNumber=" + sequenceNumber +
                '}';
    }

}
