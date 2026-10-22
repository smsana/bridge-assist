package dev.bridgeassist.mixin;

import dev.bridgeassist.BridgeAssistClient;
import dev.bridgeassist.InteractionRotationManager;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Shadow private double cursorDeltaX;
    @Shadow private double cursorDeltaY;
    @Inject(method="updateMouse",at=@At("HEAD"))
    private void bridgeassist$addMouseMovement(double timeDelta,CallbackInfo ci) {
        // Observe only physical input; never discard it or add synthetic cursor deltas.
        BridgeAssistClient.mouseDelta(timeDelta,cursorDeltaX,cursorDeltaY);
        if(InteractionRotationManager.isActive())return;
    }
}
