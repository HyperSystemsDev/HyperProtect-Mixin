package com.hyperprotect.mixin.intercept.items;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.vector.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nonnull;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

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
public abstract class WearInterceptor {

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    static {
        System.setProperty("hyperprotect.intercept.durability", "true");
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
            System.err.println("[HyperProtect] WearInterceptor error #" + count + ": " + t);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(6);
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluateWear",
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
     * Overwrite canDecreaseItemStackDurability to add hook check before original logic.
     * Original: returns playerComponent.gameMode != GameMode.Creative
     * With hook: if hook denies, return false (prevent durability loss); otherwise original logic.
     *
     * @author HyperProtect
     * @reason Intercept durability decrease for protection hooks
     */
    @Overwrite
    public boolean canDecreaseItemStackDurability(@Nonnull Ref<EntityStore> ref,
                                                   @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        // Reproduce original logic first
        Player playerComponent = componentAccessor.getComponent(ref, Player.getComponentType());
        assert (playerComponent != null);
        boolean originalResult = playerComponent.getGameMode() != GameMode.Creative;

        // If original would deny already, no need to check hook
        if (!originalResult) return false;

        // Check hook
        try {
            Object[] hook = resolveHook();
            if (hook == null) return true; // No hook = allow (original says yes)

            PlayerRef playerRef = componentAccessor.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) return true;

            World world = ((EntityStore) componentAccessor.getExternalData()).getWorld();
            String worldName = world != null ? world.getName() : null;
            if (worldName == null) return true;

            TransformComponent transform = componentAccessor.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) return true;

            Vector3d pos = transform.getPosition();
            UUID playerUuid = playerRef.getUuid();

            int verdict = (int) ((MethodHandle) hook[1]).invoke(
                    hook[0], playerUuid, worldName,
                    (int) pos.getX(), (int) pos.getY(), (int) pos.getZ());

            // Verdict 0 = ALLOW, anything else = prevent durability loss
            if (verdict != 0) {
                return false;
            }
        } catch (Throwable t) {
            reportFault(t);
            // Fail-open: allow normal durability behavior
        }
        return true;
    }
}
