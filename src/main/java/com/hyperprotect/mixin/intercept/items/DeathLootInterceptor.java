package com.hyperprotect.mixin.intercept.items;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.GameMode;
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
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.annotation.Nonnull;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

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
public abstract class DeathLootInterceptor {

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    static {
        System.setProperty("hyperprotect.intercept.death_drop", "true");
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static Object getBridge(int slot) {
        try {
            Object bridge = System.getProperties().get("hyperprotect.bridge");
            if (bridge == null) return null;
            return ((AtomicReferenceArray<Object>) bridge).get(slot);
        } catch (Exception e) {
            return null;
        }
    }

    @Unique
    private static void reportFault(Throwable t) {
        long count = faultCount.incrementAndGet();
        if (count == 1 || count % 100 == 0) {
            System.err.println("[HyperProtect] DeathLootInterceptor error #" + count + ": " + t);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(5);
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluateDeathLoot",
                MethodType.methodType(int.class,
                        UUID.class, String.class, int.class, int.class, int.class));
            cached = new Object[] { impl, primary };
            hookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    /**
     * Redirect store.getComponent(ref, Player.getComponentType()) in onComponentAdded.
     * The original method calls this early and null-checks: if null, returns.
     * Also, the next check is playerComponent.getGameMode() == Creative, which also returns.
     * We return null when the hook denies, causing the original to exit immediately.
     */
    @Redirect(
        method = "onComponentAdded",
        at = @At(value = "INVOKE",
            target = "Lcom/hypixel/hytale/component/Store;getComponent(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentType;)Lcom/hypixel/hytale/component/Component;",
            ordinal = 0)
    )
    private <S, T extends Component<S>> T gateDeathDrops(Store<S> store, Ref<S> ref,
                                     com.hypixel.hytale.component.ComponentType<S, T> componentType,
                                     @Nonnull Ref<EntityStore> entityRef,
                                     @Nonnull DeathComponent component,
                                     @Nonnull Store<EntityStore> entityStore,
                                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // Call the original method
        @SuppressWarnings("unchecked")
        T result = store.getComponent(ref, componentType);

        if (result == null) return null; // Let original handle it

        try {
            Object[] hook = resolveHook();
            if (hook == null) return result; // No hook = allow (drop normally)

            // Get player context
            @SuppressWarnings("unchecked")
            Store<EntityStore> typedStore = (Store<EntityStore>) store;
            @SuppressWarnings("unchecked")
            Ref<EntityStore> typedRef = (Ref<EntityStore>) ref;

            PlayerRef playerRef = typedStore.getComponent(typedRef, PlayerRef.getComponentType());
            if (playerRef == null) return result;

            World world = typedStore.getExternalData().getWorld();
            String worldName = world != null ? world.getName() : null;
            if (worldName == null) return result;

            TransformComponent transform = typedStore.getComponent(typedRef, TransformComponent.getComponentType());
            if (transform == null) return result;

            Vector3d pos = transform.getPosition();
            UUID playerUuid = playerRef.getUuid();

            int verdict = (int) ((MethodHandle) hook[1]).invoke(
                    hook[0], playerUuid, worldName,
                    (int) pos.getX(), (int) pos.getY(), (int) pos.getZ());

            // Verdict 0 = ALLOW (drop normally), anything else = keep inventory
            if (verdict != 0) {
                return null; // Return null to trigger early exit — keep inventory
            }
        } catch (Throwable t) {
            reportFault(t);
            // Fail-open: drop items normally
        }
        return result;
    }
}
