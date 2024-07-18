package it.unitn.ds1.SupportingClasses;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class ElectionMessage implements Serializable {

    private final List<Integer> visitedNodes;
    private LinkedHashMap<Integer, Integer> rIDseqn = new LinkedHashMap<>();

    public ElectionMessage(List<Integer> visitedNodes) {
        this.visitedNodes = new ArrayList<>(visitedNodes);
    }

    public LinkedHashMap<Integer, Integer> getSeqID() {
        return rIDseqn;
    }

    public int getSeqID_id(Integer key) {
        return rIDseqn.get(key);
    }

    public void setSeqID(LinkedHashMap<Integer, Integer> seqID) {
        this.rIDseqn = seqID;
    }

    public List<Integer> getVisitedNodes() {
        return visitedNodes;
    }

    public void addVisitedNode(int nodeId) {
        visitedNodes.add(nodeId);
    }

    public boolean hasVisited(int nodeId) {
        return visitedNodes.contains(nodeId);
    }

    public void setSeqID(int id, int seqn) {
        rIDseqn.put(id, seqn);
    }

    @Override
    public String toString() {
        return "ElectionMessage{" +
                "seqID=" + rIDseqn +
                '}';
    }

}

