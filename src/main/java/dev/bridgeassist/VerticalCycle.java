package dev.bridgeassist;

import java.util.Random;

/** Tick-driven vertical phases. No key binding is ever changed by virtual jumping. */
public final class VerticalCycle {
    public enum Mode { HORIZONTAL, STAIRCASE, TOWER }
    public static Mode mode(String value) {
        try { return Mode.valueOf(value); }
        catch (IllegalArgumentException | NullPointerException e) { return Mode.HORIZONTAL; }
    }
    public enum Phase { BASE, WALK, READY, JUMP, LAND }
    private final Mode mode;
    private Phase phase;
    private long jumpTick, jumpAt, delay;
    private boolean issued;
    public VerticalCycle(Mode mode) { this.mode = mode; phase = mode == Mode.STAIRCASE ? Phase.BASE : Phase.READY; }
    public Phase phase() { return phase; }
    public boolean flying() { return phase == Phase.JUMP || phase == Phase.LAND; }
    public void basePresent() { phase = Phase.WALK; }
    public void arrived() { phase = Phase.READY; }
    public void launch(long now, long tick, Random random) {
        if (phase != Phase.READY) throw new IllegalStateException("Jump outside READY");
        phase = Phase.JUMP; jumpTick = tick; jumpAt = now;
        delay = Math.round(250 * (.85 + .30 * random.nextDouble())); issued = false;
    }
    public long delay() { return delay; }
    public boolean virtualJump(long tick) { return phase == Phase.JUMP && !issued && tick == jumpTick; }
    public void issued() { issued = true; }
    public boolean canPlace(long now, double feetY, double targetTop, boolean bodyClear, boolean realFace) {
        return phase == Phase.JUMP && issued && now - jumpAt >= delay
                && feetY >= targetTop + .001 && bodyClear && realFace;
    }
    public boolean returnedToGround(long tick) { return phase == Phase.JUMP && tick > jumpTick + 2; }
    public void confirmed() { phase = Phase.LAND; }
    public void retry() { phase = Phase.READY; issued = false; }
    public void landed() { phase = mode == Mode.STAIRCASE ? Phase.BASE : Phase.READY; issued = false; }
    public static int walkDirection(double along) {return along < -.07 ? 1 : along > .07 ? -1 : 0;}
    public static boolean outsideBody(double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ, int x, int y, int z) {
        return maxX <= x || minX >= x + 1 || maxY <= y || minY >= y + 1 || maxZ <= z || minZ >= z + 1;
    }
    /** Physical state only: never merge synthesized PlayerInput.jump into this value. */
    public static boolean manualOverride(boolean w, boolean a, boolean s, boolean d, boolean physicalJump) {
        return SpeedbridgeLogic.physicalMovement(w, a, s, d, physicalJump);
    }
}
