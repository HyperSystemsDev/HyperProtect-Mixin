package com.hyperprotect.mixin.intercept.items;

import com.hyperprotect.mixin.bridge.FaultReporter;
import com.hyperprotect.mixin.bridge.HookSlot;
import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialStructure;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerItemEntityPickupSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodType;
import java.util.UUID;

/**
 * Intercepts automatic item pickup in {@code PlayerItemEntityPickupSystem.tick()}.
 *
 * <p>Uses ProtectionBridge + HookSlot for cross-classloader hook resolution.
 * No internal MethodHandle caching -- HookSlot resolves eagerly.
 *
 * <p>Hook contract (item_pickup slot):
 * <ul>
 *   <li>Primary: {@code int evaluate(UUID, String, double, double, double)} -- returns verdict</li>
 * </ul>
 *
 * <p>Verdict protocol: 0=ALLOW, 2=DENY (always silent for automatic pickup, no messaging).
 * Negative/unknown values = ALLOW (fail-open).
 */
@Mixin(PlayerItemEntityPickupSystem.class)
public abstract class ProximityLootInterceptor {

    // --- Verdict constants ---

    @Unique private static final int ALLOW = 0;

    // --- FaultReporter ---

    @Unique
    private static final FaultReporter FAULTS = new FaultReporter("ProximityLootInterceptor");

    // --- ThreadLocal state for capturing data across injection points ---

    @Unique
    private static final ThreadLocal<Vector3d> lootPosition = new ThreadLocal<>();

    @Unique
    private static final ThreadLocal<Store<EntityStore>> activeStore = new ThreadLocal<>();

    // --- Cached HookSlot (volatile for cross-thread visibility) ---

    @Unique
    private static volatile HookSlot cachedSlot;

    // --- MethodType for hook resolution ---

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, double.class, double.class, double.class);

    static {
        System.setProperty("hyperprotect.intercept.item_pickup", "true");
    }

    // --- Hook resolution ---

    @Unique
    private static HookSlot resolveSlot() {
        HookSlot cached = cachedSlot;
        Object current = ProtectionBridge.get(ProtectionBridge.item_pickup);
        if (current == null) {
            cachedSlot = null;
            return null;
        }
        if (cached != null && cached.impl() == current) {
            return cached;
        }
        try {
            cached = ProtectionBridge.resolve(
                    ProtectionBridge.item_pickup,
                    "evaluate", EVALUATE_TYPE);
            cachedSlot = cached;
            return cached;
        } catch (Exception e) {
            FAULTS.report("Failed to resolve item_pickup hook", e);
            return null;
        }
    }

    // --- Injection points ---

    /**
     * Capture the store reference for later use.
     */
    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/component/Store;getResource(Lcom/hypixel/hytale/component/ResourceType;)Lcom/hypixel/hytale/component/Resource;")
    )
    private <R extends Resource<EntityStore>> R captureStore(Store<EntityStore> store, ResourceType<EntityStore, R> resourceType) {
        activeStore.set(store);
        return store.getResource(resourceType);
    }

    /**
     * Capture the item entity's position.
     */
    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/modules/entity/component/TransformComponent;getPosition()Lcom/hypixel/hytale/math/vector/Vector3d;",
            ordinal = 0
        )
    )
    private Vector3d captureLootPosition(TransformComponent transformComponent) {
        Vector3d position = transformComponent.getPosition();
        lootPosition.set(position);
        return position;
    }

    /**
     * Evaluate pickup permission when finding the closest player.
     * Returns null to cancel the pickup if denied.
     * No bypass checks -- the hook implementation decides.
     */
    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/component/spatial/SpatialStructure;closest(Lcom/hypixel/hytale/math/vector/Vector3d;)Ljava/lang/Object;")
    )
    private Object gateAction(SpatialStructure<?> spatialStructure, Vector3d position) {
        Object result = spatialStructure.closest(position);
        if (result == null) {
            return null;
        }

        Vector3d itemPos = lootPosition.get();
        if (itemPos == null) {
            return result;
        }

        try {
            Store<EntityStore> store = activeStore.get();
            if (store == null) {
                return result;
            }

            @SuppressWarnings("unchecked")
            Ref<EntityStore> targetRef = (Ref<EntityStore>) result;
            PlayerRef playerRef = (PlayerRef) store.getComponent(targetRef, PlayerRef.getComponentType());
            if (playerRef == null) {
                return result;
            }

            String worldName = null;
            if (store.getExternalData() != null && ((EntityStore) store.getExternalData()).getWorld() != null) {
                worldName = ((EntityStore) store.getExternalData()).getWorld().getName();
            }
            if (worldName == null) {
                return result;
            }

            HookSlot slot = resolveSlot();
            if (slot == null) {
                return result; // No hook = allow (fail-open)
            }

            int verdict = (int) slot.primary().invoke(
                    slot.impl(), playerRef.getUuid(), worldName,
                    itemPos.getX(), itemPos.getY(), itemPos.getZ());

            // Any non-zero positive verdict = deny (silent, no messaging for auto pickup)
            if (verdict > ALLOW) {
                return null; // Cancel pickup
            }
        } catch (Throwable e) {
            FAULTS.report(e);
            // Fail-open: allow pickup on error
        }

        return result;
    }
}
