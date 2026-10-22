package dev.bridgeassist.mixin;

import dev.bridgeassist.InteractionRotationManager;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** sendPacket is inherited from ClientCommonNetworkHandler in Yarn 1.21.4. */
@Mixin(ClientCommonNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @ModifyVariable(method="sendPacket",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private Packet<?> bridgeassist$interactionRotation(Packet<?> packet) {
        return (Object)this instanceof ClientPlayNetworkHandler ? InteractionRotationManager.rewrite(packet) : packet;
    }
    @Inject(method="sendPacket",at=@At("RETURN"))
    private void bridgeassist$rotationSent(Packet<?> packet,CallbackInfo ci) {
        if((Object)this instanceof ClientPlayNetworkHandler)InteractionRotationManager.onPacketSent(packet);
    }
}
