package dev.bridgeassist;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** One global outstanding click. World observations, not ActionResult, acknowledge it. */
public final class PlacementTracker {
    public record Face(long support, int side) {}
    public enum Result { NONE, CONFIRMED, TIMED_OUT }
    public enum FailureReason { AIM_MISS, TARGET_OCCUPIED, OUT_OF_REACH, ITEM_USE_COOLDOWN, SERVER_CANCELLED, UNCONFIRMED }

    private Long pending;
    private long sentTick, sentAt, lastConfirmed = -1, backoffUntil, reaimAfterTick;
    private int failures, samples;
    private boolean reaim;
    private long observedTarget, observedTick = -1;
    private Face observedFace;
    private boolean sawPrediction, sawFullBlock, lastTimeoutPredicted;
    private long lateTarget = Long.MIN_VALUE, lateSentAt = -1;
    private final Map<Face, Long> clickedFaces = new LinkedHashMap<>();
    private final long[] latencies = new long[9];
    private int latencySize, latencyIndex;

    public void confirmedLate(long now) {
        lastConfirmed = now;
        failures = 0;
        reaim = false;
        samples = 0;
        if (lateSentAt >= 0) recordLatency(Math.max(0, now - lateSentAt));
        lateSentAt = -1;
        lateTarget = Long.MIN_VALUE;
    }
    public Long pending() { return pending; }
    public int failures() { return failures; }
    public long lastConfirmed() { return lastConfirmed; }
    public long backoffUntil() { return backoffUntil; }
    public boolean needsReaim() { return reaim; }
    public boolean timeoutSawPrediction() { return lastTimeoutPredicted; }
    public long lateTarget() { return lateTarget; }
    public boolean idle(long now, int minimum) {
        return pending == null && now >= backoffUntil
                && (lastConfirmed < 0 || now - lastConfirmed >= minimum);
    }
    public Result observeWorld(boolean fullBlockPresent, boolean predictionPresent, long tick, long now, int timeoutTicks) {
        if (pending == null) return Result.NONE;
        if (tick <= sentTick) return Result.NONE;
        if (predictionPresent) sawPrediction = true;
        if (fullBlockPresent) sawFullBlock = true;
        if (fullBlockPresent && !predictionPresent) {
            pending = null;
            lastConfirmed = now;
            failures = 0;
            reaim = false;
            samples = 0;
            recordLatency(Math.max(0, now - sentAt));
            lateSentAt = -1;
            lateTarget = Long.MIN_VALUE;
            return Result.CONFIRMED;
        }
        // A live prediction means the server has not resolved this action yet.
        // Never release the outstanding-click gate merely because a timer expired.
        if (!predictionPresent && tick - sentTick >= timeoutTicks) {
            pending = null;
            failures++;
            if (failures >= 2) backoffUntil = Math.max(backoffUntil, now + 500);
            reaim = true;
            reaimAfterTick = tick;
            samples = 0;
            observedTick = -1;
            lastTimeoutPredicted = sawPrediction || sawFullBlock;
            return Result.TIMED_OUT;
        }
        return Result.NONE;
    }
    /** Re-aim requires fresh settled raycasts after a failure; normal clicks also use this settle counter. */
    public void resetAim() { samples=0; observedTick=-1; observedFace=null; }
    public void observeFace(long target, Face face, boolean settled, long tick) {
        if (reaim && tick <= reaimAfterTick) {
            observedTick = -1;
            samples = 0;
            return;
        }
        if (tick == observedTick) return;
        if (!settled || face == null) {
            observedTick = tick;
            observedFace = null;
            samples = 0;
            return;
        }
        samples = observedTick == tick - 1 && observedTarget == target && face.equals(observedFace)
                ? samples + 1 : 1;
        observedTarget = target;
        observedFace = face;
        observedTick = tick;
        if (reaim && tick > reaimAfterTick && samples >= 2) reaim = false;
    }
    public boolean canClick(long target, Face face, boolean occupied, long tick, long now, int minimum, int settleTicks) {
        if (occupied || face == null || reaim || !idle(now, minimum)
                || observedTick != tick || observedTarget != target || !face.equals(observedFace)
                || samples < settleTicks) return false;
        Long last = clickedFaces.get(face);
        return last == null || now - last >= minimum;
    }
    public boolean faceSettled(long target, Face face, long tick, int settleTicks) {
        return face != null && observedTick == tick && observedTarget == target
                && face.equals(observedFace) && samples >= settleTicks;
    }
    /** Caller must perform final world/ray/body checks immediately before this method. */
    public void clicked(long target, Face face, long tick, long now) {
        if (pending != null) throw new IllegalStateException("Outstanding placement");
        pending = target;
        sentTick = tick;
        sentAt = now;
        lateTarget = target;
        lateSentAt = now;
        sawPrediction = false;
        sawFullBlock = false;
        clickedFaces.put(face, now);
        if (clickedFaces.size() > 256) clickedFaces.remove(clickedFaces.keySet().iterator().next());
    }
    public long medianLatencyMs() {
        if (latencySize == 0) return 0;
        long[] copy = Arrays.copyOf(latencies, latencySize);
        Arrays.sort(copy);
        return copy[latencySize / 2];
    }
    public int latencySettleBonusTicks() {
        long median = medianLatencyMs();
        return median > 250 ? 2 : median > 150 ? 1 : 0;
    }
    public int confirmationTimeoutTicks() {
        // Estimate from measured end-to-end confirmation, not aim dwell time.
        return (int)Math.min(200,Math.max(20,(medianLatencyMs()*3+250+49)/50));
    }
    public void notePrediction(boolean present) { if(pending!=null && present)sawPrediction=true; }
    public static boolean supportConfirmed(boolean fullBlockPresent, boolean predictionPresent) {
        return fullBlockPresent && !predictionPresent;
    }
    public static boolean allowMovementOnSupport(boolean requireConfirmedSupport, boolean fullBlockPresent, boolean predictionPresent) {
        return !requireConfirmedSupport || supportConfirmed(fullBlockPresent, predictionPresent);
    }
    private void recordLatency(long latency) {
        latencies[latencyIndex] = latency;
        latencyIndex = (latencyIndex + 1) % latencies.length;
        latencySize = Math.min(latencySize + 1, latencies.length);
    }
}
