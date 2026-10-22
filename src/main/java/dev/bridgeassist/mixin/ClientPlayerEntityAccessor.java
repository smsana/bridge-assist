package dev.bridgeassist.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** yaw/pitch are declared by Entity in 1.21.4, not redeclared by ClientPlayerEntity. */
@Mixin(Entity.class)
public interface ClientPlayerEntityAccessor {
    @Accessor("yaw") float bridgeassist$getYaw();
    @Accessor("yaw") void bridgeassist$setYaw(float yaw);
    @Accessor("pitch") float bridgeassist$getPitch();
    @Accessor("pitch") void bridgeassist$setPitch(float pitch);
}
