package com.hyperprotect.mixin.intercept.entities;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Intercepts NPC entity addition via Store.addEntity() within NPCPlugin.
 * Checks the mob_spawn hook before allowing NPC entities to be added.
 *
 * <p>Hook contract (mob_spawn slot):
 * <pre>
 *   int evaluateCreatureSpawn(String worldName, int x, int y, int z)
 *     Verdict: 0=ALLOW, 2=DENY_SILENT
 * </pre>
 *
 * <p>Only intercepts {@link AddReason#SPAWN} — LOAD and other reasons pass through.
 * Position is passed as 0,0,0 since NPC spawn position is not available at this point.
 */
@Mixin(NPCPlugin.class)
public class NpcAdditionGate {

    @Unique
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    @Unique
    private static final MethodType SPAWN_EVAL_TYPE = MethodType.methodType(
            int.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.npc_spawn", "true");
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
            System.err.println("[HyperProtect] NpcAdditionGate error #" + count + ": " + t);
        }
    }

    /**
     * Redirect addEntity calls to evaluate the mob_spawn hook.
     * Returns null (spawn failed) if the hook blocks the spawn.
     */
    @Redirect(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/component/Store;addEntity(Lcom/hypixel/hytale/component/Holder;Lcom/hypixel/hytale/component/AddReason;)Lcom/hypixel/hytale/component/Ref;"
        ),
        require = 0
    )
    private Ref<EntityStore> gateCreatureSpawn(Store<EntityStore> store, Holder<EntityStore> holder,
                                               AddReason reason) {
        // Only intercept SPAWN reason, not LOAD
        if (reason != AddReason.SPAWN) {
            return store.addEntity(holder, reason);
        }

        try {
            int verdict = querySpawnVerdict(store);
            if (verdict != 0) {
                return null; // Block spawn
            }
        } catch (Throwable t) {
            reportFault(t);
            // Fail-open: allow spawn
        }

        return store.addEntity(holder, reason);
    }

    /**
     * Evaluates whether an NPC spawn in the given store's world should be allowed.
     *
     * @return verdict int: 0=ALLOW, non-zero=DENY
     */
    @Unique
    private static int querySpawnVerdict(Store<EntityStore> store) throws Throwable {
        Object[] cached = hookCache;

        // Re-resolve if hook changed or not yet cached
        Object current = getBridge(8);
        if (cached == null || cached[0] != current) {
            if (current == null) {
                hookCache = null;
                return 0; // No hook = allow
            }
            try {
                MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                    current.getClass(), "evaluateCreatureSpawn", SPAWN_EVAL_TYPE);
                cached = new Object[] { current, primary };
                hookCache = cached;
            } catch (Exception e) {
                reportFault(e);
                return 0;
            }
        }

        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null) return 0;

        String worldName = world.getName();

        // NPC spawn position not available at this point — pass 0,0,0
        int verdict = (int) ((MethodHandle) cached[1]).invoke(cached[0], worldName, 0, 0, 0);

        // Fail-open for negative/unknown values
        return verdict < 0 ? 0 : verdict;
    }
}
