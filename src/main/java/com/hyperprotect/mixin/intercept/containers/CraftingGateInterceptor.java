package com.hyperprotect.mixin.intercept.containers;

import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Intercepts crafting in CraftingManager.craftItem() to check container access.
 * Uses bench position from the CraftingManager shadow fields (x, y, z).
 *
 * <p>Uses {@code @Redirect} instead of {@code @Inject} to avoid runtime dependency
 * on {@code CallbackInfo} (not on TransformingClassLoader classpath).
 *
 * <p>Hook contract (container_access slot, index 7):
 * <ul>
 *   <li>Primary: {@code int evaluateCrafting(UUID, String, int, int, int)} — returns verdict</li>
 *   <li>Secondary: {@code String fetchCraftingDenyReason(UUID, String, int, int, int)} — returns deny reason text</li>
 * </ul>
 *
 * <p>Verdict protocol: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES.
 * Fail-open on error.
 */
@Mixin(CraftingManager.class)
public abstract class CraftingGateInterceptor {

    @Shadow
    private int x;

    @Shadow
    private int y;

    @Shadow
    private int z;

    @Shadow
    private boolean isValidBenchForRecipe(Ref<EntityStore> ref,
                                           ComponentAccessor<EntityStore> componentAccessor,
                                           CraftingRecipe recipe) {
        throw new AssertionError();
    }

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final MethodType FETCH_REASON_TYPE = MethodType.methodType(
            String.class, UUID.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.container_access", "true");
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
            System.err.println("[HyperProtect] CraftingGateInterceptor error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(7);
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "evaluateCrafting", EVALUATE_TYPE);
            MethodHandle secondary = null;
            try {
                secondary = MethodHandles.publicLookup().findVirtual(
                    impl.getClass(), "fetchCraftingDenyReason", FETCH_REASON_TYPE);
            } catch (NoSuchMethodException ignored) {}
            cached = new Object[] { impl, primary, secondary };
            hookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    @Unique
    private static void formatReason(Object[] hook, Player player,
                                     UUID playerUuid, String worldName,
                                     int x, int y, int z) {
        try {
            if (hook.length < 3 || hook[2] == null) return;
            String raw = (String) ((MethodHandle) hook[2]).invoke(hook[0],
                    playerUuid, worldName, x, y, z);
            if (raw == null || raw.isEmpty()) return;
            Object fmtHandle = getBridge(15);
            if (fmtHandle instanceof MethodHandle mh) {
                Message msg = (Message) mh.invoke(raw);
                player.sendMessage(msg);
            }
        } catch (Throwable t) {
            reportFault(t);
        }
    }

    /**
     * Redirects the isValidBenchForRecipe() call inside craftItem().
     * If the bench is invalid, returns false (original behavior).
     * If the bench is valid, checks the protection hook before allowing crafting.
     */
    @Redirect(
        method = "craftItem",
        at = @At(value = "INVOKE",
            target = "Lcom/hypixel/hytale/builtin/crafting/component/CraftingManager;isValidBenchForRecipe(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentAccessor;Lcom/hypixel/hytale/server/core/asset/type/item/config/CraftingRecipe;)Z")
    )
    private boolean gateCraftingAccess(CraftingManager self,
                                       Ref<EntityStore> ref,
                                       ComponentAccessor<EntityStore> componentAccessor,
                                       CraftingRecipe recipe) {
        // Call original bench validation first
        boolean valid = this.isValidBenchForRecipe(ref, componentAccessor, recipe);
        if (!valid) return false;

        // Check protection hook
        try {
            Object[] hook = resolveHook();
            if (hook == null) return true; // No hook = allow

            PlayerRef playerRef = componentAccessor.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) return true;

            String worldName = null;
            try {
                worldName = ((EntityStore) componentAccessor.getExternalData())
                        .getWorld().getName();
            } catch (Exception ignored) {}
            if (worldName == null) return true;

            UUID playerUuid = playerRef.getUuid();

            int verdict = (int) ((MethodHandle) hook[1]).invoke(
                    hook[0], playerUuid, worldName,
                    this.x, this.y, this.z);

            if (verdict >= 1 && verdict <= 3) {
                if (verdict == 1) {
                    Player player = componentAccessor.getComponent(ref, Player.getComponentType());
                    if (player != null) {
                        formatReason(hook, player, playerUuid, worldName,
                                this.x, this.y, this.z);
                    }
                }
                return false; // Deny crafting
            }
        } catch (Throwable t) {
            reportFault(t);
            // Fail-open: allow crafting
        }
        return true;
    }
}
