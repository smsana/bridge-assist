package dev.bridgeassist;

/** Coast-aware supported travel. Shift is a safety action, not a pacing mechanism. */
public final class HorizontalCycle {
    public enum Phase { APPROACH, BRAKE, EDGE_APPROACH, SETTLE, WAIT_CONFIRM, CROSS, UNSAFE }
    private Phase phase = Phase.UNSAFE;
    private double scale;
    private boolean emergency;
    // Conservative bounds for ordinary grounded walking on full solid bridge blocks.
    // Retain .14+ blocks of the .6-wide footprint; never plan a stop at its outer edge.
    private static final double RETENTION=.65, ACCELERATION=.105, STOP_DISTANCE=-.16;
    public Phase phase() { return phase; }
    public void reset() {phase=Phase.UNSAFE;scale=0;emergency=false;}
    public Phase update(boolean support, boolean nextConfirmed, boolean outstanding,
                        boolean nextPrediction, double edgeDistance, double forwardVelocity) {
        scale=0;emergency=false;
        if (!support) return phase = Phase.UNSAFE;
        if (outstanding || nextPrediction) return phase = Phase.WAIT_CONFIRM;
        if (nextConfirmed) {scale=1;return phase = Phase.CROSS;}
        double velocity=Math.max(0,forwardVelocity);
        double coastDistance=velocity/(1-RETENTION);
        emergency=edgeDistance-coastDistance<-.27;
        if(edgeDistance<=-.12) return phase=Phase.SETTLE;
        // Budget the whole remaining coast, including acceleration from this input.
        // Reducing input instead of crouch/brake/restart avoids the old slow edge crawl.
        double accelerationBudget=(edgeDistance-STOP_DISTANCE)*(1-RETENTION)-velocity;
        scale=Math.max(0,Math.min(1,accelerationBudget/ACCELERATION));
        if(emergency) {scale=0;return phase=Phase.BRAKE;}
        if(scale<=1e-4) {scale=0;return phase=Phase.BRAKE;}
        return phase=scale>=.999?Phase.APPROACH:Phase.EDGE_APPROACH;
    }
    public boolean allowMovement() {return scale>0 && phase!=Phase.WAIT_CONFIRM && phase!=Phase.UNSAFE;}
    public boolean allowClick() {return phase==Phase.APPROACH || phase==Phase.BRAKE
            || phase==Phase.EDGE_APPROACH || phase==Phase.SETTLE;}
    public boolean crouch() {return emergency || phase==Phase.WAIT_CONFIRM || phase==Phase.UNSAFE;}
    public double inputScale() {return scale;}
}
