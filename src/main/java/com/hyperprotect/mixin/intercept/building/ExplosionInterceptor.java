package com.hyperprotect.mixin.intercept.building;

import com.hyperprotect.mixin.bridge.FaultReporter;
import com.hyperprotect.mixin.bridge.HookSlot;
import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemTool;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.invoke.MethodType;

/**
 * Intercepts explosion block damage in BlockHarvestUtils.performBlockDamage().
 * When the damage source has no entity (entity == null AND ref == null), it's an explosion.
 *
 * <p>Hook contract (explosion slot):
 * <pre>
 *   int evaluateExplosion(World world, int x, int y, int z)
 *     Verdict: 0=ALLOW, 2=DENY_SILENT
 * </pre>
 *
 * <p>No player context — explosions are environmental. No messaging needed.
 */
@Mixin(BlockHarvestUtils.class)
public class ExplosionInterceptor {

    @Unique
    private static final FaultReporter FAULTS = new FaultReporter("ExplosionInterceptor");

    @Unique
    private static volatile HookSlot cachedSlot;

    static {
        System.setProperty("hyperprotect.intercept.explosion", "true");
    }

    @Redirect(
        method = "performBlockDamage(Lcom/hypixel/hytale/math/vector/Vector3i;Lcom/hypixel/hytale/server/core/inventory/ItemStack;Lcom/hypixel/hytale/server/core/asset/type/item/config/ItemTool;FILcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/CommandBuffer;Lcom/hypixel/hytale/component/ComponentAccessor;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/modules/interaction/BlockHarvestUtils;performBlockDamage(Lcom/hypixel/hytale/server/core/entity/LivingEntity;Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/math/vector/Vector3i;Lcom/hypixel/hytale/server/core/inventory/ItemStack;Lcom/hypixel/hytale/server/core/asset/type/item/config/ItemTool;Ljava/lang/String;ZFILcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentAccessor;Lcom/hypixel/hytale/component/ComponentAccessor;)Z"
        )
    )
    private static boolean redirectExplosionBlockDamage(
            @Nullable LivingEntity entity,
            @Nullable Ref<EntityStore> ref,
            @Nonnull Vector3i targetBlockPos,
            @Nullable ItemStack itemStack,
            @Nullable ItemTool tool,
            @Nullable String toolId,
            boolean matchTool,
            float damageScale,
            int setBlockSettings,
            @Nonnull Ref<ChunkStore> chunkReference,
            @Nonnull ComponentAccessor<EntityStore> entityStore,
            @Nonnull ComponentAccessor<ChunkStore> chunkStore) {

        // Only intercept explosions (no entity source)
        if (entity == null && ref == null) {
            try {
                int verdict = queryExplosionVerdict(entityStore, targetBlockPos);
                if (verdict != 0) {
                    return false; // Block the explosion damage
                }
            } catch (Throwable t) {
                FAULTS.report(t);
                // Fail-open: allow explosion
            }
        }

        return BlockHarvestUtils.performBlockDamage(
                entity, ref, targetBlockPos, itemStack, tool, toolId,
                matchTool, damageScale, setBlockSettings, chunkReference,
                entityStore, chunkStore);
    }

    /**
     * Evaluates whether an explosion at the given block position should be allowed.
     *
     * @return verdict int: 0=ALLOW, non-zero=DENY
     */
    @Unique
    private static int queryExplosionVerdict(ComponentAccessor<EntityStore> entityStore,
                                             Vector3i targetBlockPos) throws Throwable {
        HookSlot slot = cachedSlot;

        // Re-resolve if hook changed or not yet cached
        Object current = ProtectionBridge.get(ProtectionBridge.explosion);
        if (slot == null || slot.impl() != current) {
            if (current == null) return 0; // No hook = allow
            slot = ProtectionBridge.resolve(
                    ProtectionBridge.explosion,
                    "evaluateExplosion",
                    MethodType.methodType(int.class, World.class, int.class, int.class, int.class));
            cachedSlot = slot;
        }

        World world = ((EntityStore) entityStore.getExternalData()).getWorld();
        if (world == null) return 0;

        int verdict = (int) slot.primary().invoke(
                slot.impl(), world,
                targetBlockPos.getX(), targetBlockPos.getY(), targetBlockPos.getZ());

        // Fail-open for negative/unknown values
        return verdict < 0 ? 0 : verdict;
    }
}
