package com.hyperprotect.mixin.intercept.entities;

import com.hyperprotect.mixin.bridge.FaultReporter;
import com.hyperprotect.mixin.bridge.HookSlot;
import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.server.spawning.SpawnTestResult;
import com.hypixel.hytale.server.spawning.SpawningContext;
import com.hypixel.hytale.server.spawning.world.system.WorldSpawnJobSystems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodType;

/**
 * Intercepts world chunk spawning in WorldSpawnJobSystems.trySpawn().
 * Redirects canSpawn() calls on SpawningContext to check the mob_spawn hook.
 *
 * <p>Hook contract (mob_spawn slot):
 * <pre>
 *   int evaluateCreatureSpawn(String worldName, int x, int y, int z)
 *     Verdict: 0=ALLOW, 2=DENY_SILENT
 * </pre>
 *
 * <p>Spawn startup behavior:
 * <ul>
 *   <li>If no hook AND not initialized AND startup pass disabled: block spawns</li>
 *   <li>If no hook AND initialized (or startup pass enabled): allow spawns</li>
 * </ul>
 */
@Mixin(WorldSpawnJobSystems.class)
public class ChunkSpawnGate {

    @Unique
    private static final FaultReporter FAULTS = new FaultReporter("ChunkSpawnGate");

    @Unique
    private static volatile HookSlot cachedSlot;

    @Unique
    private static final MethodType SPAWN_EVAL_TYPE = MethodType.methodType(
            int.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.world_spawn", "true");
    }

    /**
     * Redirect canSpawn() in trySpawn to evaluate the mob_spawn hook.
     * If spawning should be blocked, returns FAIL_NOT_SPAWNABLE.
     */
    @Redirect(
        method = "trySpawn",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/spawning/SpawningContext;canSpawn()Lcom/hypixel/hytale/server/spawning/SpawnTestResult;"
        ),
        require = 0
    )
    private static SpawnTestResult gateCreatureSpawn(SpawningContext context) {
        SpawnTestResult original = context.canSpawn();

        // Only intercept if the server would allow the spawn
        if (original != SpawnTestResult.TEST_OK) {
            return original;
        }

        try {
            int verdict = querySpawnVerdict(context);
            if (verdict != 0) {
                return SpawnTestResult.FAIL_NOT_SPAWNABLE;
            }
        } catch (Throwable t) {
            FAULTS.report(t);
            // Fail-open: allow spawn
        }

        return original;
    }

    /**
     * Evaluates whether a creature spawn at the given context should be allowed.
     *
     * @return verdict int: 0=ALLOW, non-zero=DENY
     */
    @Unique
    private static int querySpawnVerdict(SpawningContext context) throws Throwable {
        HookSlot slot = cachedSlot;

        // Re-resolve if hook changed or not yet cached
        Object current = ProtectionBridge.get(ProtectionBridge.mob_spawn);
        if (slot == null || slot.impl() != current) {
            if (current == null) {
                cachedSlot = null;
                // No hook: check startup behavior
                if (!isSpawnInitialized() && !isStartupPassEnabled()) {
                    return 2; // DENY_SILENT — block spawns until ready
                }
                return 0;
            }
            slot = ProtectionBridge.resolve(
                    ProtectionBridge.mob_spawn,
                    "evaluateCreatureSpawn",
                    SPAWN_EVAL_TYPE);
            cachedSlot = slot;
        }

        String worldName = context.world != null ? context.world.getName() : null;
        if (worldName == null) return 0;

        int x = (int) context.xSpawn;
        int y = (int) context.ySpawn;
        int z = (int) context.zSpawn;

        int verdict = (int) slot.primary().invoke(slot.impl(), worldName, x, y, z);

        // Fail-open for negative/unknown values
        return verdict < 0 ? 0 : verdict;
    }

    @Unique
    private static boolean isSpawnInitialized() {
        return ProtectionBridge.flag(ProtectionBridge.spawn_ready);
    }

    @Unique
    private static boolean isStartupPassEnabled() {
        return ProtectionBridge.flag(ProtectionBridge.spawn_allow_startup);
    }
}
