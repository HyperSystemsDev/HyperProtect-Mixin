package com.hyperprotect.mixin.intercept.items;

import com.hyperprotect.mixin.bridge.FaultReporter;
import com.hyperprotect.mixin.bridge.HookSlot;
import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.vector.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodType;
import java.util.UUID;

/**
 * Intercepts durability decrease checks on Player.canDecreaseItemStackDurability().
 * When the hook denies, returns false to prevent item durability loss.
 *
 * <p>Hook contract (durability slot):
 * <pre>
 *   int evaluateWear(UUID playerUuid, String worldName, int x, int y, int z)
 *     Verdict: 0=ALLOW (durability decreases), non-zero=DENY (prevent durability loss)
 * </pre>
 *
 * <p>No messaging needed — durability is a passive mechanic.
 */
@Mixin(Player.class)
public class WearInterceptor {

    @Unique
    private static final FaultReporter FAULTS = new FaultReporter("Wear");

    @Unique
    private static volatile HookSlot cachedSlot;

    static {
        System.setProperty("hyperprotect.intercept.durability", "true");
    }

    /**
     * Inject at the start of canDecreaseItemStackDurability.
     * If the hook denies, return false (cannot decrease = prevent durability loss).
     */
    @Inject(
        method = "canDecreaseItemStackDurability",
        at = @At("HEAD"),
        cancellable = true
    )
    private void gateDurabilityDecrease(Ref<EntityStore> ref,
                                        ComponentAccessor<EntityStore> componentAccessor,
                                        CallbackInfoReturnable<Boolean> cir) {
        try {
            HookSlot slot = cachedSlot;

            // Re-resolve if hook changed or not yet cached
            Object current = ProtectionBridge.get(ProtectionBridge.durability);
            if (slot == null || slot.impl() != current) {
                if (current == null) return; // No hook = allow
                slot = ProtectionBridge.resolve(
                        ProtectionBridge.durability,
                        "evaluateWear",
                        MethodType.methodType(int.class,
                                UUID.class, String.class, int.class, int.class, int.class));
                cachedSlot = slot;
            }

            PlayerRef playerRef = componentAccessor.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) return;

            World world = ((EntityStore) componentAccessor.getExternalData()).getWorld();
            String worldName = world != null ? world.getName() : null;
            if (worldName == null) return;

            TransformComponent transform = componentAccessor.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) return;

            Vector3d pos = transform.getPosition();
            UUID playerUuid = playerRef.getUuid();

            int verdict = (int) slot.primary().invoke(
                    slot.impl(), playerUuid, worldName,
                    (int) pos.getX(), (int) pos.getY(), (int) pos.getZ());

            // Verdict 0 = ALLOW, anything else = prevent durability loss
            if (verdict != 0) {
                cir.setReturnValue(false); // Cannot decrease = prevent durability loss
            }
        } catch (Throwable t) {
            FAULTS.report(t);
            // Fail-open: allow normal durability behavior
        }
    }
}
