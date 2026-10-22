package dev.bridgeassist;

/** Pure geometry, separated from the game so it can be tested without OpenGL. */
public final class BridgeMath {
    private BridgeMath() {}
    public record Axes(double sideways, double forward) {}
    public record Aim(float yaw, float pitch) {}
    public static Axes rotateInput(double sideways, double forward, double yawDifference) {
        double a = Math.toRadians(yawDifference);
        return new Axes(sideways * Math.cos(a) - forward * Math.sin(a),
                forward * Math.cos(a) + sideways * Math.sin(a));
    }
    /** Safe fixed jump angle: projected hit stays inside the top face up to jump apex. */
    public static double safeVerticalPitch(double x,double z,int blockX,int blockZ,double yaw,double preferred,double margin) {
        double ux=-Math.sin(Math.toRadians(yaw)),uz=Math.cos(Math.toRadians(yaw));
        double budget=Double.POSITIVE_INFINITY;
        if(Math.abs(ux)>1e-8)budget=Math.min(budget,(ux>0?blockX+1-margin-x:x-blockX-margin)/Math.abs(ux));
        if(Math.abs(uz)>1e-8)budget=Math.min(budget,(uz>0?blockZ+1-margin-z:z-blockZ-margin)/Math.abs(uz));
        return Math.min(90,Math.max(preferred,Math.toDegrees(Math.atan2(2.90,Math.max(0,budget)*.95))));
    }
    public static Aim lookAt(double x, double y, double z) {
        return new Aim((float) Math.toDegrees(Math.atan2(-x, z)),
                (float) -Math.toDegrees(Math.atan2(y, Math.sqrt(x * x + z * z))));
    }
}
