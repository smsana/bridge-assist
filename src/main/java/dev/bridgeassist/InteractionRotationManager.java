package dev.bridgeassist;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/** Client-thread interaction rotation. Never drives the visual camera or invents positions. */
public final class InteractionRotationManager {
    public record Rotation(float yaw, float pitch) {}
    private static Rotation angles, sent;
    private static int remaining;
    private static long tick, firstSentTick = -1;
    private InteractionRotationManager() {}

    public static void requestAngles(float yaw, float pitch, int ticks) {
        if(!Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("Non-finite rotation");
        if(ticks<=0) { clear(); return; }
        Rotation next=new Rotation((float)SpeedbridgeLogic.wrap(yaw),Math.max(-90,Math.min(90,pitch)));
        if(!isActive() || !close(angles,next,.05f)) {firstSentTick=-1;sent=null;}
        angles=next;remaining=ticks;
    }
    public static Rotation getAngles() {return isActive()?angles:null;}
    public static boolean isActive() {return remaining>0 && angles!=null;}
    public static void tick() {
        tick++;
        if(remaining>0 && --remaining==0) {angles=null;sent=null;firstSentTick=-1;}
    }
    public static void clear() {remaining=0;angles=null;sent=null;firstSentTick=-1;}
    public static boolean hasSentCurrentAngles() {return isActive() && close(angles,sent,.05f);}
    /** A successful send on an earlier client tick, not merely a queued angle request. */
    public static boolean readyForInteraction() {return hasSentCurrentAngles() && firstSentTick>=0 && tick>firstSentTick;}
    public static void onPacketSent(Packet<?> packet) {
        if(!isActive() || !(packet instanceof PlayerMoveC2SPacket move) || !move.changesLook())return;
        Rotation rotation=new Rotation(move.getYaw(0),move.getPitch(0));
        if(!close(angles,rotation,.05f))return;
        sent=rotation;if(firstSentTick<0)firstSentTick=tick;
    }
    public static Packet<?> rewrite(Packet<?> packet) {
        if(!isActive() || !(packet instanceof PlayerMoveC2SPacket move))return packet;
        Rotation r=getAngles();
        if(move.changesPosition())return new PlayerMoveC2SPacket.Full(move.getX(0),move.getY(0),move.getZ(0),
                r.yaw(),r.pitch(),move.isOnGround(),move.horizontalCollision());
        return new PlayerMoveC2SPacket.LookAndOnGround(r.yaw(),r.pitch(),move.isOnGround(),move.horizontalCollision());
    }
    private static boolean close(Rotation a,Rotation b,float tolerance) {
        return a!=null && b!=null && Math.abs(SpeedbridgeLogic.wrap(a.yaw()-b.yaw()))<=tolerance
                && Math.abs(a.pitch()-b.pitch())<=tolerance;
    }
}
