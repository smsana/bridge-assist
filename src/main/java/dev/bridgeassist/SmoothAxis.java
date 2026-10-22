package dev.bridgeassist;

/** Frame-time driven critically damped aiming. No random jitter or angle snapping. */
public final class SmoothAxis {
    private double velocity;
    public void reset() { velocity = 0; }
    public double step(double error, double dt, double maxSpeed) {
        if (!Double.isFinite(error) || !Double.isFinite(dt) || dt <= 0) return 0;
        dt = Math.min(dt, 0.05);
        if (Math.abs(error) < 0.003 && Math.abs(velocity) < 0.03) {
            velocity = 0;
            return error;
        }
        double omega = 14.0;
        double positionError = -error;
        double temp = (velocity + omega * positionError) * dt;
        double decay = Math.exp(-omega * dt);
        double move = error + (positionError + temp) * decay;
        double newVelocity = (velocity - omega * temp) * decay;
        double limit = maxSpeed * dt;
        if (Math.abs(move) > limit) {
            move = Math.copySign(limit, move);
            newVelocity = move / dt;
        }
        if (move * error > 0 && Math.abs(move) > Math.abs(error)) {
            move = error;
            newVelocity = 0;
        }
        velocity = Math.max(-maxSpeed, Math.min(maxSpeed, newVelocity));
        return move;
    }
}
