package dev.bridgeassist;

import java.util.Random;

/** Run with gradle mathTest; no Minecraft client or display required. */
public final class BridgeMathTest {
    private static int assertions;
    private static void near(double actual, double expected, double tolerance) {
        assertions++;
        if (Math.abs(actual - expected) > tolerance)
            throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        Random r = new Random(1214);
        for (int i = 0; i < 10000; i++) {
            double yaw = r.nextDouble() * 720 - 360;
            double aimYaw = r.nextDouble() * 720 - 360;
            double forward = r.nextDouble() * 2 - 1;
            double side = r.nextDouble() * 2 - 1;
            var corrected = BridgeMath.rotateInput(side, forward, yaw - aimYaw);
            var wantedWorld = BridgeMath.rotateInput(side, forward, yaw);
            var correctedWorld = BridgeMath.rotateInput(corrected.sideways(), corrected.forward(), aimYaw);
            near(correctedWorld.sideways(), wantedWorld.sideways(), 1e-12);
            near(correctedWorld.forward(), wantedWorld.forward(), 1e-12);
            near(Math.hypot(side, forward), Math.hypot(corrected.sideways(), corrected.forward()), 1e-12);
            double x = r.nextDouble() * 8 - 4, y = -r.nextDouble() * 4 - .01, z = r.nextDouble() * 8 - 4;
            var a = BridgeMath.lookAt(x, y, z);
            double yawRadians = Math.toRadians(a.yaw()), pitchRadians = Math.toRadians(a.pitch());
            double length = Math.sqrt(x*x + y*y + z*z);
            near(-Math.sin(yawRadians) * Math.cos(pitchRadians), x / length, 3e-7);
            near(-Math.sin(pitchRadians), y / length, 3e-7);
            near(Math.cos(yawRadians) * Math.cos(pitchRadians), z / length, 3e-7);
        }
        near(BridgeMath.lookAt(0,-1,0).pitch(), 90, 1e-8);
        near(BridgeMath.lookAt(1,0,0).yaw(), -90, 1e-8);
        near(BridgeMath.lookAt(0,0,1).yaw(), 0, 1e-8);
        System.out.println("PASS: " + assertions + " geometry assertions (movement and aiming).");
    }
}
