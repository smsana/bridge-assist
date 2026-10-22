package dev.bridgeassist;

import dev.bridgeassist.mixin.ClientWorldAccessor;
import dev.bridgeassist.mixin.ClientPlayerEntityAccessor;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import dev.bridgeassist.mixin.MinecraftClientAccessor;
import dev.bridgeassist.mixin.PendingUpdateManagerAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Assisted human-style ninja bridging: fixed view, backward keys, actual sneak pulses. */
public final class BridgeAssistClient implements ClientModInitializer {
    public record MouseDelta(double x, double y) {
        static final MouseDelta ZERO = new MouseDelta(0, 0);
    }
    private record AimSolution(BlockPos support, Direction side, Vec3d point, float yaw, float pitch, double score) {}
    private record AimAnalysis(BlockHitResult hit, PlacementTracker.Face face, PlacementTracker.FailureReason failure) {}

    private static final Logger LOGGER = LoggerFactory.getLogger("bridgeassist");
    private static final double STABLE_AIM_DEGREES = 0.5;
    private static final HorizontalCycle horizontal = new HorizontalCycle();
    private static AimSolution lockedPlacementAim;
    private static BlockPos lockedPlacementTarget;
    private static int lockedVariant = -1;
    private static BlockPos lastClickTarget;
    private static Vec3d lastClickPoint;
    private static boolean horizontalClickAllowed;
    private static long aimVariationSeed = new java.util.Random().nextLong();

    private static KeyBinding toggle, settings, autoToggle;
    private static final SpeedbridgeLogic.AutoControl auto = new SpeedbridgeLogic.AutoControl();
    private static boolean waitingFace, autoStartOnClose;
    private static final SpeedbridgeLogic.BridgeAxis bridgeAxis = new SpeedbridgeLogic.BridgeAxis();
    public static Text autoKeyLabel() {return autoToggle.getBoundKeyLocalizedText();}
    public static void bindAutoKey(int key,int scan) {
        autoToggle.setBoundKey(InputUtil.fromKeyCode(key,scan));KeyBinding.updateKeysByCode();
        config.autoBuildKey=autoToggle.getBoundKeyTranslationKey();
        MinecraftClient.getInstance().options.write();config.save();
    }
    public static void setAutoBuild(boolean value) {
        if(value && !config.autoBuild)bridgeAxis.reset();
        stopRun();config.autoBuild=value;
        autoStartOnClose=value && MinecraftClient.getInstance().currentScreen instanceof BridgeSettingsScreen;
        if(value) {if(!enabled)activeMode=VerticalCycle.mode(config.buildMode);enabled=true;pausedByMouse=false;auto.resume();} else auto.pause();
        config.save();
    }
    private static boolean physicalMovement(MinecraftClient c) {
        return VerticalCycle.manualOverride(c.options.forwardKey.isPressed(),c.options.leftKey.isPressed(),
                c.options.backKey.isPressed(),c.options.rightKey.isPressed(),c.options.jumpKey.isPressed());
    }
    private static boolean automatic() {return auto.movement(enabled,config.autoBuild,false);}
    private static boolean wantsMovement(MinecraftClient c) {return automatic() || (!config.autoBuild && c.options.backKey.isPressed());}
    private static void pauseAuto() {
        boolean was=automatic();auto.pause();stopRun();
        MinecraftClient c=MinecraftClient.getInstance();
        if(was && c.player!=null)warn(c,"message.bridgeassist.auto_paused");
    }
    public static void settingsOpened() {autoStartOnClose=false;if(config.autoBuild)pauseAuto();else stopRun();}
    private static final HumanTiming timing=new HumanTiming(new java.util.Random());
    private static long now() {return System.nanoTime()/1_000_000;}
    public static BridgeConfig config() {return config;}
    public static void settingsChanged() {stopRun();if(autoStartOnClose && config.autoBuild) {auto.resume();autoStartOnClose=false;}}
    private static BridgeConfig config;
    private static PlacementTracker placements = new PlacementTracker();
    private static Object observedWorld;
    private static BlockPos unconfirmedTarget;
    private static VerticalCycle.Mode activeMode = VerticalCycle.Mode.HORIZONTAL;
    private static VerticalCycle vertical;
    private static BlockPos verticalBase;
    private static boolean supportLost, stairReserved, failureStop;
    private static float axisYaw;
    private static int verticalWalk=1;
    private static final java.util.Random jumpRandom = new java.util.Random();
    private static boolean enabled, running, pausedByMouse, sneak, holdMovement, edgeBrake;
    private static long brakeUntil;
    private static int side, bridgeY, originalSlot = -1, autoSlot = -1;
    private static int ticks, lastWarning = -100, lastFailureTick = -100;
    private static float targetYaw, targetPitch;
    private static Direction travel;
    private static final SmoothAxis yawAxis = new SmoothAxis();
    private static final SmoothAxis pitchAxis = new SmoothAxis();
    private static BlockPos repeatTarget, lastFailureTarget;
    private static int repeatAttempts;
    private static PlacementTracker.FailureReason lastFailureReason;

