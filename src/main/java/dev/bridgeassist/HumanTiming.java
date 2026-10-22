package dev.bridgeassist;

import java.util.Random;

/** Bounded monotonic-clock timing. Safety overrides randomness, never the reverse. */
public final class HumanTiming {
    private final Random random;
    private final SpeedbridgeLogic.PlaceMeter meter=new SpeedbridgeLogic.PlaceMeter();
    private boolean adaptive;
    private int minimum=300;
    private double target=300,adaptiveDelay;
    private long adaptiveClickAt=-1,adaptiveClickKey;
    public void configurePacing(boolean enabled,int minimumMs) {
        int value=Math.max(150,Math.min(600,minimumMs));
        if(adaptive!=enabled || minimum!=value) {adaptiveDelay=0;adaptiveClickAt=-1;target=value;}
        adaptive=enabled;minimum=value;
    }
    public SpeedbridgeLogic.PlaceMeter meter() {return meter;}
    public double adaptiveDelay() {return adaptiveDelay;}
    public double variedTarget() {return target;}
    public long remaining(long now) {
        if(meter.previous()<0)return 0;
        // The user's configured minimum is a hard floor, even on a -15% roll.
        return Math.max(0,meter.previous()+(long)Math.ceil((adaptive?Math.max(minimum,target):minimum))-now);
    }
    public boolean click(long now,long blockKey,boolean natural) {
        if(adaptive && (adaptiveClickAt<0 || adaptiveClickKey!=blockKey)) {
            adaptiveClickKey=blockKey;adaptiveClickAt=now+(long)Math.ceil(adaptiveDelay);
        }
        if(remaining(now)>0 || (adaptive && now<adaptiveClickAt))return false;
        return !natural || click(now,blockKey);
    }
    private void pacePlaced(long now) {
        long interval=meter.placed(now);
        if(adaptive) {
            if(interval>=0) {
                double error=target-meter.average();
                // 12.5% correction, bounded and converging back to zero on slow runs.
                if(interval<minimum || error>0)adaptiveDelay=Math.min(600,adaptiveDelay+.125*Math.max(0,error));
                else if(meter.average()>target*1.10)adaptiveDelay=Math.max(0,adaptiveDelay-.125*Math.max(1,meter.average()-target));
            }
            target=minimum*(.85+.30*random.nextDouble());
        }
        adaptiveClickAt=-1;
    }
    private long nextPause,pauseUntil,pressAt=-1,clickAt=-1,clickKey,releaseUntil;
    private double strength=.6,interval=3;
    private int minPause=200,maxPause=500;
    private double yawAmplitude,pitchOffset,speedFactor=1,edgeExtra;
    private long cameraStart,cameraPeriod=1200;
    public HumanTiming(Random random) {this.random=random;}
    public void configure(double strength,double interval,int minPause,int maxPause) {
        this.strength=Math.max(0,Math.min(1,strength));this.interval=Math.max(1,Math.min(15,interval));
        this.minPause=Math.max(0,Math.min(1000,minPause));this.maxPause=Math.max(this.minPause,Math.min(1000,maxPause));
    }
    public void reset(long now) {
        pauseUntil=releaseUntil=0;pressAt=clickAt=-1;adaptiveClickAt=-1;nextPause=now+intervalDelay();
        cameraStart=now;rollCamera();edgeExtra=random.nextDouble()*.045*strength;
    }
    private long intervalDelay() {return Math.round(interval*1000*(.8+.4*random.nextDouble()));}
    private int delay(int max,double probability) {
        return random.nextDouble()<probability*strength?1+random.nextInt(Math.max(1,(int)(max*strength))):0;
    }
    public boolean pause(long now,boolean safe,boolean allowed) {
        if(!allowed) {pauseUntil=0;nextPause=now+intervalDelay();return false;}
        if(now<pauseUntil) return true;
        if(pauseUntil!=0) {pauseUntil=0;nextPause=now+intervalDelay();return false;}
        if(now>=nextPause && safe) {
            pauseUntil=now+minPause+random.nextInt(maxPause-minPause+1);return now<pauseUntil;
        }
        return false;
    }
    public boolean sneak(long now,boolean requested,boolean emergency) {
        if(!requested) {pressAt=-1;return now<releaseUntil;}
        if(pressAt<0) pressAt=now+delay(45,.65);
        return emergency || now>=pressAt || now<releaseUntil;
    }
    public boolean click(long now,long blockKey) {
        if(clickAt<0 || clickKey!=blockKey) {clickKey=blockKey;clickAt=now+delay(65,.5);}
        return now>=clickAt;
    }
    public void attempted() {clickAt=adaptiveClickAt=-1;}
    public void placed(long now) {
        pacePlaced(now);
        releaseUntil=now+delay(140,.45);pressAt=-1;
        edgeExtra=random.nextDouble()*.045*strength;
    }
    public boolean releaseHeld(long now) {return now<releaseUntil;}
    public double edgeExtra() {return edgeExtra;}
    private void rollCamera() {
        yawAmplitude=(.04+random.nextDouble()*.10)*strength;
        pitchOffset=(random.nextDouble()*2-1)*.25*strength;
        speedFactor=1+(random.nextDouble()*2-1)*.18*strength;
        cameraPeriod=900+random.nextInt(901);
    }
    public void updateCamera(long now) {
        if(now-cameraStart>=cameraPeriod) {cameraStart=now;rollCamera();}
    }
    /** Full sine cycle returns to the bridge axis instead of a permanent yaw drift. */
    public double yawOffset(long now) {return yawAmplitude*Math.sin(2*Math.PI*Math.max(0,now-cameraStart)/cameraPeriod);}
    public double pitchOffset() {return pitchOffset;}
    public double speedFactor() {return speedFactor;}
}
