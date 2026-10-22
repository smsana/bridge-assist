package dev.bridgeassist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.LoggerFactory;

public final class BridgeConfig {
    public String buildMode = "HORIZONTAL";
    public double staircasePitch = 82.0;
    public double towerPitch = 88.0;
    public int aimSettleTicks = 3;
    public double faceEdgeMargin = 0.15;
    public boolean requireConfirmedSupport = true;
    public boolean adaptivePacing = true;
    public int minPlaceIntervalMs = 300;
    public String stanceMode = "FOLLOW_INPUT";
    public boolean autoBuild = false;
    public String autoBuildKey = "key.keyboard.b";
    public boolean humanize = true;
    public boolean randomPauses = true;
    public double humanStrength = .6;
    public double pauseIntervalSeconds = 3;
    public double pauseMinMs = 200;
    public double pauseMaxMs = 500;
    public double straightPitch = 79.5;
    public double diagonalPitch = 77.0;
    public double yawSpeedDegreesPerSecond = 220;
    public double pitchSpeedDegreesPerSecond = 160;
    public double sneakDistance = 0.10;
    public double manualOverrideDegrees = 2.0;
    public static BridgeConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("bridgeassist.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        BridgeConfig config = new BridgeConfig();
        try {
            if (Files.exists(path)) {
                BridgeConfig read = gson.fromJson(Files.readString(path), BridgeConfig.class);
                if (read != null) config = read;
            } else {
                Files.createDirectories(path.getParent());
                Files.writeString(path, gson.toJson(config));
            }
        } catch (Exception e) {
            LoggerFactory.getLogger("bridgeassist").warn("Cannot load bridgeassist.json; using defaults", e);
        }
        config.validate();config.save();return config;
    }
    public void validate() {
        minPlaceIntervalMs=Math.max(150,Math.min(600,minPlaceIntervalMs));
        buildMode=VerticalCycle.mode(buildMode).name();
        staircasePitch=valid(staircasePitch,77,89,82);
        towerPitch=valid(towerPitch,83,90,88);
        aimSettleTicks=(int)Math.max(1,Math.min(6,aimSettleTicks));
        faceEdgeMargin=valid(faceEdgeMargin,.05,.30,.15);
        stanceMode=SpeedbridgeLogic.stance(stanceMode).name();
        if(autoBuildKey==null || autoBuildKey.isBlank()) autoBuildKey="key.keyboard.b";
        humanStrength=valid(humanStrength,0,1,.6);
        pauseIntervalSeconds=valid(pauseIntervalSeconds,1,15,3);
        pauseMinMs=valid(pauseMinMs,0,1000,200);
        pauseMaxMs=valid(pauseMaxMs,pauseMinMs,1000,Math.max(500,pauseMinMs));
        straightPitch=valid(straightPitch,77,83,79.5);
        diagonalPitch=valid(diagonalPitch,74,82,77);
        yawSpeedDegreesPerSecond=valid(yawSpeedDegreesPerSecond,30,360,220);
        pitchSpeedDegreesPerSecond=valid(pitchSpeedDegreesPerSecond,30,240,160);
        sneakDistance=valid(sneakDistance,.02,.30,.1);
        manualOverrideDegrees=valid(manualOverrideDegrees,.5,15,2);
    }
    public void save() {
        validate();
        try {
            Path p=FabricLoader.getInstance().getConfigDir().resolve("bridgeassist.json");
            Files.createDirectories(p.getParent());
            Files.writeString(p,new GsonBuilder().setPrettyPrinting().create().toJson(this));
        } catch(Exception e) {LoggerFactory.getLogger("bridgeassist").warn("Cannot save settings",e);}
    }
    private static double valid(double n, double min, double max, double fallback) {
        return Double.isFinite(n) ? Math.max(min, Math.min(max, n)) : fallback;
    }
}
