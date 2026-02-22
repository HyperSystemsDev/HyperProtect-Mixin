package com.hyperprotect.mixin.intercept.entities;

import com.hyperprotect.mixin.bridge.FaultReporter;
import com.hyperprotect.mixin.bridge.HookSlot;
import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodType;

/**
 * Intercepts entity loading from saved data via Store.addEntity() with LOAD reason.
 * Only blocks entities being loaded from save data, not newly spawned entities.
 *
 * <p>Hook contract (mob_spawn slot):
 * <pre>
 *   int evaluateCreatureSpawn(String worldName, int x, int y, int z)
 *     Verdict: 0=ALLOW, 2=DENY_SILENT
 * </pre>
 *
 * <p>Only intercepts {@link AddReason#LOAD} — other reasons pass through.
 * Checks that externalData is an EntityStore with a world context before evaluating.
 * Position is passed as 0,0,0.
 */
@Mixin(Store.class)
public class EntityLoadGate {

    @Unique
    private static final FaultReporter FAULTS = new FaultReporter("EntityLoadGate");

    @Unique
    private static volatile HookSlot cachedSlot;

    @Unique
    private static final MethodType SPAWN_EVAL_TYPE = MethodType.methodType(
            int.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.entity_load", "true");
    }

    /**
     * Redirect addEntity(Holder, AddReason) to evaluate the mob_spawn hook for LOAD reason.
     * Only intercepts LOAD — other reasons (SPAWN, COMMAND, etc.) pass through.
     * Returns null to block entity load.
     *
     * <p>NOTE: Raw types on parameters — no generics on Store, Holder, Ref.
     */
    @Redirect(
        method = "addEntity(Lcom/hypixel/hytale/component/Holder;Lcom/hypixel/hytale/component/AddReason;)Lcom/hypixel/hytale/component/Ref;",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/component/Store;addEntity(Lcom/hypixel/hytale/component/Holder;Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/AddReason;)Lcom/hypixel/hytale/component/Ref;"
        ),
        require = 0
    )
    private Ref checkEntityLoad(Store store, Holder holder, Ref storeRef, AddReason reason) {
        // Only intercept LOAD reason
        if (reason != AddReason.LOAD) {
            return store.addEntity(holder, storeRef, reason);
        }

        try {
            int verdict = queryLoadVerdict(store);
            if (verdict != 0) {
                return null; // Block entity load
            }
        } catch (Throwable t) {
            FAULTS.report(t);
            // Fail-open: allow load
        }

        return store.addEntity(holder, storeRef, reason);
    }

    /**
     * Evaluates whether an entity load in the given store's world should be allowed.
     *
     * @return verdict int: 0=ALLOW, non-zero=DENY
     */
    @Unique
    private static int queryLoadVerdict(Store store) throws Throwable {
        HookSlot slot = cachedSlot;

        // Re-resolve if hook changed or not yet cached
        Object current = ProtectionBridge.get(ProtectionBridge.mob_spawn);
        if (slot == null || slot.impl() != current) {
            if (current == null) {
                cachedSlot = null;
                return 0; // No hook = allow
            }
            slot = ProtectionBridge.resolve(
                    ProtectionBridge.mob_spawn,
                    "evaluateCreatureSpawn",
                    SPAWN_EVAL_TYPE);
            cachedSlot = slot;
        }

        // Check if this is an EntityStore with a world context
        Object externalData = store.getExternalData();
        if (!(externalData instanceof EntityStore entityStore)) {
            return 0;
        }

        World world = entityStore.getWorld();
        if (world == null) return 0;

        String worldName = world.getName();

        // Entity load position not available — pass 0,0,0
        int verdict = (int) slot.primary().invoke(slot.impl(), worldName, 0, 0, 0);

        // Fail-open for negative/unknown values
        return verdict < 0 ? 0 : verdict;
    }
}
