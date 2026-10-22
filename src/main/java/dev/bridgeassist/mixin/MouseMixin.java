package dev.bridgeassist.mixin;

import dev.bridgeassist.BridgeAssistClient;
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
    @Inject(method = "updateMouse", at = @At("HEAD"))
    private void bridgeassist$addMouseMovement(double timeDelta, CallbackInfo ci) {
        BridgeAssistClient.MouseDelta delta = BridgeAssistClient.mouseDelta(timeDelta, cursorDeltaX, cursorDeltaY);
        cursorDeltaX += delta.x();
        cursorDeltaY += delta.y();
    }
}
