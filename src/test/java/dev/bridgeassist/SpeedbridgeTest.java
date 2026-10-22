package dev.bridgeassist;

public final class SpeedbridgeTest {
    private static int assertions;
    private static void check(boolean b, String message) {
        assertions++;
        if (!b) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        for (double yaw = -720; yaw <= 720; yaw += .25) for (int side = -1; side <= 1; side++) {
            var h = SpeedbridgeLogic.heading(yaw, side);
            var world = BridgeMath.rotateInput(side, -1, h.cameraYaw());
            double len = Math.hypot(world.sideways(), world.forward());
            check(Math.abs(world.sideways()/len-h.dx()) < 1e-12, "cardinal X");
            check(Math.abs(world.forward()/len-h.dz()) < 1e-12, "cardinal Z");
            check(Math.abs(h.cameraYaw()-yaw) <= 45.00001, "short turn only");
        }
        for (int fps : new int[]{20, 30, 60, 144, 240}) {
            for (double target : new double[]{-180, -80, -1, 1, 80, 180}) {
                SmoothAxis axis = new SmoothAxis();
                double position = 0;
                for (int frame = 0; frame < fps*3; frame++) {
                    double dt = 1.0/fps;
                    double delta = axis.step(target-position, dt, 220);
                    check(Math.abs(delta) <= 220*dt+1e-9, "speed bound");
                    check(delta*target >= -1e-8, "no shaking");
                    position += delta;
                    check(Math.abs(position) <= Math.abs(target)+1e-8, "no overshoot");
                }
                check(Math.abs(position-target) < .005, "converges at every FPS");
            }
        }
        for (double sensitivity : new double[]{0, .1, .5, 1}) {
            double factor = SpeedbridgeLogic.mouseDegreesPerUnit(sensitivity);
            for (double degrees : new double[]{-5, -.1, .1, 5})
                check(Math.abs((degrees/factor)*factor-degrees) < 1e-12, "mouse scaling");
        }
        check(!SpeedbridgeLogic.sneakAtEdge(false, .6, .1), "walk between edges");
        check(SpeedbridgeLogic.sneakAtEdge(false, .05, .1), "shift at edge");
        check(!SpeedbridgeLogic.sneakAtEdge(true, -.2, .1), "unshift after placement");
        check(Double.isFinite(new SmoothAxis().step(80, .01, 160)), "finite");
        check(SpeedbridgeLogic.stance("bad")==SpeedbridgeLogic.Stance.FOLLOW_INPUT,"bad stance fallback");
        check(SpeedbridgeLogic.stance(null)==SpeedbridgeLogic.Stance.FOLLOW_INPUT,"null stance fallback");
        for(double yaw=-360;yaw<360;yaw+=.5) {
            var straight=SpeedbridgeLogic.fixedHeading(yaw,0);
            var diagonal=SpeedbridgeLogic.fixedHeading(yaw,1);
            check(Math.abs(SpeedbridgeLogic.wrap(diagonal.cameraYaw()-straight.cameraYaw())+45)<1e-8,"exact diagonal stance offset");
            check(straight.dx()==diagonal.dx() && straight.dz()==diagonal.dz(),"stance never changes bridge route");
            var movement=BridgeMath.rotateInput(1,-1,diagonal.cameraYaw());
            double length=Math.hypot(movement.sideways(),movement.forward());
            check(Math.abs(movement.sideways()/length-straight.dx())<1e-8,"virtual diagonal remains on route X");
            check(Math.abs(movement.forward()/length-straight.dz())<1e-8,"virtual diagonal remains on route Z");
            SmoothAxis turn=new SmoothAxis();double position=straight.cameraYaw();
            for(int n=0;n<240;n++) {
                double delta=turn.step(SpeedbridgeLogic.wrap(diagonal.cameraYaw()-position),1.0/60,220);
                check(Math.abs(delta)<=220.0/60+1e-8,"forced stance uses smooth bounded yaw, no snaps");position+=delta;
            }
            check(Math.abs(position-diagonal.cameraYaw())<.005,"forced diagonal converges");
        }
        for(int key=0;key<5;key++) {
            SpeedbridgeLogic.AutoControl auto=new SpeedbridgeLogic.AutoControl();
            check(!auto.movement(true,true,false),"saved auto setting cannot start motion on login");
            auto.resume();check(auto.movement(true,true,false),"auto generates movement without physical keys");
            boolean[] keys=new boolean[5];keys[key]=true;
            boolean physical=SpeedbridgeLogic.physicalMovement(keys[0],keys[1],keys[2],keys[3],keys[4]);
            check(!auto.movement(true,true,physical),"each real WASD/jump stops virtual movement");
            check(!auto.movement(true,true,false),"release does not silently resume auto");
            auto.resume();check(auto.movement(true,true,false),"explicit resume restores virtual input");
            auto.pause();check(!auto.movement(true,true,false),"mouse/focus pause stops input");
            auto.resume();check(!auto.movement(false,true,false),"G master suppresses auto input");
        }
        check(SpeedbridgeLogic.requestedSide("DIAGONAL",0,false)==1,"no physical strafe needed");
        check(SpeedbridgeLogic.requestedSide("STRAIGHT",1,false)==0,"straight overrides strafe");
        check(SpeedbridgeLogic.requestedSide("FOLLOW_INPUT",1,false)==1,"legacy follows physical input");
        check(SpeedbridgeLogic.requestedSide("FOLLOW_INPUT",1,true)==0,"auto follow input defaults straight");
        check(!PlacementTracker.allowMovementOnSupport(true, true, true),"unconfirmed predicted support blocks virtual movement when required");
        check(PlacementTracker.allowMovementOnSupport(true, true, false),"movement resumes automatically after support confirmation");
        check(PlacementTracker.allowMovementOnSupport(false, true, true),"requireConfirmedSupport=false preserves 2.4.0 movement behavior");
        SpeedbridgeLogic.BridgeAxis lock=new SpeedbridgeLogic.BridgeAxis();
        var first=lock.fixed(90,1);
        for(double noise:new double[]{-.084,-.001,0,.001,.084}) {
            var resumed=lock.fixed(first.cameraYaw()+noise,1);
            check(resumed.dx()==first.dx() && resumed.dz()==first.dz() && resumed.cameraYaw()==first.cameraYaw(),"pause/resume retains axis at diagonal rounding boundary");
        }
        lock.reset();check(lock.fixed(0,0).cameraYaw()==0,"G clears locked route for a new bridge");
        System.out.println("PASS: " + assertions + " speedbridge/mouse assertions; no game-client test.");
    }
}
