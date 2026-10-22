package dev.bridgeassist;

/** Reproducible cardinal bridge setup and mouse scaling; no game state required. */
public final class SpeedbridgeLogic {
    private SpeedbridgeLogic() {}
    public enum Stance { FOLLOW_INPUT, STRAIGHT, DIAGONAL }
    public static Stance stance(String value) {
        try {return Stance.valueOf(value);} catch(IllegalArgumentException | NullPointerException e) {return Stance.FOLLOW_INPUT;}
    }
    public static int requestedSide(String mode,int physicalSide,boolean automatic) {
        return switch(stance(mode)) {
            case STRAIGHT -> 0;
            case DIAGONAL -> 1;
            case FOLLOW_INPUT -> automatic?0:physicalSide;
        };
    }
    /** Lock the bridge axis first, then change only the stance, not the route. */
    public static Heading fixedHeading(double yaw,int side) {
        Heading straight=heading(yaw,0);
        return new Heading(straight.cameraYaw()-45*side,straight.dx(),straight.dz());
    }
    /** Retain the bridge axis across pause/resume; 45-degree jitter must not choose a new route. */
    public static final class BridgeAxis {
        private Heading straight;
        public void reset() {straight=null;}
        public void remember(Heading heading,int side) {
            straight=new Heading(heading.cameraYaw()+45*side,heading.dx(),heading.dz());
        }
        public Heading fixed(double yaw,int side) {
            if(straight==null)straight=heading(yaw,0);
            return new Heading(straight.cameraYaw()-45*side,straight.dx(),straight.dz());
        }
    }
    public static boolean physicalMovement(boolean w,boolean a,boolean s,boolean d,boolean jump) {
        return w || a || s || d || jump;
    }
    /** Runtime latch: saved autoBuild is never permission to move on joining a world. */
    public static final class AutoControl {
        private boolean paused=true;
        public void resume() {paused=false;}
        public void pause() {paused=true;}
        public boolean paused() {return paused;}
        public boolean movement(boolean enabled,boolean requested,boolean physical) {
            if(requested && physical) pause();
            return enabled && requested && !paused;
        }
    }
    /** Sliding window of actual successful placements; monotonic milliseconds. */
    public static final class PlaceMeter {
        private final long[] intervals=new long[8];
        private int size,index;
        private long previous=-1;
        public long placed(long now) {
            long interval=previous<0?-1:Math.max(0,now-previous);
            if(interval>=0) {intervals[index]=interval;index=(index+1)%intervals.length;size=Math.min(size+1,intervals.length);}
            previous=now;return interval;
        }
        public double average() {
            if(size==0)return 0;
            long sum=0;for(int i=0;i<size;i++)sum+=intervals[i];return (double)sum/size;
        }
        public int size() {return size;}
        public long previous() {return previous;}
    }
    // Vanilla sneak can keep less than 0.001 block of overlap. Do not discard
    // that real support; exclude only numerical face-touching, not thin overlap.
    public static int overlapMin(double min) {return (int)Math.floor(min+1e-7);}
    public static int overlapMax(double max) {return (int)Math.floor(max-1e-7);}
    public record Heading(double cameraYaw, int dx, int dz) {}
    public static double wrap(double a) {
        double n = a % 360;
        if (n >= 180) n -= 360;
        if (n < -180) n += 360;
        return n;
    }
    public static Heading heading(double currentYaw, int sideways) {
        BridgeMath.Axes world = BridgeMath.rotateInput(sideways, -1, currentYaw);
        double travelYaw = Math.toDegrees(Math.atan2(-world.sideways(), world.forward()));
        double cardinal = Math.round(travelYaw / 90.0) * 90.0;
        double localYaw = Math.toDegrees(Math.atan2(-sideways, -1));
        double camera = currentYaw + wrap(cardinal - localYaw - currentYaw);
        double radians = Math.toRadians(cardinal);
        return new Heading(camera, (int) Math.round(-Math.sin(radians)),
                (int) Math.round(Math.cos(radians)));
    }
    public static double mouseDegreesPerUnit(double sensitivity) {
        double f = sensitivity * 0.6000000238418579 + 0.20000000298023224;
        return f * f * f * 8.0 * 0.15;
    }
    public static boolean sneakAtEdge(boolean nextIsFloor, double distance, double threshold) {
        return !nextIsFloor && distance <= threshold;
    }
}
