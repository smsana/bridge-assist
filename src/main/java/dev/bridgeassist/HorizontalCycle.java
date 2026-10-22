package dev.bridgeassist;

/** Full-speed travel on real support; braking and a short crouched edge approach only. */
public final class HorizontalCycle {
    public enum Phase { APPROACH, BRAKE, EDGE_APPROACH, SETTLE, WAIT_CONFIRM, CROSS, UNSAFE }
    private Phase phase = Phase.UNSAFE;
    public Phase phase() { return phase; }
    public void reset() {phase=Phase.UNSAFE;}
    public Phase update(boolean support, boolean nextConfirmed, boolean outstanding,
                        boolean nextPrediction, double edgeDistance, double forwardVelocity) {
        if (!support) return phase = Phase.UNSAFE;
        if (outstanding || nextPrediction) return phase = Phase.WAIT_CONFIRM;
        if (nextConfirmed) return phase = Phase.CROSS;
        if (edgeDistance <= -.18) return phase = Phase.SETTLE;
        boolean edging=phase==Phase.BRAKE || phase==Phase.EDGE_APPROACH || phase==Phase.SETTLE;
        // Include residual ground velocity and the input tick about to be consumed.
        // Latch edge approach so braking cannot oscillate back to full-speed walking.
        double stoppingAllowance=Math.max(0,forwardVelocity)*3.5+.04;
        if(edging || edgeDistance-stoppingAllowance<=-.18)
            return phase=forwardVelocity>.055 ? Phase.BRAKE : Phase.EDGE_APPROACH;
        return phase=Phase.APPROACH;
    }
    public boolean allowMovement() { return phase==Phase.APPROACH || phase==Phase.EDGE_APPROACH || phase==Phase.CROSS; }
    // Movement state is not a raycast: valid placement may mature while approaching/braking.
    public boolean allowClick() { return phase==Phase.APPROACH || phase==Phase.BRAKE
            || phase==Phase.EDGE_APPROACH || phase==Phase.SETTLE; }
    public boolean crouch() { return phase!=Phase.APPROACH && phase!=Phase.CROSS; }
    public double inputScale() { return phase==Phase.EDGE_APPROACH ? .65 : 1; }
}
