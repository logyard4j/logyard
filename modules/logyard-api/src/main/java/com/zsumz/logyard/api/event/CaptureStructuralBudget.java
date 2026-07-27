package com.zsumz.logyard.api.event;

/** Tracks one event's bounded graph, entry, exception-node, and stack-frame allowances. */
final class CaptureStructuralBudget {
    private int remainingNodes;
    private int remainingEntries;
    private int remainingExceptionNodes;
    private int remainingFrames;

    CaptureStructuralBudget(int nodes, int entries, int exceptionNodes, int frames) {
        remainingNodes = nodes;
        remainingEntries = entries;
        remainingExceptionNodes = exceptionNodes;
        remainingFrames = frames;
    }

    boolean claimNode() {
        if (remainingNodes == 0) {
            return false;
        }
        remainingNodes--;
        return true;
    }

    boolean claimEntry() {
        if (remainingEntries == 0) {
            return false;
        }
        remainingEntries--;
        return true;
    }

    boolean claimExceptionNode() {
        if (remainingExceptionNodes == 0 || !claimNode()) {
            return false;
        }
        remainingExceptionNodes--;
        return true;
    }

    boolean claimFrame() {
        if (remainingFrames == 0 || !claimEntry()) {
            return false;
        }
        remainingFrames--;
        return true;
    }

    int remainingNodes() {
        return remainingNodes;
    }

    int remainingEntries() {
        return remainingEntries;
    }
}
