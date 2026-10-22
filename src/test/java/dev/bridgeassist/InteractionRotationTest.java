package dev.bridgeassist;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.Hand;

public final class InteractionRotationTest {
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args) {
        InteractionRotationManager.clear();
        Packet<?> unrelated=new HandSwingC2SPacket(Hand.MAIN_HAND);
        var original=new PlayerMoveC2SPacket.Full(123.25,100.5,-789.75,12,-30,true,false);
        check(InteractionRotationManager.rewrite(original)==original,"inactive packets preserve identity");
        check(InteractionRotationManager.getAngles()==null,"no inactive angle leak");
        InteractionRotationManager.requestAngles(450,110,2);
        check(InteractionRotationManager.getAngles().yaw()==90 && InteractionRotationManager.getAngles().pitch()==90,"yaw wraps and pitch clamps");
        check(!InteractionRotationManager.readyForInteraction(),"request alone is not a sent packet");
        check(InteractionRotationManager.rewrite(unrelated)==unrelated,"non-movement packets remain identical");
        for(boolean ground:new boolean[]{false,true})for(boolean collision:new boolean[]{false,true}) {
            PlayerMoveC2SPacket[] variants={
                new PlayerMoveC2SPacket.Full(123.25,100.5,-789.75,12,-30,ground,collision),
                new PlayerMoveC2SPacket.PositionAndOnGround(-1000000.25,200.125,2000000.75,ground,collision),
                new PlayerMoveC2SPacket.LookAndOnGround(-30,45,ground,collision),
                new PlayerMoveC2SPacket.OnGroundOnly(ground,collision)
            };
            for(var packet:variants) {
                var result=(PlayerMoveC2SPacket)InteractionRotationManager.rewrite(packet);
                check(result.changesPosition()==packet.changesPosition(),"position-bearing packet never loses XYZ");
                check(result.isOnGround()==ground && result.horizontalCollision()==collision,"both movement flags preserved");
                check(result.changesLook() && result.getYaw(0)==90 && result.getPitch(0)==90,"every variant carries requested look");
                if(packet.changesPosition()) {
                    check(result instanceof PlayerMoveC2SPacket.Full,"position becomes Full, not LookOnly");
                    check(Double.doubleToLongBits(result.getX(0))==Double.doubleToLongBits(packet.getX(0))
                            && Double.doubleToLongBits(result.getY(0))==Double.doubleToLongBits(packet.getY(0))
                            && Double.doubleToLongBits(result.getZ(0))==Double.doubleToLongBits(packet.getZ(0)),"position is bit-exact");
                }
            }
        }
        check(original.getYaw(0)==12 && original.getPitch(0)==-30,"original packet is immutable");
        InteractionRotationManager.onPacketSent(InteractionRotationManager.rewrite(original));
        check(InteractionRotationManager.hasSentCurrentAngles(),"actual send acknowledged");
        check(!InteractionRotationManager.readyForInteraction(),"same-tick look and use forbidden");
        InteractionRotationManager.tick();
        check(InteractionRotationManager.readyForInteraction(),"previous-tick look permits use");
        InteractionRotationManager.requestAngles(90,90,2);
        check(InteractionRotationManager.readyForInteraction(),"refresh does not restart preparation barrier");
        InteractionRotationManager.requestAngles(91,80,2);
        check(!InteractionRotationManager.readyForInteraction(),"changed angles require a fresh send");
        InteractionRotationManager.onPacketSent(new PlayerMoveC2SPacket.LookAndOnGround(90,90,true,false));
        InteractionRotationManager.tick();
        check(!InteractionRotationManager.readyForInteraction(),"old rotation cannot acknowledge new request");
        InteractionRotationManager.onPacketSent(InteractionRotationManager.rewrite(original));
        check(!InteractionRotationManager.readyForInteraction(),"new send must also precede click by a tick");
        InteractionRotationManager.requestAngles(91,80,2);InteractionRotationManager.tick();
        check(InteractionRotationManager.readyForInteraction(),"new request is ready on following tick");
        InteractionRotationManager.tick();
        check(!InteractionRotationManager.isActive() && !InteractionRotationManager.readyForInteraction(),"TTL expires after exactly two ticks");
        check(InteractionRotationManager.rewrite(original)==original,"packet rewriting ends with TTL");
        InteractionRotationManager.requestAngles(-180,40,2);InteractionRotationManager.clear();
        check(!InteractionRotationManager.isActive() && InteractionRotationManager.getAngles()==null,"pause/disconnect clears angles");
        boolean rejected=false;try{InteractionRotationManager.requestAngles(Float.NaN,0,2);}catch(IllegalArgumentException e){rejected=true;}
        check(rejected,"NaN must never enter network packets");
        for(int t=0;t<1000;t++) {
            InteractionRotationManager.requestAngles(180+t,82,2);
            var packet=(PlayerMoveC2SPacket)InteractionRotationManager.rewrite(new PlayerMoveC2SPacket.PositionAndOnGround(t+.25,100,t-.75,true,false));
            InteractionRotationManager.onPacketSent(packet);
            check(!InteractionRotationManager.readyForInteraction(),"retargeted rotation cannot click early");
            InteractionRotationManager.tick();
            check(InteractionRotationManager.readyForInteraction(),"retargeted rotation advances independently of camera");
        }
        // Serialized float angles must still hit the same central face, not its boundary.
        var shape=net.minecraft.util.shape.VoxelShapes.fullCube();
        var origin=net.minecraft.util.math.BlockPos.ORIGIN;
        for(double margin:new double[]{.05,.15,.30})for(var side:net.minecraft.util.math.Direction.values())
            for(double a:new double[]{margin+.002,1-margin-.002})for(double b:new double[]{margin+.002,1-margin-.002}) {
                var point=switch(side.getAxis()) {
                    case X -> new net.minecraft.util.math.Vec3d(.5+side.getOffsetX()*.499999,a,b);
                    case Y -> new net.minecraft.util.math.Vec3d(a,.5+side.getOffsetY()*.499999,b);
                    case Z -> new net.minecraft.util.math.Vec3d(a,b,.5+side.getOffsetZ()*.499999);
                };
                var eye=switch(side.getAxis()) {
                    case X -> new net.minecraft.util.math.Vec3d(.5+side.getOffsetX()*.638058,2.27,.499986);
                    case Y -> new net.minecraft.util.math.Vec3d(.499986,.5+side.getOffsetY()*2,.5);
                    case Z -> new net.minecraft.util.math.Vec3d(.499986,2.27,.5+side.getOffsetZ()*.638058);
                };
                var d=point.subtract(eye);var aim=BridgeMath.lookAt(d.x,d.y,d.z);
                var end=eye.add(net.minecraft.util.math.Vec3d.fromPolar(aim.pitch(),aim.yaw()).multiply(5));
                var hit=shape.raycast(eye,end,origin);
                check(hit!=null && hit.getSide()==side,"float packet angles preserve the actual target face");
                var pos=hit.getPos();
                double u=switch(side.getAxis()){case X->pos.y;case Y, Z->pos.x;};
                double v=switch(side.getAxis()){case X, Y->pos.z;case Z->pos.y;};
                check(u>=margin && u<=1-margin && v>=margin && v<=1-margin,"packet roundtrip leaves the configured edge margin");
            }
        InteractionRotationManager.clear();
        System.out.println("PASS: "+assertions+" interaction rotation/packet assertions; camera independence requires game test.");
    }
}