    @Override public void onInitializeClient() {
        config = BridgeConfig.load();
        toggle = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bridgeassist.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G,
                "category.bridgeassist"));
        settings=KeyBindingHelper.registerKeyBinding(new KeyBinding("key.bridgeassist.settings",
                InputUtil.Type.KEYSYM,GLFW.GLFW_KEY_H,"category.bridgeassist"));
        InputUtil.Key autoKey;
        try {autoKey=InputUtil.fromTranslationKey(config.autoBuildKey);}
        catch(IllegalArgumentException e) {autoKey=InputUtil.fromKeyCode(GLFW.GLFW_KEY_B,0);config.autoBuildKey=autoKey.getTranslationKey();config.save();}
        autoToggle=KeyBindingHelper.registerKeyBinding(new KeyBinding("key.bridgeassist.auto",
                autoKey.getCategory(),autoKey.getCode(),"category.bridgeassist"));
        ClientTickEvents.START_CLIENT_TICK.register(BridgeAssistClient::startTick);
        ClientTickEvents.END_CLIENT_TICK.register(c->{
            try {endTick(c);} finally {tickInteractionRotation(c);}
        });
    }

    private static void restoreVisualRotation(MinecraftClient c) {
        if(c.player!=null && c.getNetworkHandler()!=null)
            c.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                    c.player.getYaw(),c.player.getPitch(),c.player.isOnGround(),c.player.horizontalCollision));
    }
    private static void tickInteractionRotation(MinecraftClient c) {
        boolean active=InteractionRotationManager.isActive();
        InteractionRotationManager.tick();
        if(active && !InteractionRotationManager.isActive())restoreVisualRotation(c);
    }
    private static void prepareInteractionRotation(MinecraftClient c) {
        InteractionRotationManager.requestAngles(targetYaw,targetPitch,Math.max(2,effectiveAimSettleTicks()+1));
        if(!InteractionRotationManager.hasSentCurrentAngles() && c.getNetworkHandler()!=null) {
            var rotation=InteractionRotationManager.getAngles();
            c.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                    rotation.yaw(),rotation.pitch(),c.player.isOnGround(),c.player.horizontalCollision));
        }
    }
    private static BlockHitResult interactionHit(MinecraftClient c) {
        var rotation=InteractionRotationManager.getAngles();
        return rotation==null?null:raycastAngles(c,rotation.yaw(),rotation.pitch());
    }
    private static BlockHitResult raycastAngles(MinecraftClient c,float yaw,float pitch) {
        if(c.player==null || c.world==null)return null;
        Vec3d eye=c.player.getEyePos();
        Vec3d end=eye.add(Vec3d.fromPolar(pitch,yaw).multiply(c.player.getBlockInteractionRange()));
        return c.world.raycast(new RaycastContext(eye,end,RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,c.player));
    }

    private static void startTick(MinecraftClient c) {
        ticks++;
        horizontalClickAllowed=false;
        sneak = holdMovement = false;
        observePlacements(c);
        if (c.player == null || c.world == null) {
            enabled = pausedByMouse = false;auto.pause();bridgeAxis.reset();failureStop=false;
            stopRun();
            originalSlot = autoSlot = -1;
            return;
        }
        while (toggle.wasPressed()) {
            if (c.currentScreen != null) continue;
            enabled = !enabled;bridgeAxis.reset();failureStop=false;
            if(enabled)activeMode=VerticalCycle.mode(config.buildMode);
            stopRun();
            pausedByMouse = false;
            if (!enabled) {config.autoBuild=false;auto.pause();config.save();restoreSlot(c.player);edgeBrake=false;brakeUntil=0;}
            c.player.sendMessage(Text.translatable(enabled ? "message.bridgeassist.on"
                    : "message.bridgeassist.off"), true);
        }
        String bound=autoToggle.getBoundKeyTranslationKey();
        if(!bound.equals(config.autoBuildKey)) {config.autoBuildKey=bound;config.save();}
        while(autoToggle.wasPressed()) if(c.currentScreen==null) {
            if(failureStop) {
                failureStop=false;repeatAttempts=0;repeatTarget=null;clearPlacementAim();
                if(config.autoBuild) auto.resume();
                c.player.sendMessage(Text.translatable("message.bridgeassist.failure_resume"),true);
                continue;
            }
            if(!config.autoBuild || auto.paused())setAutoBuild(true);else setAutoBuild(false);
            c.player.sendMessage(Text.translatable(config.autoBuild?"message.bridgeassist.auto_on":"message.bridgeassist.auto_off"),true);
        }
        while(settings.wasPressed()) if(c.currentScreen==null) {
            settingsOpened();c.setScreen(new BridgeSettingsScreen());
        }
        if(config.autoBuild && physicalMovement(c)) {pauseAuto();return;}
        if(config.autoBuild && (!usable(c) || c.options.attackKey.isPressed() || c.options.useKey.isPressed() || c.player.isUsingItem())) {
            pauseAuto();return;
        }
        if (failureStop) {sneak=holdMovement=true;return;}
        if (!wantsMovement(c)) {
            stopRun();
            pausedByMouse = false;
            return;
        }
        if (!enabled || !usable(c) || c.options.forwardKey.isPressed()
                || c.options.jumpKey.isPressed() || c.options.attackKey.isPressed()
                || c.options.useKey.isPressed() || c.player.isUsingItem()) {
            stopRun();
            return;
        }
        if (pausedByMouse) {
            // Do not wrestle the real mouse. Pause until S is released.
            sneak = holdMovement = true;
            return;
        }
        int physicalSide = (c.options.leftKey.isPressed() ? 1 : 0)
                - (c.options.rightKey.isPressed() ? 1 : 0);
        int requestedSide=SpeedbridgeLogic.requestedSide(config.stanceMode,physicalSide,automatic());
        if (!running || (activeMode == VerticalCycle.Mode.HORIZONTAL && requestedSide != side)) beginRun(c, requestedSide);
        if (guardSupport(c)) return;
        if (activeMode != VerticalCycle.Mode.HORIZONTAL) { startVertical(c); return; }
        if (!c.player.isOnGround()) { sneak=holdMovement=true; return; }
        BlockPos support = findSupport(c);
        if (support == null) {
            sneak = holdMovement = true;
            return;
        }
        if (config.requireConfirmedSupport) {
            startConfirmedHorizontal(c,support);
            return;
        }
        BlockPos next = support.offset(travel);
        waitingFace=false;
        boolean hasCandidate=updateAimForPlacement(c,next,support,travel,false);
        if(config.requireConfirmedSupport && full(c,next) && predicted(c,next)) {sneak=holdMovement=true;return;}
        if(waitingPlacement() || now()<placements.backoffUntil()) {sneak=holdMovement=true;return;}
        if(automatic() && !c.world.isSpaceEmpty(c.player,c.player.getBoundingBox().offset(
                travel.getOffsetX()*.12,0,travel.getOffsetZ()*.12))) {
            sneak=holdMovement=true;return;
        }
        int slot = findBlockSlot(c, next);
        if (slot < 0) {
            sneak = holdMovement = true;
            warn(c, "message.bridgeassist.no_blocks");
            return;
        }
        equip(c,slot);
        long now=now();timing.updateCamera(now);
        boolean safePause=c.player.getBoundingBox().minX>=support.getX()+.005
                && c.player.getBoundingBox().maxX<=support.getX()+.995
                && c.player.getBoundingBox().minZ>=support.getZ()+.005
                && c.player.getBoundingBox().maxZ<=support.getZ()+.995;
        if(config.humanize && timing.pause(now,safePause,config.randomPauses)) {
            sneak=holdMovement=true;return;
        }
        boolean ready = aimReady(c);
        double distance = edgeDistance(c.player, support);
        if (!ready) {
            sneak = holdMovement = true;
            // A side face is invisible from the centre of its own support block.
            // Approach crouched on the existing floor instead of waiting forever for that face.
            if(!hasCandidate && distance>-.12 && canReplace(c,next)
                    && c.world.isSpaceEmpty(c.player,c.player.getBoundingBox().offset(
                    travel.getOffsetX()*.08,0,travel.getOffsetZ()*.08)))holdMovement=false;
            return;
        }
        boolean nextIsFloor = movementSupportPresent(c,next);
        // Ordinary unshifted movement across each new block. Shift only at its end.
        boolean emergency=SpeedbridgeLogic.sneakAtEdge(nextIsFloor,distance,config.sneakDistance);
        boolean early=SpeedbridgeLogic.sneakAtEdge(nextIsFloor,distance,config.sneakDistance+timing.edgeExtra());
        sneak=config.humanize?timing.sneak(now,early,emergency):emergency;
        // Stop rather than walk into water, non-replaceable obstacles or a workstation.
        if (!nextIsFloor && distance <= config.sneakDistance && !canReplace(c, next))
            sneak = holdMovement = true;
        if(!nextIsFloor && distance<=-.12) {
            // Finish the configured dwell while stationary, even with confirmed-support opt-out.
            sneak=holdMovement=true;waitingFace=true;
        }
        if(automatic() && !nextIsFloor && distance<=-.26 && !placementFace(c,support,next)) {
            // Keep re-evaluating the real ray while stationary; never fabricate a hit.
            sneak=holdMovement=true;waitingFace=true;
        }
    }

    private static boolean confirmedApproach() {
        return config.requireConfirmedSupport && (activeMode==VerticalCycle.Mode.HORIZONTAL
                || activeMode==VerticalCycle.Mode.STAIRCASE && vertical!=null && vertical.phase()==VerticalCycle.Phase.BASE);
    }
    private static void startConfirmedHorizontal(MinecraftClient c, BlockPos support) {
        BlockPos next = support.offset(travel);
        double velocity = c.player.getVelocity().x * travel.getOffsetX()
                + c.player.getVelocity().z * travel.getOffsetZ();
        horizontal.update(confirmedFull(c,support), confirmedFull(c,next), waitingPlacement(),
                predicted(c,next), edgeDistance(c.player,support), velocity);
        horizontalClickAllowed = horizontal.allowClick();
        holdMovement = !horizontal.allowMovement();
        sneak = horizontal.crouch();
        waitingFace = false;
        int slot = findBlockSlot(c,next);
        if (slot < 0) {
            sneak=holdMovement=true;horizontalClickAllowed=false;
            warn(c,"message.bridgeassist.no_blocks");return;
        }
        equip(c,slot);
        if (!c.world.isSpaceEmpty(c.player,c.player.getBoundingBox().offset(
                travel.getOffsetX()*.08,0,travel.getOffsetZ()*.08))) {
            sneak=holdMovement=true;horizontalClickAllowed=false;return;
        }
        if (horizontal.phase()==HorizontalCycle.Phase.WAIT_CONFIRM) {
            horizontalClickAllowed=false;
            if (ticks-lastWarning>=40) warn(c,"message.bridgeassist.wait_confirmation");
            return;
        }
        if (horizontal.phase()==HorizontalCycle.Phase.CROSS) {
            clearPlacementAim();
            // Preserve the selected bridge axis while moving to the confirmed block.
            targetYaw=axisYaw;targetPitch=(float)(side==0?config.straightPitch:config.diagonalPitch);
            return;
        }
        if (!horizontalClickAllowed && horizontal.phase()==HorizontalCycle.Phase.APPROACH) {
            clearPlacementAim();
            targetYaw=axisYaw;targetPitch=(float)(side==0?config.straightPitch:config.diagonalPitch);
            return;
        }
        // Prepare the real interaction ray while braking; clicking still requires SETTLE.

        updateAimForPlacement(c,next,support,travel,false);
        boolean contained=c.player.getBoundingBox().minX>=support.getX()+.005
                && c.player.getBoundingBox().maxX<=support.getX()+.995
                && c.player.getBoundingBox().minZ>=support.getZ()+.005
                && c.player.getBoundingBox().maxZ<=support.getZ()+.995;
        if(config.humanize && timing.pause(now(),contained,config.randomPauses))horizontalClickAllowed=false;
    }
    private static void clearPlacementAim() {
        lockedPlacementAim=null;lockedPlacementTarget=null;lockedVariant=-1;
        placements.resetAim();
    }

    private static void beginRun(MinecraftClient c, int requestedSide) {
        side = requestedSide;
        clearPlacementAim();horizontal.reset();aimVariationSeed=new java.util.Random().nextLong();
        boolean follow=activeMode==VerticalCycle.Mode.HORIZONTAL && SpeedbridgeLogic.stance(config.stanceMode)==SpeedbridgeLogic.Stance.FOLLOW_INPUT;
        SpeedbridgeLogic.Heading heading=follow?SpeedbridgeLogic.heading(c.player.getYaw(),side):bridgeAxis.fixed(c.player.getYaw(),side);
        if(follow)bridgeAxis.remember(heading,side);
        targetYaw = (float) heading.cameraYaw();
        targetPitch = (float) (side == 0 ? config.straightPitch : config.diagonalPitch);
        axisYaw=targetYaw;
        if(activeMode==VerticalCycle.Mode.TOWER)targetPitch=(float)config.towerPitch;
        vertical=activeMode==VerticalCycle.Mode.HORIZONTAL?null:new VerticalCycle(activeMode);
        verticalBase=vertical==null?null:feetSupport(c);
        stairReserved=false;
        int dx = heading.dx(), dz = heading.dz();
        travel = dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST
                : dz > 0 ? Direction.SOUTH : Direction.NORTH;
        bridgeY = MathHelper.floor(c.player.getBoundingBox().minY - 0.05);
        yawAxis.reset(); pitchAxis.reset();
        timing.configure(config.humanStrength,config.pauseIntervalSeconds,(int)config.pauseMinMs,(int)config.pauseMaxMs);
        timing.configurePacing(config.adaptivePacing,config.minPlaceIntervalMs);
        timing.reset(now());
        edgeBrake=false;brakeUntil=0;
        running = true;
    }

    private static void endTick(MinecraftClient c) {
        if (!enabled || !running || pausedByMouse || !usable(c) || supportLost || failureStop
                || !wantsMovement(c) || c.options.jumpKey.isPressed()
                || c.options.attackKey.isPressed() || c.options.useKey.isPressed()) return;
        if (activeMode != VerticalCycle.Mode.HORIZONTAL) { endVertical(c); return; }
        if (!c.player.isOnGround()) return;
        if(config.requireConfirmedSupport) {
            if(!horizontalClickAllowed) return;
        } else if(holdMovement && !waitingFace) return;
        BlockPos support = findSupport(c);
        if (support != null) tryPlace(c, support.offset(travel), support, travel, false);
    }

    private static boolean predicted(MinecraftClient c, BlockPos pos) {
        var manager=((ClientWorldAccessor)c.world).bridgeassist$pendingUpdateManager();
        return ((PendingUpdateManagerAccessor)manager).bridgeassist$pendingBlocks().containsKey(pos.asLong());
    }
    private static boolean waitingPlacement() {
        return placements.pending()!=null || unconfirmedTarget!=null;
    }
    private static void confirmed(MinecraftClient c, BlockPos pos) {
        LOGGER.info("Placement reconciled target={} medianLatencyMs={}",pos,placements.medianLatencyMs());
        timing.placed(now());unconfirmedTarget=null;repeatAttempts=0;repeatTarget=null;clearPlacementAim();
        if(vertical!=null && verticalBase!=null && vertical.phase()==VerticalCycle.Phase.JUMP
                && pos.equals(verticalBase.up()))vertical.confirmed();
    }
    private static void observePlacements(MinecraftClient c) {
        if (observedWorld != c.world) {
            InteractionRotationManager.clear();
            placements = new PlacementTracker(); observedWorld = c.world;unconfirmedTarget=null;
            supportLost = false; vertical = null; failureStop=false; repeatTarget=null; repeatAttempts=0;
            lastFailureTarget=null; lastFailureReason=null;
        }
        if(c.world==null)return;
        Long key=placements.pending();
        if(key==null) {
            // A slow authoritative response may arrive after the dynamic timeout.
            if(unconfirmedTarget!=null && !predicted(c,unconfirmedTarget)) {
                BlockPos pos=unconfirmedTarget;unconfirmedTarget=null;
                if(full(c,pos)) {placements.confirmedLate(now());confirmed(c,pos);}
            }
            return;
        }
        BlockPos pos=BlockPos.fromLong(key);
        boolean predicted = predicted(c,pos);
        boolean authoritative = full(c,pos) && !predicted;
        PlacementTracker.Result result=placements.observeWorld(authoritative,predicted,ticks,now(),placements.confirmationTimeoutTicks());
        if(result==PlacementTracker.Result.CONFIRMED)confirmed(c,pos);
        else if(result==PlacementTracker.Result.TIMED_OUT) {
            yawAxis.reset();pitchAxis.reset();
            registerTimedOutAttempt(c,pos,placements.timeoutSawPrediction()
                    ? PlacementTracker.FailureReason.SERVER_CANCELLED
                    : PlacementTracker.FailureReason.UNCONFIRMED);
        }
    }
    private static boolean full(MinecraftClient c, BlockPos pos) {
        return pos!=null && c.world.getBlockState(pos).isFullCube(c.world,pos);
    }
    private static boolean confirmedFull(MinecraftClient c, BlockPos pos) {
        return pos!=null && PlacementTracker.supportConfirmed(full(c,pos), predicted(c,pos));
    }
    private static boolean movementSupportPresent(MinecraftClient c, BlockPos pos) {
        // Opting out of confirmation permits predicted blocks, never empty air.
        return pos!=null && full(c,pos) && PlacementTracker.allowMovementOnSupport(config.requireConfirmedSupport, true, predicted(c,pos));
    }
    private static BlockPos feetSupport(MinecraftClient c) {
        return feetSupport(c,false);
    }
    private static BlockPos feetSupport(MinecraftClient c, boolean confirmedOnly) {
        Box box=c.player.getBoundingBox(); int y=MathHelper.floor(box.minY-.05);
        BlockPos best=null; double nearest=Double.MAX_VALUE;
        for(int x=SpeedbridgeLogic.overlapMin(box.minX);x<=SpeedbridgeLogic.overlapMax(box.maxX);x++)
            for(int z=SpeedbridgeLogic.overlapMin(box.minZ);z<=SpeedbridgeLogic.overlapMax(box.maxZ);z++) {
                BlockPos pos=new BlockPos(x,y,z);
                double distance=pos.toCenterPos().squaredDistanceTo(c.player.getPos());
                boolean present=confirmedOnly?confirmedFull(c,pos):full(c,pos);
                if(present && distance<nearest) {nearest=distance;best=pos;}
            }
        return best;
    }
    private static boolean missingSupport(MinecraftClient c) {
        if(vertical!=null && vertical.flying()) {
            return !full(c,verticalBase)
                    || (vertical.phase()==VerticalCycle.Phase.LAND && !full(c,verticalBase.up()))
                    || c.player.getBoundingBox().minY < verticalBase.getY()+.9;
        }
        return !c.player.isOnGround() || feetSupport(c,
                activeMode==VerticalCycle.Mode.HORIZONTAL && config.requireConfirmedSupport)==null;
    }
    /** The same emergency gate is repeated immediately before virtual input is consumed. */
    private static boolean guardSupport(MinecraftClient c) {
        if(supportLost && c.player.isOnGround()
                && feetSupport(c,activeMode==VerticalCycle.Mode.HORIZONTAL && config.requireConfirmedSupport)!=null) {
            supportLost=false;
            if(vertical!=null) {
                verticalBase=feetSupport(c);vertical=new VerticalCycle(activeMode);stairReserved=false;
            }
        }
        if(supportLost || missingSupport(c)) {
            supportLost=true;sneak=holdMovement=true;waitingFace=false;return true;
        }
        return false;
    }
    private static boolean aimReady(MinecraftClient c) {
        var rotation=InteractionRotationManager.getAngles();
        return rotation!=null && InteractionRotationManager.readyForInteraction()
                && Math.abs(MathHelper.wrapDegrees(targetYaw-rotation.yaw()))<2
                && Math.abs(targetPitch-rotation.pitch())<2;
    }
    private static boolean clickAimStable(MinecraftClient c) {
        var rotation=InteractionRotationManager.getAngles();
        return rotation!=null && InteractionRotationManager.readyForInteraction()
                && Math.abs(MathHelper.wrapDegrees(targetYaw-rotation.yaw()))<=STABLE_AIM_DEGREES
                && Math.abs(targetPitch-rotation.pitch())<=STABLE_AIM_DEGREES;
    }
    private static int effectiveAimSettleTicks() {
        return Math.max(1, Math.min(6, config.aimSettleTicks + placements.latencySettleBonusTicks()));
    }
    private static void equip(MinecraftClient c, int slot) {
        int current=c.player.getInventory().selectedSlot;
        if(slot<0 || slot==current)return;
        if(originalSlot<0)originalSlot=current;
        c.player.getInventory().selectedSlot=slot;autoSlot=slot;
    }
    private static int blockCount(MinecraftClient c, BlockPos target) {
        int count=0;
        for(int i=0;i<9;i++) {
            ItemStack stack=c.player.getInventory().getStack(i);
            if(validStack(c,stack,target))count+=stack.getCount();
        }
        return count;
    }
    private static boolean headroom(MinecraftClient c, boolean ahead) {
        Box actual=c.player.getBoundingBox();
        Box box=new Box(actual.minX,actual.minY,actual.minZ,actual.maxX,actual.minY+1.8,actual.maxZ);
        if(ahead)box=box.stretch(travel.getOffsetX(),0,travel.getOffsetZ());
        return c.world.isSpaceEmpty(c.player,box.stretch(0,1.30,0));
    }
    private static void startVertical(MinecraftClient c) {
        holdMovement=true;sneak=false;waitingFace=false;verticalWalk=1;
        if(vertical==null || verticalBase==null) {sneak=true;return;}
        timing.updateCamera(now());
        var phase=vertical.phase();
        BlockPos lower=verticalBase.offset(travel), upper=verticalBase.up();
        if(phase==VerticalCycle.Phase.BASE) {
            targetYaw=axisYaw;
            targetPitch=(float)(side==0?config.straightPitch:config.diagonalPitch);
            updateAimForPlacement(c,lower,verticalBase,travel,false);
            if(!stairReserved) {
                if(automatic() && blockCount(c,lower)<4) {
                    sneak=true;warn(c,"message.bridgeassist.low_stair_blocks");return;
                }
                if(blockCount(c,lower)<2) {sneak=true;warn(c,"message.bridgeassist.no_blocks");return;}
                if(!headroom(c,true)) {sneak=true;return;}
                stairReserved=true;
            }
            if(full(c,lower) && !waitingPlacement()) {vertical.basePresent();return;}
            if(config.requireConfirmedSupport) {startConfirmedHorizontal(c,verticalBase);return;}
            int slot=findBlockSlot(c,lower);
            if(slot<0) {sneak=true;warn(c,"message.bridgeassist.no_blocks");return;}
            equip(c,slot);
            if(waitingPlacement() || now()<placements.backoffUntil() || !aimReady(c)) {sneak=true;return;}
            if(!canReplace(c,lower) || !c.world.isSpaceEmpty(c.player,c.player.getBoundingBox().offset(
                    travel.getOffsetX()*.12,0,travel.getOffsetZ()*.12))) {sneak=true;return;}
            double distance=edgeDistance(c.player,verticalBase);
            boolean emergency=SpeedbridgeLogic.sneakAtEdge(false,distance,config.sneakDistance);
            boolean early=SpeedbridgeLogic.sneakAtEdge(false,distance,config.sneakDistance+timing.edgeExtra());
            sneak=config.humanize?timing.sneak(now(),early,emergency):emergency;
            holdMovement=false;
            if(distance<=-.26 && !placementFace(c,verticalBase,lower)) {
                sneak=holdMovement=true;waitingFace=true;
            }
            return;
        }
        if(!vertical.flying()) {targetYaw=axisYaw;targetPitch=(float)(activeMode==VerticalCycle.Mode.TOWER?config.towerPitch:config.staircasePitch);}
        if(phase==VerticalCycle.Phase.WALK) {
            if(!full(c,lower)) {sneak=true;vertical=new VerticalCycle(activeMode);stairReserved=false;return;}
            double along=(c.player.getX()-(lower.getX()+.5))*travel.getOffsetX()
                    +(c.player.getZ()-(lower.getZ()+.5))*travel.getOffsetZ();
            verticalWalk=VerticalCycle.walkDirection(along);holdMovement=verticalWalk==0;sneak=true;
            if(!holdMovement && !c.world.isSpaceEmpty(c.player,c.player.getBoundingBox().offset(
                    travel.getOffsetX()*.12*verticalWalk,0,travel.getOffsetZ()*.12*verticalWalk))) {holdMovement=true;return;}
            Box box=c.player.getBoundingBox();
            boolean contained=box.minX>=lower.getX()+.001 && box.maxX<=lower.getX()+.999
                    && box.minZ>=lower.getZ()+.001 && box.maxZ<=lower.getZ()+.999;
            if(holdMovement && c.player.isOnGround() && lower.equals(feetSupport(c))
                    && c.player.getVelocity().horizontalLength()<.03 && contained) {
                verticalBase=lower;bridgeY=lower.getY();vertical.arrived();
            }
            return;
        }
        if(phase==VerticalCycle.Phase.LAND) {
            if(c.player.isOnGround() && upper.equals(feetSupport(c))) {
                verticalBase=upper;bridgeY=upper.getY();vertical.landed();stairReserved=false;
            }
            return;
        }
        updateAimForPlacement(c,upper,null,null,true);
        int slot=findBlockSlot(c,upper);
        if(slot<0) {sneak=true;warn(c,"message.bridgeassist.no_blocks");return;}
        equip(c,slot);
        if(phase==VerticalCycle.Phase.READY) {
            if(!canReplace(c,upper)) {sneak=true;return;}
            if(waitingPlacement() || !placements.idle(now(),config.minPlaceIntervalMs)
                    || timing.remaining(now())>0 || !aimReady(c) || !c.player.isOnGround()
                    || c.player.getVelocity().horizontalLength()>.03 || !headroom(c,false)) {sneak=true;return;}
            if(config.humanize && timing.pause(now(),true,config.randomPauses)) {sneak=true;return;}
            vertical.launch(now(),ticks,jumpRandom);
        } else if(phase==VerticalCycle.Phase.JUMP && c.player.isOnGround()
                && vertical.returnedToGround(ticks) && !waitingPlacement()) {
            vertical.retry();
        }
    }
    private static void endVertical(MinecraftClient c) {
        if(vertical==null || verticalBase==null) return;
        if(vertical.phase()==VerticalCycle.Phase.BASE) {
            if(!stairReserved)return;
            if(config.requireConfirmedSupport ? !horizontalClickAllowed : holdMovement && !waitingFace)return;
            tryPlace(c,verticalBase.offset(travel),verticalBase,travel,false);
        } else if(vertical.phase()==VerticalCycle.Phase.READY || vertical.phase()==VerticalCycle.Phase.JUMP) {
            BlockPos target=verticalBase.up();
            updateAimForPlacement(c,target,null,null,true);
            AimAnalysis analysis=analyzeHit(c,target,null,null,hasPlacementAim(c,target,null,null,true));
            observePlacementAim(c,target,analysis,clickAimStable(c));
            Box box=c.player.getBoundingBox();
            if(vertical.canPlace(now(),box.minY,target.getY()+1,bodyClear(c,target),analysis.hit()!=null))
                tryPlace(c,target,null,null,true);
        }
    }
    private static boolean updateAimForPlacement(MinecraftClient c, BlockPos target, BlockPos support, Direction side, boolean upward) {
        // A vertical jump moves the eye every tick. Keep the pre-jump direction,
        // rather than chasing a new pitch and resetting settle throughout the jump.
        // The actual crosshair, edge margin and body clearance are still rechecked.
        if(upward && vertical!=null && vertical.flying()) {
            prepareInteractionRotation(c);
            return hasPlacementAim(c,target,support,side,upward);
        }
        double preferredPitch = upward ? (activeMode==VerticalCycle.Mode.TOWER?config.towerPitch:config.staircasePitch)
                : (BridgeAssistClient.side==0?config.straightPitch:config.diagonalPitch);
        // One point per target/attempt, also in legacy/manual and vertical READY modes.
        // Re-sampling a point on every tick makes a long settle window unreachable.
        int variant=retryVariant(target);
        AimSolution solution=upward?chooseVerticalAim(c,target,preferredPitch):null;
        if(target.equals(lockedPlacementTarget) && variant==lockedVariant
                && lockedPlacementAim!=null
                && (support==null || support.equals(lockedPlacementAim.support()))
                && (side==null || side==lockedPlacementAim.side())
                && visibleAim(c,lockedPlacementAim)) {
            AimSolution old=lockedPlacementAim;
            Vec3d delta=old.point().subtract(c.player.getEyePos());
            BridgeMath.Aim aim=BridgeMath.lookAt(delta.x,delta.y,delta.z);
            solution=new AimSolution(old.support(),old.side(),old.point(),aim.yaw(),aim.pitch(),old.score());
        }
        if(solution==null) {
            solution = support!=null && side!=null
                    ? chooseFaceAim(c,support,side,axisYaw,preferredPitch,variant)
                    : chooseNeighbourAim(c,target,axisYaw,preferredPitch,variant);
        }
        if(solution!=null) {
            boolean samePoint=target.equals(lockedPlacementTarget) && variant==lockedVariant
                    && lockedPlacementAim!=null && lockedPlacementAim.support().equals(solution.support())
                    && lockedPlacementAim.side()==solution.side()
                    && lockedPlacementAim.point().squaredDistanceTo(solution.point())<1e-10;
            if(!samePoint)placements.resetAim();
            lockedPlacementAim=solution;lockedPlacementTarget=target.toImmutable();lockedVariant=variant;
        }
        if(solution==null) return false;
        targetYaw=solution.yaw();
        targetPitch=solution.pitch();
        prepareInteractionRotation(c);
        return true;
    }
    private static AimSolution chooseVerticalAim(MinecraftClient c,BlockPos target,double preferredPitch) {
        BlockPos support=target.down();
        if(!full(c,support))return null;
        Vec3d eye=c.player.getEyePos();
        double pitch=BridgeMath.safeVerticalPitch(eye.x,eye.z,support.getX(),support.getZ(),axisYaw,preferredPitch,config.faceEdgeMargin);
        double radius=(eye.y-(support.getY()+1))/Math.tan(Math.toRadians(pitch));
        Vec3d point=new Vec3d(eye.x-Math.sin(Math.toRadians(axisYaw))*radius,
                support.getY()+1-1e-6,eye.z+Math.cos(Math.toRadians(axisYaw))*radius);
        AimSolution solution=new AimSolution(support,Direction.UP,point,axisYaw,(float)pitch,0);
        return visibleAim(c,solution)?solution:null;
    }
    private static boolean visibleAim(MinecraftClient c, AimSolution aim) {
        Vec3d eye=c.player.getEyePos();
        if(eye.squaredDistanceTo(aim.point())>Math.pow(c.player.getBlockInteractionRange(),2))return false;
        Vec3d delta=aim.point().subtract(eye);
        BridgeMath.Aim angles=BridgeMath.lookAt(delta.x,delta.y,delta.z);
        // Minecraft's fromPolar uses lookup-table trig: a geometrically visible point
        // does not guarantee that the quantized interaction ray is inside its margin.
        BlockHitResult ray=raycastAngles(c,angles.yaw(),angles.pitch());
        return ray.getType()==HitResult.Type.BLOCK && ray.getBlockPos().equals(aim.support())
                && ray.getSide()==aim.side() && pointInsideMargin(ray,config.faceEdgeMargin);
    }
    private static boolean hasPlacementAim(MinecraftClient c, BlockPos target, BlockPos support, Direction side, boolean upward) {
        double preferredPitch = upward ? (activeMode==VerticalCycle.Mode.TOWER?config.towerPitch:config.staircasePitch)
                : (BridgeAssistClient.side==0?config.straightPitch:config.diagonalPitch);
        return (support!=null && side!=null
                ? chooseFaceAim(c,support,side,axisYaw,preferredPitch,retryVariant(target))
                : chooseNeighbourAim(c,target,axisYaw,preferredPitch,retryVariant(target))) != null;
    }
    private static AimSolution chooseNeighbourAim(MinecraftClient c, BlockPos target, float desiredYaw, double desiredPitch, int variant) {
        AimSolution best=null;
        for(Direction d:Direction.values()) {
            BlockPos support=target.offset(d);
            if(!full(c,support) || interactive(c.world.getBlockState(support).getBlock()))continue;
            AimSolution solution=chooseFaceAim(c,support,d.getOpposite(),desiredYaw,desiredPitch,variant);
            if(solution!=null && (best==null || solution.score()<best.score()))best=solution;
        }
        return best;
    }
    private static AimSolution chooseFaceAim(MinecraftClient c, BlockPos support, Direction face, float desiredYaw, double desiredPitch, int variant) {
        AimSolution varied=chooseFaceAim(c,support,face,desiredYaw,desiredPitch,variant,config.humanize);
        // Cosmetic offsets must not remove a valid placement or spend a retry.
        return varied!=null || !config.humanize ? varied
                : chooseFaceAim(c,support,face,desiredYaw,desiredPitch,variant,false);
    }
    private static AimSolution chooseFaceAim(MinecraftClient c, BlockPos support, Direction face, float desiredYaw, double desiredPitch, int variant, boolean vary) {
        Vec3d eye=c.player.getEyePos();
        double rangeSq=Math.pow(c.player.getBlockInteractionRange(),2);
        double[] primary=offsets(variant, shiftAmount());
        double[] secondary=offsets(0, Math.min(shiftAmount(), Math.max(0.10, 0.5-config.faceEdgeMargin)));
        AimSolution best=null;
        for(int i=0;i<primary.length;i++) for(int j=0;j<secondary.length;j++) {
            // Leave room for float yaw/pitch quantization when re-casting the packet angle.
            double inset=config.faceEdgeMargin+.002;
            double a=FaceAimVariation.coordinate(primary[i],inset,vary,aimVariationSeed,
                    support.asLong(),face.ordinal(),variant,i,j,0);
            double b=FaceAimVariation.coordinate(secondary[j],inset,vary,aimVariationSeed,
                    support.asLong(),face.ordinal(),variant,i,j,1);
            Vec3d point=facePoint(support,face,a,b);
            if(variant>0 && support.offset(face).equals(lastClickTarget) && lastClickPoint!=null) {
                double shift=point.distanceTo(lastClickPoint);
                if(shift<.2-1e-5 || shift>.5+1e-5)continue;
            }
            if(eye.squaredDistanceTo(point)>rangeSq)continue;
            Vec3d delta=point.subtract(eye);
            float yaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
            float pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
            BlockHitResult ray=raycastAngles(c,yaw,pitch);
            if(ray.getType()!=HitResult.Type.BLOCK || !ray.getBlockPos().equals(support) || ray.getSide()!=face)continue;
            if(!pointInsideMargin(ray,config.faceEdgeMargin))continue;
            if(variant>0 && support.offset(face).equals(lastClickTarget) && lastClickPoint!=null) {
                double shift=ray.getPos().distanceTo(lastClickPoint);
                if(shift<.2-1e-5 || shift>.5+1e-5)continue;
            }
            double score=Math.abs(MathHelper.wrapDegrees(yaw-desiredYaw))+2*Math.abs(pitch-desiredPitch)+i*.01+j*.001;
            AimSolution candidate=new AimSolution(support,face,point,yaw,pitch,score);
            if(best==null || candidate.score()<best.score())best=candidate;
        }
        return best;
    }
    private static double shiftAmount() {
        double half=Math.max(0.05,0.5-config.faceEdgeMargin);
        return half<=0.2 ? half : Math.min(0.35,half);
    }
    private static double[] offsets(int variant, double shift) {
        double low=Math.max(config.faceEdgeMargin,0.5-shift), high=Math.min(1-config.faceEdgeMargin,0.5+shift);
        return switch(variant) {
            case 1 -> new double[]{high, low, 0.5};
            case 2 -> new double[]{low, high, 0.5};
            default -> new double[]{0.5, high, low};
        };
    }
    private static Vec3d facePoint(BlockPos block, Direction face, double a, double b) {
        return switch(face.getAxis()) {
            case X -> new Vec3d(block.getX()+0.5+face.getOffsetX()*.499999, block.getY()+a, block.getZ()+b);
            case Y -> new Vec3d(block.getX()+a, block.getY()+0.5+face.getOffsetY()*.499999, block.getZ()+b);
            case Z -> new Vec3d(block.getX()+a, block.getY()+b, block.getZ()+0.5+face.getOffsetZ()*.499999);
        };
    }
    private static boolean pointInsideMargin(BlockHitResult hit, double margin) {
        Vec3d pos=hit.getPos();
        BlockPos block=hit.getBlockPos();
        double x=pos.x-block.getX(), y=pos.y-block.getY(), z=pos.z-block.getZ();
        return switch(hit.getSide().getAxis()) {
            case X -> between(y,margin,1-margin) && between(z,margin,1-margin);
            case Y -> between(x,margin,1-margin) && between(z,margin,1-margin);
            case Z -> between(x,margin,1-margin) && between(y,margin,1-margin);
        };
    }
    private static boolean between(double value,double min,double max) {
        return value>=min-1e-6 && value<=max+1e-6;
    }
    private static boolean bodyClear(MinecraftClient c, BlockPos target) {
        Box b=c.player.getBoundingBox();
        return VerticalCycle.outsideBody(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ,target.getX(),target.getY(),target.getZ());
    }
    private static PlacementTracker.Face face(BlockHitResult hit) {
        return hit==null?null:new PlacementTracker.Face(hit.getBlockPos().asLong(),hit.getSide().ordinal());
    }
    private static AimAnalysis analyzeHit(MinecraftClient c, BlockPos target, BlockPos support, Direction side, boolean hasCandidate) {
        BlockHitResult hit=interactionHit(c);
        Vec3d eye=c.player.getEyePos();
        double nearX=MathHelper.clamp(eye.x,target.getX(),target.getX()+1);
        double nearY=MathHelper.clamp(eye.y,target.getY(),target.getY()+1);
        double nearZ=MathHelper.clamp(eye.z,target.getZ(),target.getZ()+1);
        boolean beyondReach=eye.squaredDistanceTo(new Vec3d(nearX,nearY,nearZ))>Math.pow(c.player.getBlockInteractionRange(),2);
        if(hit==null || hit.getType()!=HitResult.Type.BLOCK)
            return new AimAnalysis(null,null,beyondReach?PlacementTracker.FailureReason.OUT_OF_REACH:PlacementTracker.FailureReason.AIM_MISS);
        boolean geometry=hit.getBlockPos().offset(hit.getSide()).equals(target)
                && (support==null || hit.getBlockPos().equals(support))
                && (side==null || hit.getSide()==side)
                && !interactive(c.world.getBlockState(hit.getBlockPos()).getBlock());
        if(!geometry)
            return new AimAnalysis(null,null,beyondReach?PlacementTracker.FailureReason.OUT_OF_REACH:PlacementTracker.FailureReason.AIM_MISS);
        if(c.player.getEyePos().squaredDistanceTo(hit.getPos())>Math.pow(c.player.getBlockInteractionRange(),2))
            return new AimAnalysis(null,face(hit),PlacementTracker.FailureReason.OUT_OF_REACH);
        if(!pointInsideMargin(hit,config.faceEdgeMargin))
            return new AimAnalysis(null,face(hit),PlacementTracker.FailureReason.AIM_MISS);
        return new AimAnalysis(hit,face(hit),null);
    }
    private static void observePlacementAim(MinecraftClient c, BlockPos target, AimAnalysis analysis, boolean stable) {
        var rotation=InteractionRotationManager.getAngles();
        placements.observeAim(target.asLong(),analysis.hit()==null?null:analysis.face(),
                stable && analysis.hit()!=null && rotation!=null,
                rotation==null?0:rotation.yaw(),rotation==null?0:rotation.pitch(),ticks);
    }
    private static void tryPlace(MinecraftClient c, BlockPos target, BlockPos support, Direction side, boolean upward) {
        if(waitingPlacement() || failureStop)return;
        boolean hasCandidate=updateAimForPlacement(c,target,support,side,upward);
        long now=now();
        MinecraftClientAccessor accessor=(MinecraftClientAccessor)c;
        AimAnalysis analysis=analyzeHit(c,target,support,side,hasCandidate);
        boolean stable=hasCandidate && clickAimStable(c);
        if(confirmedApproach())stable &= c.player.getVelocity().horizontalLength()<.008;
        observePlacementAim(c,target,analysis,stable);
        if(upward && (vertical==null || !vertical.canPlace(now,c.player.getBoundingBox().minY,target.getY()+1,
                bodyClear(c,target),analysis.hit()!=null)))return;
        if(!stable || !placements.idle(now,config.minPlaceIntervalMs) || !bodyClear(c,target)
                || !validStack(c,c.player.getMainHandStack(),target))return;
        if(!canReplace(c,target)) {
            reportFailure(c,target,PlacementTracker.FailureReason.TARGET_OCCUPIED,"occupied before click");
            return;
        }
        if(accessor.bridgeassist$getItemUseCooldown()>0) {
            reportFailure(c,target,PlacementTracker.FailureReason.ITEM_USE_COOLDOWN,
                    "vanilla itemUseCooldown="+accessor.bridgeassist$getItemUseCooldown());
            return;
        }
        if(analysis.failure()!=null) {
            BlockHitResult raw=interactionHit(c);
            reportFailure(c,target,analysis.failure(),"strict interaction ray/face validation failed; rotation="
                    +InteractionRotationManager.getAngles()+" ray="+(raw==null?"none":raw.getBlockPos()+"/"+raw.getSide()+"@"+raw.getPos()));
            return;
        }
        if(!placements.canClick(target.asLong(),analysis.face(),false,ticks,now,config.minPlaceIntervalMs,effectiveAimSettleTicks()))return;
        if(!timing.click(now,target.asLong(),config.humanize))return;
        if(!canReplace(c,target) || !bodyClear(c,target)) {
            reportFailure(c,target,PlacementTracker.FailureReason.TARGET_OCCUPIED,"target changed between settle and click");
            return;
        }
        // Re-cast the real interaction ray immediately before vanilla consumes it.
        AimAnalysis finalHit=analyzeHit(c,target,support,side,hasCandidate);
        if(finalHit.hit()==null || !finalHit.face().equals(analysis.face())) return;
        prepareInteractionRotation(c);
        if(!InteractionRotationManager.readyForInteraction())return;
        lastClickTarget=target.toImmutable();lastClickPoint=finalHit.hit().getPos();
        placements.clicked(target.asLong(),finalHit.face(),ticks,now);
        unconfirmedTarget=target.toImmutable();
        InteractionRotationManager.requestAngles(targetYaw,targetPitch,Math.max(2,effectiveAimSettleTicks()+1));
        var rotation=InteractionRotationManager.getAngles();
        ClientPlayerEntityAccessor playerAngles=(ClientPlayerEntityAccessor)c.player;
        float visualYaw=playerAngles.bridgeassist$getYaw(),visualPitch=playerAngles.bridgeassist$getPitch();
        HitResult visualCrosshair=c.crosshairTarget;
        var visualTargetedEntity=c.targetedEntity;
        try {
            // Synchronous vanilla interaction context only. No rendering or movement tick occurs here.
            playerAngles.bridgeassist$setYaw(rotation.yaw());playerAngles.bridgeassist$setPitch(rotation.pitch());
            c.crosshairTarget=finalHit.hit();c.targetedEntity=null;
            accessor.bridgeassist$doItemUse();
        } finally {
            playerAngles.bridgeassist$setYaw(visualYaw);playerAngles.bridgeassist$setPitch(visualPitch);
            c.crosshairTarget=visualCrosshair;c.targetedEntity=visualTargetedEntity;
        }
        placements.notePrediction(predicted(c,target));
        LOGGER.info("Placement sent target={} face={} point={} yaw={} pitch={} predicted={} timeoutTicks={}",
                target,finalHit.face(),finalHit.hit().getPos(),rotation.yaw(),rotation.pitch(),predicted(c,target),placements.confirmationTimeoutTicks());
        timing.attempted();
    }
    private static int retryVariant(BlockPos target) {
        return target!=null && target.equals(repeatTarget) ? Math.min(repeatAttempts,2) : 0;
    }
    private static void registerTimedOutAttempt(MinecraftClient c, BlockPos target, PlacementTracker.FailureReason reason) {
        if(target.equals(repeatTarget)) repeatAttempts++;
        else {repeatTarget=target.toImmutable();repeatAttempts=1;}
        reportFailure(c,target,reason,"world confirmation timed out after "+placements.confirmationTimeoutTicks()+" ticks");
        if(repeatAttempts>2) {
            failureStop=true;
            auto.pause();
            c.player.sendMessage(Text.translatable("message.bridgeassist.failure_stop"),true);
        }
    }
    private static void reportFailure(MinecraftClient c, BlockPos target, PlacementTracker.FailureReason reason, String detail) {
        if(c.player==null)return;
        if(lastFailureReason==reason && target.equals(lastFailureTarget) && ticks-lastFailureTick<6)return;
        lastFailureReason=reason;lastFailureTarget=target.toImmutable();lastFailureTick=ticks;
        c.player.sendMessage(Text.translatable("message.bridgeassist.place_failed",reasonText(reason)),true);
        LOGGER.warn("Placement failed: {} target=({}, {}, {}) visualYaw={} visualPitch={} interaction={} mode={} repeat={} detail={}",
                reason.name(),target.getX(),target.getY(),target.getZ(),
                String.format(java.util.Locale.ROOT,"%.2f",c.player.getYaw()),
                String.format(java.util.Locale.ROOT,"%.2f",c.player.getPitch()),
                InteractionRotationManager.getAngles(),activeMode,repeatAttempts,detail);
    }
    private static Text reasonText(PlacementTracker.FailureReason reason) {
        return switch(reason) {
            case AIM_MISS -> Text.translatable("reason.bridgeassist.aim_miss");
            case TARGET_OCCUPIED -> Text.translatable("reason.bridgeassist.target_occupied");
            case OUT_OF_REACH -> Text.translatable("reason.bridgeassist.out_of_reach");
            case ITEM_USE_COOLDOWN -> Text.translatable("reason.bridgeassist.item_use_cooldown");
            case SERVER_CANCELLED -> Text.translatable("reason.bridgeassist.server_cancelled");
            case UNCONFIRMED -> Text.translatable("reason.bridgeassist.unconfirmed");
        };
    }

    /** Called immediately before vanilla Mouse consumes its accumulated cursor deltas. */
    public static MouseDelta mouseDelta(double dt, double physicalX, double physicalY) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (!enabled || !running || pausedByMouse || !usable(c)
                || !c.mouse.isCursorLocked() || !wantsMovement(c)) return MouseDelta.ZERO;
        double factor = SpeedbridgeLogic.mouseDegreesPerUnit(c.options.getMouseSensitivity().getValue());
        if (Math.hypot(physicalX, physicalY) * factor >= config.manualOverrideDegrees) {
            if(config.autoBuild) {pauseAuto();warn(c,"message.bridgeassist.auto_paused");}
            else {pausedByMouse=true;sneak=holdMovement=true;warn(c,"message.bridgeassist.manual");}
            yawAxis.reset(); pitchAxis.reset();
            return MouseDelta.ZERO;
        }
        return MouseDelta.ZERO;
    }

    /** Feed actual crouch input. Vanilla handles pose, sneak speed and ledge clipping. */
    public static void applyInput(Input input) {
        MinecraftClient c = MinecraftClient.getInstance();
        if(c.player==null || c.world==null) return;
        if(config.autoBuild && physicalMovement(c))pauseAuto();
        if(enabled && failureStop && usable(c)) {
            input.movementForward=input.movementSideways=0;
            input.playerInput=new PlayerInput(false,false,false,false,false,true,false);
            return;
        }
        if(enabled && running && usable(c) && wantsMovement(c) && guardSupport(c)) {
            input.movementForward=input.movementSideways=0;
            input.playerInput=new PlayerInput(false,false,false,false,false,true,false);return;
        }
        if(enabled && !running && (edgeBrake || now()<brakeUntil)) {
            boolean manual=c.currentScreen==null && (c.options.forwardKey.isPressed()
                    || c.options.backKey.isPressed() || c.options.leftKey.isPressed()
                    || c.options.rightKey.isPressed() || c.options.jumpKey.isPressed());
            if(manual || !c.player.isOnGround() || c.player.getAbilities().flying) {
                edgeBrake=false;brakeUntil=0;
            } else {
                input.movementForward=input.movementSideways=0;
                input.playerInput=new PlayerInput(false,false,false,false,false,true,false);
                return;
            }
        }
        if (!enabled || c.currentScreen != null || !wantsMovement(c)
                || (!running && !pausedByMouse)) return;
        if(confirmedApproach()) {
            BlockPos floor=activeMode==VerticalCycle.Mode.STAIRCASE?verticalBase:findSupport(c);
            if(floor==null) {sneak=holdMovement=true;}
            else {
                BlockPos next=floor.offset(travel);
                double v=c.player.getVelocity().x*travel.getOffsetX()+c.player.getVelocity().z*travel.getOffsetZ();
                horizontal.update(confirmedFull(c,floor),confirmedFull(c,next),waitingPlacement(),
                        predicted(c,next),edgeDistance(c.player,floor),v);
                if(!horizontal.allowMovement())holdMovement=true;
                sneak |= horizontal.crouch();
            }
        }
        PlayerInput p = input.playerInput;
        boolean crouch = p.sneak() || sneak;
        if(vertical!=null && activeMode!=VerticalCycle.Mode.HORIZONTAL) {
            boolean jump=vertical.virtualJump(ticks) && !supportLost && !sneak;
            if(jump)vertical.issued();
            boolean move=!holdMovement && activeMode==VerticalCycle.Mode.STAIRCASE;
            int direction=move?verticalWalk:0;
            input.movementForward=-direction;input.movementSideways=side*direction;
            input.playerInput=new PlayerInput(direction<0,direction>0,side*direction>0,side*direction<0,jump,crouch,false);
        } else if (holdMovement) {
            input.movementForward = input.movementSideways = 0;
            input.playerInput = new PlayerInput(false, false, false, false, false, crouch, false);
        } else if(automatic() || SpeedbridgeLogic.stance(config.stanceMode)!=SpeedbridgeLogic.Stance.FOLLOW_INPUT) {
            input.movementForward=-1;
            input.movementSideways=side;
            input.playerInput=new PlayerInput(false,true,side>0,side<0,false,crouch,false);
        } else {
            input.playerInput = new PlayerInput(p.forward(), p.backward(), p.left(), p.right(),
                    p.jump(), crouch, false);
        }
        if(!holdMovement && activeMode!=VerticalCycle.Mode.TOWER) {
            int direction=activeMode==VerticalCycle.Mode.STAIRCASE?verticalWalk:1;
            BridgeMath.Axes keys=BridgeMath.rotateInput(side*direction,-direction,axisYaw-c.player.getYaw());
            double scale=(confirmedApproach()?horizontal.inputScale():1)/Math.max(1,Math.hypot(keys.sideways(),keys.forward()));
            input.movementForward=(float)(keys.forward()*scale);
            input.movementSideways=(float)(keys.sideways()*scale);
            input.playerInput=new PlayerInput(input.movementForward>1e-5,input.movementForward< -1e-5,
                    input.movementSideways>1e-5,input.movementSideways< -1e-5,false,crouch,false);
        }
    }

    private static boolean placementFace(MinecraftClient c,BlockPos support,BlockPos next) {
        BlockHitResult hit=interactionHit(c);
        return hit!=null && hit.getType()==HitResult.Type.BLOCK
                && hit.getSide()==travel && hit.getBlockPos().equals(support)
                && hit.getBlockPos().offset(hit.getSide()).equals(next)
                && !interactive(c.world.getBlockState(support).getBlock())
                && c.player.getEyePos().squaredDistanceTo(hit.getPos())<=Math.pow(c.player.getBlockInteractionRange(),2)
                && pointInsideMargin(hit,config.faceEdgeMargin);
    }
    private static BlockPos findSupport(MinecraftClient c) {
        Box box = c.player.getBoundingBox();
        BlockPos best = null;
        double score = -Double.MAX_VALUE;
        boolean confirmedOnly = activeMode == VerticalCycle.Mode.HORIZONTAL && config.requireConfirmedSupport;
        for (int x = SpeedbridgeLogic.overlapMin(box.minX); x <= SpeedbridgeLogic.overlapMax(box.maxX); x++)
            for (int z = SpeedbridgeLogic.overlapMin(box.minZ); z <= SpeedbridgeLogic.overlapMax(box.maxZ); z++) {
                BlockPos p = new BlockPos(x, bridgeY, z);
                if (!(confirmedOnly ? confirmedFull(c,p) : full(c,p))) continue;
                double along = x * travel.getOffsetX() + z * travel.getOffsetZ();
                if (along > score) { score = along; best = p; }
            }
        return best;
    }

    private static double edgeDistance(ClientPlayerEntity p, BlockPos support) {
        return switch (travel) {
            case EAST -> support.getX() + 1.0 - p.getX();
            case WEST -> p.getX() - support.getX();
            case SOUTH -> support.getZ() + 1.0 - p.getZ();
            case NORTH -> p.getZ() - support.getZ();
            default -> 0;
        };
    }

    private static boolean usable(MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        return p != null && c.world != null && c.currentScreen == null && !c.isPaused()
                && c.isWindowFocused() && p.isAlive() && !p.isSpectator()
                && !p.getAbilities().flying && !p.hasVehicle() && !p.isGliding()
                && !p.isTouchingWater() && !p.isInLava() && !p.isClimbing();
    }
    private static boolean canReplace(MinecraftClient c, BlockPos pos) {
        BlockState state = c.world.getBlockState(pos);
        return c.world.isInBuildLimit(pos) && c.world.getWorldBorder().contains(pos)
                && state.isReplaceable() && state.getFluidState().isEmpty();
    }
    private static int findBlockSlot(MinecraftClient c, BlockPos pos) {
        int current = c.player.getInventory().selectedSlot;
        if (validStack(c, c.player.getInventory().getStack(current), pos)) return current;
        for (int i = 0; i < 9; i++)
            if (validStack(c, c.player.getInventory().getStack(i), pos)) return i;
        return -1;
    }
    private static boolean validStack(MinecraftClient c, ItemStack stack, BlockPos pos) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) return false;
        Block b = item.getBlock();
        return !(b instanceof FallingBlock) && !(b instanceof TntBlock) && !(b instanceof MagmaBlock)
                && !interactive(b) && b.getDefaultState().isFullCube(c.world, pos);
    }
    private static boolean interactive(Block b) {
        return b instanceof BlockWithEntity || b instanceof CraftingTableBlock
                || b instanceof CartographyTableBlock || b instanceof LoomBlock
                || b instanceof SmithingTableBlock || b instanceof StonecutterBlock
                || b instanceof FletchingTableBlock || b instanceof RespawnAnchorBlock;
    }
    private static void stopRun() {
        boolean active=InteractionRotationManager.isActive();
        InteractionRotationManager.clear();
        if(active)restoreVisualRotation(MinecraftClient.getInstance());
        if(running) {
            MinecraftClient c=MinecraftClient.getInstance();
            if(c.player!=null && c.world!=null && c.player.isOnGround()) {
                brakeUntil=now()+300;
                BlockPos support=findSupport(c);
                if(support!=null) {
                    Box b=c.player.getBoundingBox();
                    edgeBrake=b.minX<support.getX()+.005 || b.maxX>support.getX()+.995
                            || b.minZ<support.getZ()+.005 || b.maxZ>support.getZ()+.995;
                }
            }
        }
        running = false;
        horizontalClickAllowed=false;clearPlacementAim();
        vertical=null;verticalBase=null;stairReserved=false;
        sneak = holdMovement = false;
        yawAxis.reset(); pitchAxis.reset();
    }
    private static void restoreSlot(ClientPlayerEntity p) {
        if (originalSlot >= 0 && p.getInventory().selectedSlot == autoSlot)
            p.getInventory().selectedSlot = originalSlot;
        originalSlot = autoSlot = -1;
    }
    private static void warn(MinecraftClient c, String key) {
        if (ticks - lastWarning < 40) return;
        lastWarning = ticks;
        c.player.sendMessage(Text.translatable(key), true);
    }
}
