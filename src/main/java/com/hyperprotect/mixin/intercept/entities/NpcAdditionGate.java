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
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodType;

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
    private static final FaultReporter FAULTS = new FaultReporter("NpcAdditionGate");

    @Unique
    private static volatile HookSlot cachedSlot;

    @Unique
    private static final MethodType SPAWN_EVAL_TYPE = MethodType.methodType(
            int.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.npc_spawn", "true");
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
            FAULTS.report(t);
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

        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null) return 0;

        String worldName = world.getName();

        // NPC spawn position not available at this point — pass 0,0,0
        int verdict = (int) slot.primary().invoke(slot.impl(), worldName, 0, 0, 0);

        // Fail-open for negative/unknown values
        return verdict < 0 ? 0 : verdict;
    }
}
