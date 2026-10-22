package dev.bridgeassist;
import java.util.Random;
public final class HumanTimingTest {
    private static int assertions;
    private static void check(boolean condition,String message) {assertions++;if(!condition)throw new AssertionError(message);}
    public static void main(String[] args) {
        int[] durations=new int[301];int clicksDelayed=0,releasesDelayed=0,releasesPastTick=0;
        for(int seed=0;seed<2000;seed++) {
            HumanTiming t=new HumanTiming(new Random(seed));t.configure(.6,3,200,500);t.reset(0);
            check(!t.pause(2399,true,true),"pause not before earliest interval");
            check(!t.pause(5000,false,true),"unsafe support postpones pause");
            check(t.pause(5001,true,true),"pending pause starts on safe floor");
            int duration=0;
            for(int now=5001;now<=5502;now++) {
                boolean pause=t.pause(now,true,true);
                if(pause)duration++;
                if(!pause && duration>0)break;
            }
            check(duration>=200 && duration<=500,"pause in requested range");durations[duration-200]++;
            check(!t.pause(5503,true,false),"pause switch cancels pending hold");
            check(t.sneak(6000,true,true),"emergency sneak never delayed");
            check(t.sneak(6001,true,true),"emergency remains held");
            t.sneak(6002,false,false);
            check(t.sneak(6100,true,true),"new edge remains safe");
            boolean first=t.click(7000,123);if(!first)clicksDelayed++;
            check(t.click(7065,123),"bounded click delay");t.attempted();
            t.placed(7100);if(t.releaseHeld(7100))releasesDelayed++;
            if(t.releaseHeld(7150))releasesPastTick++;
            check(!t.releaseHeld(7241),"bounded sneak release delay");
            for(int ms=0;ms<6000;ms+=17) {
                t.updateCamera(ms);
                check(Math.abs(t.yawOffset(ms))<=.08400001,"bounded yaw pulse");
                check(Math.abs(t.pitchOffset())<=.1500001,"bounded pitch variation");
                check(t.speedFactor()>=.892 && t.speedFactor()<=1.108,"bounded tempo");
            }
        }
        check(clicksDelayed>100 && clicksDelayed<1500,"clicks include immediate and delayed cases");
        check(releasesDelayed>100 && releasesDelayed<1500,"release includes immediate and delayed cases");
        check(releasesPastTick>50,"release variation survives 50ms game tick");
        for(int block=-100;block<100;block++) for(double overlap:new double[]{.000001,.0001,.000821,.001,.01}) {
            check(SpeedbridgeLogic.overlapMin(block+1-overlap)==block,"thin support overlap retained");
            check(SpeedbridgeLogic.overlapMax(block+overlap)==block,"thin opposite support overlap retained");
        }
        check(SpeedbridgeLogic.overlapMin(16.0)==16,"mere face touching excluded");
        int unique=0;for(int count:durations)if(count>0)unique++;
        check(unique>200,"pause duration is variable, not a fixed cycle");
        // Tick-level simulations exercise the exact gate used by the client.
        for(int minimum:new int[]{150,175,300,425,600})for(int seed=0;seed<80;seed++) {
            HumanTiming t=new HumanTiming(new Random(seed));t.configurePacing(true,minimum);t.reset(0);
            long last=-1;int placed=0;
            for(long now=0;now<40000;now+=25) {
                if(t.click(now,placed,true)) {
                    if(last>=0)check(now-last>=minimum,"hard configured floor, even on negative roll");
                    last=now;t.attempted();t.placed(now);placed++;
                    check(t.variedTarget()>=minimum*.85-1e-8 && t.variedTarget()<=minimum*1.15+1e-8,"target within +/-15%");
                    check(t.meter().size()<=8,"sliding window bounded");
                }
                check(t.sneak(now,true,true),"adaptive wait never delays emergency crouch");
            }
            check(placed>40,"controller does not stall");
            for(int n=0;n<100;n++)t.placed(last+=5);
            check(t.adaptiveDelay()>0 && t.adaptiveDelay()<=600,"fast external samples increase bounded delay");
            for(int n=0;n<100;n++)t.placed(last+=2000);
            check(t.adaptiveDelay()==0,"slow series removes extra delay rather than growing forever");
            t.configurePacing(false,minimum);
            check(t.remaining(last)==minimum && !t.click(last,123,false),"disabled adapter retains absolute confirmed-placement floor");
        }
        SpeedbridgeLogic.PlaceMeter meter=new SpeedbridgeLogic.PlaceMeter();
        for(int n=0;n<20;n++)meter.placed(n*300);
        check(meter.size()==8 && meter.average()==300,"actual interval rolling average");
        for(int seed=0;seed<100;seed++) {
            HumanTiming current=new HumanTiming(new Random(seed));
            LegacyHumanTiming legacy=new LegacyHumanTiming(new Random(seed));
            current.configurePacing(false,300);current.reset(0);legacy.reset(0);
            for(int now=0;now<20000;now+=50) {
                check(current.pause(now,true,true)==legacy.pause(now,true,true),"legacy pause bit-for-bit");
                check(current.click(now,now/1000,false)==(current.remaining(now)==0),"disabled adapter retains hard floor without changing random sequence");
                check(current.click(now,now/1000)==legacy.click(now,now/1000),"legacy random click bit-for-bit");
                check(current.sneak(now,now%300<100,false)==legacy.sneak(now,now%300<100,false),"legacy crouch bit-for-bit");
                if(now%350==0) {current.attempted();legacy.attempted();current.placed(now);legacy.placed(now);}
                check(current.releaseHeld(now)==legacy.releaseHeld(now),"legacy release bit-for-bit");
                current.updateCamera(now);legacy.updateCamera(now);
                check(current.yawOffset(now)==legacy.yawOffset(now),"legacy camera bit-for-bit");
            }
        }
        BridgeConfig conf=new com.google.gson.Gson().fromJson("{\"straightPitch\":80.5,\"humanStrength\":0.4}",BridgeConfig.class);
        conf.validate();
        check(conf.adaptivePacing && conf.minPlaceIntervalMs==300 && !conf.autoBuild,"old JSON gets new defaults");
        check(conf.straightPitch==80.5 && conf.humanStrength==.4,"existing user values retained");
        conf.stanceMode="bad";conf.minPlaceIntervalMs=900;conf.validate();
        check(conf.stanceMode.equals("FOLLOW_INPUT") && conf.minPlaceIntervalMs==600,"invalid config normalized");
        System.out.println("PASS: "+assertions+" human-timing assertions; varied durations="+unique);
    }
}
