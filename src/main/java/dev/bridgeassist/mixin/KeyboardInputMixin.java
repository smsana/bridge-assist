package dev.bridgeassist.mixin;

import dev.bridgeassist.BridgeAssistClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void bridgeassist$ninjaInput(CallbackInfo ci) {
        BridgeAssistClient.applyInput((Input) (Object) this);
    }
}
