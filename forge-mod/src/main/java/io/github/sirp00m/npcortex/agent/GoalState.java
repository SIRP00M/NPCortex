package io.github.sirp00m.npcortex.agent;

/** Goal lifecycle is separate from executor phases (jumping, walking, etc.). */
public enum GoalState {
    READY, THINKING, EXECUTING, VERIFYING, CLEANING, PAUSED, SUCCEEDED, CANCELLED;
    public boolean terminal() { return this == SUCCEEDED || this == CANCELLED; }
    public boolean permits(GoalState next) {
        if (this == next) return true;
        if (terminal()) return false;
        if (next == PAUSED || next == CLEANING || next == CANCELLED) return true;
        return switch (this) {
            case READY -> next == THINKING || next == EXECUTING;
            case THINKING -> next == EXECUTING || next == READY;
            case EXECUTING -> next == VERIFYING;
            case VERIFYING -> next == READY || next == SUCCEEDED;
            case CLEANING -> next == VERIFYING || next == READY;
            case PAUSED -> next == READY;
            default -> false;
        };
    }
    public static GoalState phase(String phase) {
        return switch (phase) {
            case "ready", "search" -> READY;
            case "thinking" -> THINKING;
            case "paused" -> PAUSED;
            case "cleanup", "pillar_cleanup" -> CLEANING;
            default -> EXECUTING;
        };
    }
}
