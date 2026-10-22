package dev.bridgeassist;

import java.util.Random;

/** Small per-attempt offsets, stable across ticks and bounded by the real face margin. */
public final class FaceAimVariation {
    private FaceAimVariation() {}
    public static double coordinate(double base,double inset,boolean enabled,long seed,long support,
                                    int face,int variant,int i,int j,int axis) {
        if(!enabled)return clamp(base,inset);
        long key=seed ^ Long.rotateLeft(support,21) ^ (0x9E3779B97F4A7C15L*(face+7L*variant+1));
        Random random=new Random(key);
        // Sometimes keep the ideal candidate, otherwise use a bounded, non-centred point.
        if(random.nextDouble()<.30)return clamp(base,inset);
        random=new Random(key ^ (0xD1B54A32D192ED03L*(1+axis+2L*j+32L*i)));
        double offset=(.025+random.nextDouble()*.065)*(random.nextBoolean()?1:-1);
        return clamp(base+offset,inset);
    }
    private static double clamp(double value,double inset) {return Math.max(inset,Math.min(1-inset,value));}
}
