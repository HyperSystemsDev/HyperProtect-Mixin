package com.hyperprotect.mixin.intercept.entities;

import com.hyperprotect.mixin.bridge.FaultReporter;
import com.hyperprotect.mixin.bridge.HookSlot;
import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.Transform;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Intercepts player respawn position resolution in Player.getRespawnPosition.
 *
 * <p>This is a <b>value hook</b>, not a gate hook. Instead of returning a verdict,
 * it returns override coordinates for the respawn location.
 *
 * <p>Hook contract (respawn slot, index 22):
 * <ul>
 *   <li>Primary: {@code double[] evaluateRespawn(UUID, String, int, int, int)} —
 *       returns {@code double[3]} with [x, y, z] to override respawn location,
 *       or {@code null} to use default respawn logic</li>
 * </ul>
 *
 * <p>Use cases:
 * <ul>
 *   <li>Respawn at faction home when dying in claimed territory</li>
 *   <li>Respawn at zone-defined spawn point</li>
 *   <li>Respawn at nearest ally territory</li>
 * </ul>
 *
 * <p>Fail-open on error (uses default respawn location).
 */
@Mixin(Player.class)
public class RespawnInterceptor {

    @Unique
    private static final FaultReporter FAULTS = new FaultReporter("RespawnInterceptor");

    @Unique
    private static volatile HookSlot cachedSlot;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            double[].class, UUID.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.respawn", "true");
    }

    @Unique
    private static HookSlot resolveSlot() {
        HookSlot cached = cachedSlot;
        Object current = ProtectionBridge.get(ProtectionBridge.respawn);
        if (current == null) {
            cachedSlot = null;
            return null;
        }
        if (cached != null && cached.impl() == current) {
            return cached;
        }
        try {
            cached = ProtectionBridge.resolve(
                    ProtectionBridge.respawn,
                    "evaluateRespawn", EVALUATE_TYPE);
            cachedSlot = cached;
            return cached;
        } catch (Exception e) {
            FAULTS.report("Failed to resolve respawn hook", e);
            return null;
        }
    }

    @Inject(
        method = "getRespawnPosition",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void interceptRespawnPosition(Ref<EntityStore> ref, String worldName,
                                                  ComponentAccessor<EntityStore> componentAccessor,
                                                  CallbackInfoReturnable<CompletableFuture<Transform>> cir) {
        try {
            HookSlot slot = resolveSlot();
            if (slot == null) return;

            // Extract player UUID from the entity ref
            PlayerRef playerRef = componentAccessor.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) return;

            UUID playerUuid = playerRef.getUuid();

            // Get current position for death location context (approximate from entity transform)
            Player player = componentAccessor.getComponent(ref, Player.getComponentType());
            int deathX = 0, deathY = 0, deathZ = 0;
            if (player != null) {
                Transform transform = componentAccessor.getTransform(ref);
                if (transform != null) {
                    deathX = (int) transform.getX();
                    deathY = (int) transform.getY();
                    deathZ = (int) transform.getZ();
                }
            }

            double[] override = (double[]) slot.primary().invoke(
                    slot.impl(), playerUuid, worldName, deathX, deathY, deathZ);

            if (override != null && override.length >= 3) {
                // Create a transform at the override coordinates
                Transform respawnTransform = new Transform(
                        override[0], override[1], override[2]);
                cir.setReturnValue(CompletableFuture.completedFuture(respawnTransform));
            }
            // null = use default respawn logic
        } catch (Throwable t) {
            FAULTS.report(t);
            // Fail-open: use default respawn location
        }
    }
}
