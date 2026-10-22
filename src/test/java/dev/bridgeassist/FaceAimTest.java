package dev.bridgeassist;

public final class FaceAimTest {
    private static int assertions;
    private static void check(boolean ok,String message) {assertions++;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) {
        int shifted=0,unchanged=0;
        for(long seed=0;seed<4000;seed++)for(double margin:new double[]{.05,.15,.30}) {
            double inset=margin+.002;
            for(double base:new double[]{inset,.5,1-inset})for(int axis=0;axis<2;axis++) {
                double v=FaceAimVariation.coordinate(base,inset,true,seed,1234567,2,0,0,1,axis);
                check(v>=inset && v<=1-inset,"offset never leaves safe face zone");
                check(Math.abs(v-base)<=.09000001,"bounded offset");
                check(v==FaceAimVariation.coordinate(base,inset,true,seed,1234567,2,0,0,1,axis),"no per-tick resampling");
                check(base==FaceAimVariation.coordinate(base,inset,false,seed,1234567,2,0,0,1,axis),"humanize opt-out unchanged");
                if(base==.5 && axis==0) {if(v==base)unchanged++;else shifted++;}
            }
        }
        check(shifted>5000 && unchanged>1500,"both offset and unshifted placements occur");
        for(int settle=1;settle<=6;settle++) {
            PlacementTracker tracker=new PlacementTracker();var face=new PlacementTracker.Face(42,1);
            for(int tick=1;tick<=settle;tick++) {
                tracker.observeAim(43,face,true,179.9f,80,tick);
                tracker.observeAim(43,face,true,179.9f,80,tick); // START/END must count once.
                check(tracker.canClick(43,face,false,tick,tick*50,0,settle)==(tick==settle),"all settle values reachable, no early click");
            }
            tracker.observeAim(43,face,true,-179.9f,80,settle+1);
            check(tracker.faceSettled(43,face,settle+1,settle),"yaw wrap is not a reset");
            tracker.observeAim(43,face,true,-178.9f,80,settle+2);
            check(!tracker.faceSettled(43,face,settle+2,2),"real aim change restarts full dwell");
            tracker.observeAim(43,face,false,-178.9f,80,settle+3);
            check(!tracker.faceSettled(43,face,settle+3,1),"ray miss invalidates dwell");
        }
        var cube=net.minecraft.util.shape.VoxelShapes.fullCube();
        for(int seed=0;seed<100;seed++)for(var side:net.minecraft.util.math.Direction.values())
            for(double margin:new double[]{.05,.15,.30}) {
                double inset=margin+.002;
                double a=FaceAimVariation.coordinate(.5,inset,true,seed,0,side.ordinal(),0,0,0,0);
                double b=FaceAimVariation.coordinate(.5,inset,true,seed,0,side.ordinal(),0,0,0,1);
                var point=switch(side.getAxis()) {
                    case X -> new net.minecraft.util.math.Vec3d(.5+side.getOffsetX()*.499999,a,b);
                    case Y -> new net.minecraft.util.math.Vec3d(a,.5+side.getOffsetY()*.499999,b);
                    case Z -> new net.minecraft.util.math.Vec3d(a,b,.5+side.getOffsetZ()*.499999);
                };
                var eye=new net.minecraft.util.math.Vec3d(.5+side.getOffsetX()*2,.5+side.getOffsetY()*2,.5+side.getOffsetZ()*2);
                var d=point.subtract(eye);var aim=BridgeMath.lookAt(d.x,d.y,d.z);
                var hit=cube.raycast(eye,eye.add(net.minecraft.util.math.Vec3d.fromPolar(aim.pitch(),aim.yaw()).multiply(5)),net.minecraft.util.math.BlockPos.ORIGIN);
                check(hit!=null && hit.getSide()==side,"varied packet angle hits the real face");
                var v=hit.getPos();
                double u=switch(side.getAxis()){case X->v.y;case Y,Z->v.x;};
                double w=switch(side.getAxis()){case X,Y->v.z;case Z->v.y;};
                check(u>=margin && u<=1-margin && w>=margin && w<=1-margin,"float ray retains the edge margin");
            }
        // A steep side-face point can pass geometric visibility but fail Minecraft's
        // lookup-table fromPolar ray. Selection and cache validation must use the latter.
        var origin=net.minecraft.util.math.BlockPos.ORIGIN;
        var steepEye=new net.minecraft.util.math.Vec3d(.4999598707881862,2.27,-.1002274716449447);
        boolean lookupViolation=false;
        for(int n=0;n<=1000;n++) {
            var edgePoint=new net.minecraft.util.math.Vec3d(.15+n*.0007,.152,.000001);
            var direct=cube.raycast(steepEye,edgePoint,origin);
            check(direct!=null && direct.getPos().y>=.15,"geometric point passes old preflight");
            var offset=edgePoint.subtract(steepEye);var steepAim=BridgeMath.lookAt(offset.x,offset.y,offset.z);
            var rounded=cube.raycast(steepEye,steepEye.add(net.minecraft.util.math.Vec3d.fromPolar(steepAim.pitch(),steepAim.yaw()).multiply(5)),origin);
            if(rounded!=null && rounded.getPos().y<.15)lookupViolation=true;
        }
        check(lookupViolation,"actual angle ray exposes edge violations missed by geometric visibility");
        var safePoint=new net.minecraft.util.math.Vec3d(.5,.5,.000001);
        var offset=safePoint.subtract(steepEye);var safeAim=BridgeMath.lookAt(offset.x,offset.y,offset.z);
        var safe=cube.raycast(steepEye,steepEye.add(net.minecraft.util.math.Vec3d.fromPolar(safeAim.pitch(),safeAim.yaw()).multiply(5)),origin);
        check(safe!=null && safe.getPos().y>=.15 && safe.getPos().y<=.85,"another actual packet direction remains valid");
        PlacementTracker drift=new PlacementTracker();var face=new PlacementTracker.Face(1,1);
        for(int t=1;t<=5;t++)drift.observeAim(2,face,true,t*.2f,80,t);
        check(!drift.faceSettled(2,face,5,4),"slow cumulative drift cannot masquerade as steady aim");
        System.out.println("PASS: "+assertions+" face variation and settle assertions.");
    }
}
