package com.hyperprotect.mixin.intercept.items;

import com.hyperprotect.mixin.bridge.FaultReporter;
import com.hyperprotect.mixin.bridge.HookSlot;
import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.vector.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.invoke.MethodType;
import java.util.UUID;

/**
 * Intercepts death item drops in DeathSystems.DropPlayerDeathItems.
 * When the hook denies, the entire death drop process is cancelled (player keeps items).
 *
 * <p>Hook contract (death_drop slot):
 * <pre>
 *   int evaluateDeathLoot(UUID playerUuid, String worldName, int x, int y, int z)
 *     Verdict: 0=ALLOW (drop normally), non-zero=DENY (keep inventory)
 * </pre>
 *
 * <p>No messaging needed — death drops are a background process.
 */
@Mixin(targets = "com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems$DropPlayerDeathItems")
public class DeathLootInterceptor {

    @Unique
    private static final FaultReporter FAULTS = new FaultReporter("DeathLoot");

    @Unique
    private static volatile HookSlot cachedSlot;

    static {
        System.setProperty("hyperprotect.intercept.death_drop", "true");
    }

    /**
     * Inject at the start of onComponentAdded to evaluate whether drops should be kept.
     * If the hook returns a non-zero verdict, cancel to keep inventory.
     */
    @Inject(
        method = "onComponentAdded",
        at = @At("HEAD"),
        cancellable = true
    )
    private void gateDeathDrops(Ref<EntityStore> ref, DeathComponent component,
                                Store<EntityStore> store,
                                CommandBuffer<EntityStore> commandBuffer,
                                CallbackInfo ci) {
        try {
            HookSlot slot = cachedSlot;

            // Re-resolve if hook changed or not yet cached
            Object current = ProtectionBridge.get(ProtectionBridge.death_drop);
            if (slot == null || slot.impl() != current) {
                if (current == null) return; // No hook = allow (drop normally)
                slot = ProtectionBridge.resolve(
                        ProtectionBridge.death_drop,
                        "evaluateDeathLoot",
                        MethodType.methodType(int.class,
                                UUID.class, String.class, int.class, int.class, int.class));
                cachedSlot = slot;
            }

            // Need player context to evaluate
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) return;

            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) return;

            World world = store.getExternalData().getWorld();
            String worldName = world != null ? world.getName() : null;
            if (worldName == null) return;

            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) return;

            Vector3d pos = transform.getPosition();
            UUID playerUuid = playerRef.getUuid();

            int verdict = (int) slot.primary().invoke(
                    slot.impl(), playerUuid, worldName,
                    (int) pos.getX(), (int) pos.getY(), (int) pos.getZ());

            // Verdict 0 = ALLOW (drop normally), anything else = keep inventory
            if (verdict != 0) {
                ci.cancel(); // Keep inventory — skip all death drops
            }
        } catch (Throwable t) {
            FAULTS.report(t);
            // Fail-open: drop items normally
        }
    }
}
