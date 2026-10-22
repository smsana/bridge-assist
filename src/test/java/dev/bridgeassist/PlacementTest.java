package dev.bridgeassist;

import java.util.Random;

public final class PlacementTest {
    private static int assertions;
    private static void check(boolean ok, String message) {
        assertions++; if(!ok)throw new AssertionError(message);
    }
    private static PlacementTracker.Face face(int n) {return new PlacementTracker.Face(n,1);}
    private static boolean ready(PlacementTracker p,long target,int f,long tick,long now,int min,int settle) {
        p.observeFace(target,face(f),true,tick);
        return p.canClick(target,face(f),false,tick,now,min,settle);
    }
    private static void horizontalNetworkTests() {
        HorizontalCycle cycle=new HorizontalCycle();
        check(cycle.update(true,false,false,false,.5,0)==HorizontalCycle.Phase.APPROACH,"approach starts on confirmed old floor");
        check(cycle.allowMovement() && !cycle.crouch() && cycle.inputScale()==1 && !cycle.allowClick(),"safe approach is normal walking, never permanent crouch");
        check(cycle.update(true,false,false,false,-.121,0)==HorizontalCycle.Phase.SETTLE,"stop at safe overhang");
        check(!cycle.allowMovement() && cycle.allowClick(),"stopped movement DOES NOT disable placement");
        cycle.reset();
        check(cycle.update(true,false,false,false,.10,.10)==HorizontalCycle.Phase.BRAKE,"braking includes residual velocity before edge");
        check(!cycle.allowMovement() && cycle.crouch() && !cycle.allowClick(),"brake is stationary and cannot click");
        check(cycle.update(true,false,false,false,.10,0)==HorizontalCycle.Phase.EDGE_APPROACH,"stopped before edge resumes only a short controlled approach");
        check(cycle.allowMovement() && cycle.crouch() && cycle.inputScale()==.65,"only edge approach uses reduced crouch input");
        check(cycle.update(true,false,false,false,-.081,.03)==HorizontalCycle.Phase.SETTLE,"visible-face overhang stops motion");
        check(cycle.crouch() && !cycle.allowMovement() && cycle.allowClick(),"settle holds and allows normal placement checks");
        check(cycle.update(true,true,false,false,-.081,0)==HorizontalCycle.Phase.CROSS,"confirmed next block immediately releases brake");
        check(!cycle.crouch() && cycle.inputScale()==1 && cycle.allowMovement(),"confirmed crossing is normal walking");
        cycle.reset();
        check(cycle.update(true,false,false,false,.5,0)==HorizontalCycle.Phase.APPROACH,"new run does not inherit edge creep");
        check(cycle.update(true,false,false,true,-.12,0)==HorizontalCycle.Phase.WAIT_CONFIRM,"foreign prediction blocks motion too");
        check(!cycle.allowMovement() && !cycle.allowClick(),"predicted floor is neither permission to walk nor repeat");
        check(cycle.update(false,true,false,false,.1,0)==HorizontalCycle.Phase.UNSAFE,"lost old support beats confirmed next");
        for(int delay:new int[]{1,3,10,30,100,300}) for(boolean accept:new boolean[]{true,false}) {
            PlacementTracker p=new PlacementTracker();
            p.clicked(123,face(122),0,0);p.notePrediction(true);
            for(int tick=1;tick<=delay;tick++) {
                check(p.observeWorld(false,true,tick,tick*50L,p.confirmationTimeoutTicks())==PlacementTracker.Result.NONE,
                        "live server-unresolved prediction is not a rejection, even beyond timeout");
                cycle.update(true,false,true,true,-.12,0);
                check(!cycle.allowMovement() && !cycle.allowClick(),"no crossing or duplicate click during delayed reconciliation");
                check(p.pending()!=null && p.failures()==0,"network wait does not spend retry budget");
            }
            long at=(delay+1)*50L;
            var result=p.observeWorld(accept,false,delay+1,at,p.confirmationTimeoutTicks());
            if(accept) {
                check(result==PlacementTracker.Result.CONFIRMED,"delayed server reconciliation confirms actual block");
                check(cycle.update(true,true,false,false,-.12,0)==HorizontalCycle.Phase.CROSS,"confirmation automatically resumes movement");
                check(cycle.allowMovement(),"no B needed after successful reconciliation");
                check(!p.idle(at+299,300) && p.idle(at+300,300),"click cooldown still starts at confirmation, not send");
                for(int t=1;t<=20;t++) {
                    p.observeFace(124,face(123),true,delay+1+t);
                    check(p.pending()==null,"resume does not create an implicit click");
                }
            } else {
                // An absent block while no prediction remains never grants movement.
                check(cycle.update(true,false,result==PlacementTracker.Result.NONE,false,-.12,0)
                        !=HorizontalCycle.Phase.CROSS,"rejection retains old confirmed support");
                check(!cycle.allowMovement(),"no forward movement after rejection");
            }
        }
        for(double initialVelocity:new double[]{0,.03,.08,.12,.18}) {
            cycle.reset();double distance=1.2,velocity=initialVelocity;boolean reached=false;int walking=0;
            for(int t=0;t<160;t++) {
                cycle.update(true,false,false,false,distance,velocity);
                if(cycle.phase()==HorizontalCycle.Phase.APPROACH) {walking++;check(!cycle.crouch() && cycle.inputScale()==1,"safe floor does not use slow crouch");}
                double acceleration=cycle.allowMovement()?.098*cycle.inputScale()*(cycle.crouch()?.3:1):0;
                double displacement=velocity+acceleration;distance-=displacement;velocity=displacement*.546;
                check(distance>-.29,"braking never consumes the whole .3-block footprint overlap");
                if(cycle.phase()==HorizontalCycle.Phase.SETTLE && velocity<.008) {reached=true;break;}
            }
            check(reached && walking>0,"normal approach safely reaches a stationary visible-face position");
        }
        PlacementTracker noReply=new PlacementTracker();noReply.clicked(1,face(0),0,0);
        check(noReply.observeWorld(false,false,20,1000,20)==PlacementTracker.Result.TIMED_OUT,"no-result attempt eventually times out");
        check(!noReply.timeoutSawPrediction(),"no evidence of prediction must not be called server rollback");
        PlacementTracker captured=new PlacementTracker();captured.clicked(1,face(0),0,0);captured.notePrediction(true);
        check(captured.observeWorld(false,false,20,1000,20)==PlacementTracker.Result.TIMED_OUT && captured.timeoutSawPrediction(),
                "same-tick prediction captured even if reconciliation happens before next client tick");
        for(boolean present:new boolean[]{false,true}) for(boolean prediction:new boolean[]{false,true})
            check(PlacementTracker.allowMovementOnSupport(false,present,prediction),"explicit legacy opt-out retains support-gate behavior");
    }
    public static void main(String[] args) {
        PlacementTracker p=new PlacementTracker();
        check(!ready(p,10,1,0,0,300,3),"one settled tick is insufficient when aimSettleTicks is 3");
        check(!ready(p,10,1,1,50,300,3),"two settled ticks are still insufficient");
        check(ready(p,10,1,2,100,300,3),"third settled tick permits click");
        p.clicked(10,face(1),2,100);
        check(p.observeWorld(true,false,2,101,10)==PlacementTracker.Result.NONE,"same-tick local prediction not accepted");
        for(int t=3;t<12;t++) {
            check(p.observeWorld(false,false,t,t*50L,10)==PlacementTracker.Result.NONE,"no premature timeout");
            check(!ready(p,11,2,t,t*50L,300,3),"all subsequent targets blocked before appearance");
        }
        check(p.observeWorld(false,false,12,600,10)==PlacementTracker.Result.TIMED_OUT,"timeout after exactly 10 ticks");
        check(p.failures()==1 && p.needsReaim(),"first failure requires re-aim");
        check(!ready(p,10,1,12,600,300,3),"no same-tick retry after timeout");
        check(!ready(p,10,1,13,650,300,3),"fresh rays must be reacquired after timeout");
        check(!ready(p,10,1,14,700,300,3),"re-aim also respects the settle count");
        check(ready(p,10,1,15,750,300,3),"three fresh settled ray samples permit reassessment");
        p.clicked(10,face(1),15,750);
        check(p.observeWorld(false,true,16,800,10)==PlacementTracker.Result.NONE,"predicted block can appear before confirmation");
        check(p.observeWorld(false,false,25,1250,10)==PlacementTracker.Result.TIMED_OUT,"predicted block may still be rolled back later");
        check(p.timeoutSawPrediction(),"timeout distinguishes client prediction rolled back by server");
        check(p.backoffUntil()==1750 && p.failures()==2,"two failures enforce 500ms backoff");
        ready(p,10,1,26,1300,300,3);ready(p,10,1,27,1350,300,3);ready(p,10,1,28,1400,300,3);
        ready(p,10,1,33,1650,300,3);ready(p,10,1,34,1700,300,3);
        check(!ready(p,10,1,35,1749,300,3),"no third click during backoff");
        check(ready(p,10,1,36,1750,300,3),"backoff expires inclusively with fresh settled aim");
        p.clicked(10,face(1),36,1750);
        check(p.observeWorld(true,false,37,1950,10)==PlacementTracker.Result.CONFIRMED,"actual world appearance confirms");
        check(p.failures()==0 && p.lastConfirmed()==1950,"success resets failure count and timestamps appearance");
        check(p.medianLatencyMs()==200 && p.latencySettleBonusTicks()==1 && p.confirmationTimeoutTicks()>=17,
                "measured latency increases settle; confirmation has its own conservative window");
        check(!ready(p,11,2,38,2249,300,4),"cooldown measured from appearance, not click");
        check(!ready(p,11,2,39,2250,300,4),"one tick after cooldown is still below the adaptive settle window");
        check(!ready(p,11,2,40,2300,300,4),"adaptive settle keeps requiring consecutive fresh ticks");
        check(ready(p,11,2,41,2350,300,4),"minimum after appearance plus four settled ticks permits next click");
        check(!p.canClick(11,face(2),true,41,2350,300,4),"occupied position cannot be clicked");
        check(!p.canClick(12,face(2),false,41,2350,300,4),"target changed between aiming and click");
        check(!p.canClick(11,face(3),false,41,2350,300,4),"face changed between aiming and click");
        check(!p.canClick(11,face(2),false,42,2350,300,4),"stale ray cannot be reused");

        PlacementTracker duplicate=new PlacementTracker();
        duplicate.clicked(1,face(5),0,1000);
        duplicate.observeWorld(false,false,10,1500,10);
        ready(duplicate,1,5,11,1550,600,1);
        check(!ready(duplicate,1,5,12,1599,600,1),"same face cannot be clicked twice within interval, even after timeout");
        check(ready(duplicate,1,5,13,1600,600,1),"face cooldown expires");

        for(var mode:new VerticalCycle.Mode[]{VerticalCycle.Mode.STAIRCASE,VerticalCycle.Mode.TOWER}) {
            for(int seed=0;seed<1000;seed++) {
                VerticalCycle v=new VerticalCycle(mode);
                if(mode==VerticalCycle.Mode.STAIRCASE) {
                    check(v.phase()==VerticalCycle.Phase.BASE,"stair begins with lower riser");
                    PlacementTracker stair=new PlacementTracker();
                    stair.clicked(1,face(1),0,0);
                    check(!ready(stair,2,2,1,500,150,1),"upper stair block cannot precede lower confirmation");
                    stair.observeWorld(true,false,2,550,10);v.basePresent();
                    check(!ready(stair,2,2,3,699,150,1),"lower-to-upper interval starts at lower appearance");
                    check(ready(stair,2,2,4,700,150,1),"upper has same confirmation/cooldown gate");
                    v.arrived();
                }
                v.launch(1000,20,new Random(seed));
                check(v.delay()>=213 && v.delay()<=288,"jump-click jitter +/-15 percent");
                check(!v.canPlace(1400,102.2,102,true,true),"placement requires jump input to have been issued");
                check(v.virtualJump(20) && !v.virtualJump(21),"one-tick virtual jump pulse");v.issued();
                check(!v.virtualJump(20),"no repeated jump pulse");
                check(!v.canPlace(1000+v.delay()-1,102.2,102,true,true),"no click before randomized delay");
                check(!v.canPlace(1400,101.99,102,true,true),"feet must clear target top");
                check(!v.canPlace(1400,102.2,102,false,true),"body intersection prohibits placement");
                check(!v.canPlace(1400,102.2,102,true,false),"real ray is mandatory");
                check(v.canPlace(1400,102.2,102,true,true),"jump then sufficient height and natural face allow click");
                v.confirmed();check(v.phase()==VerticalCycle.Phase.LAND,"wait for landing after confirmation");
                check(!v.canPlace(1400,102.2,102,true,true),"no second click during landing");
                v.landed();check(v.phase()==(mode==VerticalCycle.Mode.STAIRCASE?VerticalCycle.Phase.BASE:VerticalCycle.Phase.READY),"cycle advances after landing");
            }
        }
        for(double feet=100;feet<102.5;feet+=.01)
            check(VerticalCycle.outsideBody(.2,feet,.2,.8,feet+1.8,.8,0,101,0)==(feet>=102),"exact full-block/body exclusion");
        check(VerticalCycle.outsideBody(1,101,0,1.6,102.8,.6,0,101,0),"mere face contact is not intersection");
        check(VerticalCycle.mode("invalid")==VerticalCycle.Mode.HORIZONTAL && VerticalCycle.mode(null)==VerticalCycle.Mode.HORIZONTAL,"invalid mode fallback");
        check(!VerticalCycle.manualOverride(false,false,false,false,false),"virtual jump never enters physical key state");
        SpeedbridgeLogic.AutoControl auto=new SpeedbridgeLogic.AutoControl();auto.resume();
        check(auto.movement(true,true,VerticalCycle.manualOverride(false,false,false,false,false)),"synthesized jump does not pause auto");
        check(!auto.movement(true,true,VerticalCycle.manualOverride(false,false,false,false,true)),"physical jump pauses auto");
        for(boolean adaptive:new boolean[]{false,true})for(int minimum:new int[]{150,300,600}) {
            HumanTiming timing=new HumanTiming(new Random(42));timing.configurePacing(adaptive,minimum);timing.reset(0);timing.placed(500);
            check(!timing.click(500+minimum-1,2,false),"absolute minimum independent of humanize and adapter toggles");
            timing.reset(600);check(!timing.click(500+minimum-1,2,false),"reset cannot bypass confirmed-placement floor");
        }
        for(int seed=0;seed<100;seed++) {
            Random random=new Random(seed);PlacementTracker series=new PlacementTracker();
            long nextTarget=1,clickedAt=-1,previousConfirmed=-1;boolean prediction=false,authoritative=false;
            for(long tick=0;tick<2000;tick++) {
                long now=tick*50;
                if(series.pending()!=null) {
                    var r=series.observeWorld(authoritative,prediction,tick,now,series.confirmationTimeoutTicks());
                    if(r==PlacementTracker.Result.CONFIRMED) {previousConfirmed=now;nextTarget++;prediction=false;authoritative=false;}
                }
                if(ready(series,nextTarget,(int)nextTarget,tick,now,300,1)) {
                    check(series.pending()==null,"one outstanding click in random sequence");
                    check(previousConfirmed<0 || now-previousConfirmed>=300,"all random click series respect appearance floor");
                    if(series.failures()>=2)check(now>=series.backoffUntil(),"loss series respects backoff");
                    series.clicked(nextTarget,face((int)nextTarget),tick,now);clickedAt=now;
                    prediction=random.nextDouble()>.10;
                    authoritative=prediction && random.nextDouble()>.25;
                }
                check(series.pending()==null || clickedAt>=0,"pending click has an actual prior attempt");
            }
        }
        p.confirmedLate(3000);
        check(p.failures()==0 && !p.idle(3299,300) && p.idle(3300,300),"late server appearance resets failures and starts a new hard floor");
        check(VerticalCycle.walkDirection(-.3)==1 && VerticalCycle.walkDirection(.3)==-1
                && VerticalCycle.walkDirection(0)==0,"stair centering uses bounded signed input and a dead zone");
        BridgeConfig old=new com.google.gson.Gson().fromJson("{\"straightPitch\":80.2,\"diagonalPitch\":76.3,\"autoBuild\":true}",BridgeConfig.class);
        old.validate();check(old.buildMode.equals("HORIZONTAL") && old.staircasePitch==82 && old.towerPitch==88,"old config gets vertical defaults");
        check(old.aimSettleTicks==3 && old.faceEdgeMargin==0.15,"old config gets click-stability defaults");
        check(old.requireConfirmedSupport,"old config gets confirmed-support default");
        check(old.straightPitch==80.2 && old.diagonalPitch==76.3 && old.autoBuild,"old user values preserved");
        old.buildMode="oops";old.staircasePitch=Double.NaN;old.towerPitch=99;old.aimSettleTicks=0;old.faceEdgeMargin=1;old.validate();
        check(old.buildMode.equals("HORIZONTAL") && old.staircasePitch==82 && old.towerPitch==90,"vertical config validation");
        check(old.aimSettleTicks==1 && old.faceEdgeMargin==0.30,"stability config validation");
        old.requireConfirmedSupport=false; old.validate();
        check(!old.requireConfirmedSupport,"explicit confirmed-support opt-out is preserved");
        horizontalNetworkTests();
        for(double margin:new double[]{.05,.15,.30}) for(double yaw:new double[]{0,45,90,135,180,225,270,315})
            for(double x:new double[]{.35,.5,.65}) for(double z:new double[]{.35,.5,.65}) for(double preferred:new double[]{82,88}) {
                double pitch=BridgeMath.safeVerticalPitch(x,z,0,0,yaw,preferred,margin);
                check(pitch>=preferred && pitch<=90,"safe jump pitch respects requested steepness");
                for(double height:new double[]{1.27,1.62,2.04,2.62,2.872}) {
                    double r=height/Math.tan(Math.toRadians(pitch));
                    double hitX=x-Math.sin(Math.toRadians(yaw))*r,hitZ=z+Math.cos(Math.toRadians(yaw))*r;
                    check(hitX>=margin-1e-6 && hitX<=1-margin+1e-6 && hitZ>=margin-1e-6 && hitZ<=1-margin+1e-6,
                        "fixed launch direction stays in central top-face zone throughout jump");
                }
            }
        for(var direction:net.minecraft.util.math.Direction.values()) {
            var origin=net.minecraft.util.math.BlockPos.ORIGIN;
            var center=new net.minecraft.util.math.Vec3d(.5,.5,.5);
            var normal=new net.minecraft.util.math.Vec3d(direction.getOffsetX(),direction.getOffsetY(),direction.getOffsetZ());
            var eye=center.add(normal.multiply(2));
            var shape=net.minecraft.util.shape.VoxelShapes.fullCube();
            check(shape.raycast(eye,center.add(normal.multiply(.501)),origin)==null,"ray ending outside face cannot validate that face");
            var hit=shape.raycast(eye,center.add(normal.multiply(.499999)),origin);
            check(hit!=null && hit.getSide()==direction,"ray ending just inside surface validates exact real face");
        }
        PlacementTracker freshAim=new PlacementTracker();
        ready(freshAim,1,1,1,50,0,2);check(ready(freshAim,1,1,2,100,0,2),"initial aim settled");
        freshAim.resetAim();check(!ready(freshAim,1,1,3,150,0,2),"new point on same face requires fresh settle");
        System.out.println("PASS: "+assertions+" placement/vertical assertions (unit and simulated ticks; not an in-game test).");
    }
}
