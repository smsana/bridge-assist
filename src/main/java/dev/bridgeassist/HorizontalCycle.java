package dev.bridgeassist;

/** Decisions only; no world mutation, packet spoofing or movement on a predicted floor. */
public final class HorizontalCycle {
    public enum Phase { APPROACH, SETTLE, WAIT_CONFIRM, CROSS, UNSAFE }
    private Phase phase = Phase.UNSAFE;
    public Phase phase() { return phase; }
    public Phase update(boolean support, boolean nextConfirmed, boolean outstanding,
                        boolean nextPrediction, double edgeDistance, double forwardVelocity) {
        if (!support) return phase = Phase.UNSAFE;
        if (outstanding || nextPrediction) return phase = Phase.WAIT_CONFIRM;
        if (nextConfirmed) return phase = Phase.CROSS;
        // Keep at least .18 blocks of the .6-wide footprint on the old floor.
        // Include conservative residual ground velocity and one input tick.
        double stoppingAllowance = Math.max(0, forwardVelocity) * 2.5 + .025;
        return phase = edgeDistance - stoppingAllowance <= -.12 ? Phase.SETTLE : Phase.APPROACH;
    }
    public boolean allowMovement() { return phase == Phase.APPROACH || phase == Phase.CROSS; }
    public boolean allowClick() { return phase == Phase.SETTLE; }
    public boolean crouch() { return phase != Phase.CROSS; }
    public double inputScale() { return phase == Phase.APPROACH ? .75 : 1; }
}
