package com.hyperprotect.mixin.intercept.building;

import com.hyperprotect.mixin.bridge.FaultReporter;
import com.hyperprotect.mixin.bridge.HookSlot;
import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.modules.interaction.BlockInteractionUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.UUID;

/**
 * Intercepts block harvesting (break + interactive pickup) in BlockHarvestUtils.
 *
 * Uses a consolidated ThreadLocal {@link HarvestContext} record to track the current
 * player, target block, and verdict across the five injection points within
 * {@code performPickupByInteraction()}.
 *
 * <p>Hook contract (block_break slot):
 * <ul>
 *   <li>Primary: {@code int evaluate(UUID, String, int, int, int)} — returns verdict</li>
 *   <li>Secondary: {@code String fetchDenyReason(UUID, String, int, int, int)} — returns deny reason text</li>
 * </ul>
 *
 * <p>Hook contract (item_pickup slot, for interactive pickup):
 * <ul>
 *   <li>Primary: {@code int evaluatePickup(UUID, String, int, int, int)} — returns verdict</li>
 *   <li>Secondary: {@code String fetchPickupDenyReason(UUID, String, int, int, int)} — returns deny reason text</li>
 * </ul>
 *
 * <p>Verdict protocol: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES, negative/unknown=ALLOW
 */
@Mixin(BlockHarvestUtils.class)
public abstract class HarvestInterceptor {

    // --- Verdict constants ---

    @Unique private static final int ALLOW            = 0;
    @Unique private static final int DENY_WITH_MESSAGE = 1;
    @Unique private static final int DENY_SILENT       = 2;
    @Unique private static final int DENY_MOD_HANDLES  = 3;

    // --- Consolidated ThreadLocal context ---

    @Unique
    private record HarvestContext(int verdict, String reason, Player actor, Vector3i target) {
        static final HarvestContext EMPTY = new HarvestContext(ALLOW, null, null, null);

        HarvestContext withVerdict(int verdict) {
            return new HarvestContext(verdict, this.reason, this.actor, this.target);
        }
        HarvestContext withReason(String reason) {
            return new HarvestContext(this.verdict, reason, this.actor, this.target);
        }
        HarvestContext withActor(Player actor) {
            return new HarvestContext(this.verdict, this.reason, actor, this.target);
        }
        HarvestContext withTarget(Vector3i target) {
            return new HarvestContext(this.verdict, this.reason, this.actor, target);
        }
    }

    @Unique
    private static final ThreadLocal<HarvestContext> context = new ThreadLocal<>();

    // --- FaultReporter ---

    @Unique
    private static final FaultReporter FAULTS = new FaultReporter("HarvestInterceptor");

    // --- Cached HookSlots (volatile for cross-thread visibility) ---

    @Unique private static volatile HookSlot breakSlot;
    @Unique private static volatile HookSlot pickupSlot;

    // --- MethodType constants for hook resolution ---

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final MethodType FETCH_REASON_TYPE = MethodType.methodType(
            String.class, UUID.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.block_break", "true");
    }

    @Shadow
    protected static void removeBlock(Vector3i blockPosition, BlockType blockType,
                                      int setBlockSettings, Ref<ChunkStore> chunkReference,
                                      ComponentAccessor<ChunkStore> chunkStore) {
        throw new UnsupportedOperationException("Shadow stub");
    }

    // --- Hook resolution helpers ---

    @Unique
    private static HookSlot resolveBreakSlot() {
        HookSlot cached = breakSlot;
        Object current = ProtectionBridge.get(ProtectionBridge.block_break);
        if (current == null) {
            breakSlot = null;
            return null;
        }
        if (cached != null && cached.impl() == current) {
            return cached;
        }
        try {
            cached = ProtectionBridge.resolve(
                    ProtectionBridge.block_break,
                    "evaluate", EVALUATE_TYPE,
                    "fetchDenyReason", FETCH_REASON_TYPE);
            breakSlot = cached;
            return cached;
        } catch (Exception e) {
            FAULTS.report("Failed to resolve block_break hook", e);
            return null;
        }
    }

    @Unique
    private static HookSlot resolvePickupSlot() {
        HookSlot cached = pickupSlot;
        Object current = ProtectionBridge.get(ProtectionBridge.item_pickup);
        if (current == null) {
            pickupSlot = null;
            return null;
        }
        if (cached != null && cached.impl() == current) {
            return cached;
        }
        try {
            cached = ProtectionBridge.resolve(
                    ProtectionBridge.item_pickup,
                    "evaluatePickup", EVALUATE_TYPE,
                    "fetchPickupDenyReason", FETCH_REASON_TYPE);
            pickupSlot = cached;
            return cached;
        } catch (Exception e) {
            FAULTS.report("Failed to resolve item_pickup hook for interactive pickup", e);
            return null;
        }
    }

    // --- Message formatting via bridge handle ---

    @Unique
    private static Message formatReason(String reason) {
        if (reason == null || reason.isEmpty()) return null;
        Object handle = ProtectionBridge.get(ProtectionBridge.format_handle);
        if (handle instanceof MethodHandle mh) {
            try { return (Message) mh.invoke(reason); } catch (Throwable ignored) {}
        }
        return Message.raw(reason);
    }

    // --- Injection points ---

    /**
     * Stage 1: Reset context at the start of performPickupByInteraction.
     */
    @Redirect(
        method = "performPickupByInteraction",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/server/core/asset/type/blocktype/config/BlockType;isUnknown()Z")
    )
    private static boolean resetContext(BlockType blockType) {
        context.set(HarvestContext.EMPTY);
        return blockType.isUnknown();
    }

    /**
     * Stage 2: Capture the target block coordinates.
     */
    @Redirect(
        method = "performPickupByInteraction",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/server/core/universe/world/chunk/section/BlockSection;getRotationIndex(III)I")
    )
    private static int snapshotTarget(BlockSection section, int x, int y, int z) {
        HarvestContext ctx = context.get();
        if (ctx != null) {
            context.set(ctx.withTarget(new Vector3i(x, y, z)));
        }
        return section.getRotationIndex(x, y, z);
    }

    /**
     * Stage 3: Evaluate block break permission before the action proceeds.
     * No bypass checks -- the hook implementation decides.
     */
    @Redirect(
        method = "performPickupByInteraction",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/server/core/modules/interaction/BlockInteractionUtils;isNaturalAction(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentAccessor;)Z")
    )
    private static boolean gateAction(Ref<EntityStore> ref, ComponentAccessor<EntityStore> entityStore) {
        try {
            Player player = (Player) entityStore.getComponent(ref, Player.getComponentType());
            HarvestContext ctx = context.get();
            if (ctx != null) {
                context.set(ctx.withActor(player));
            }

            PlayerRef playerRef = (PlayerRef) entityStore.getComponent(ref, PlayerRef.getComponentType());
            UUID playerUuid = playerRef != null ? playerRef.getUuid() : null;
            World world = ((EntityStore) entityStore.getExternalData()).getWorld();
            String worldName = world != null ? world.getName() : "unknown";
            Vector3i targetBlock = ctx != null ? ctx.target() : null;

            if (targetBlock != null) {
                HookSlot slot = resolveBreakSlot();
                if (slot != null) {
                    int verdict = (int) slot.primary().invoke(
                            slot.impl(), playerUuid, worldName,
                            targetBlock.getX(), targetBlock.getY(), targetBlock.getZ());

                    if (verdict == DENY_WITH_MESSAGE || verdict == DENY_SILENT || verdict == DENY_MOD_HANDLES) {
                        String reason = null;
                        if (verdict == DENY_WITH_MESSAGE && slot.hasSecondary()) {
                            reason = (String) slot.secondary().invoke(
                                    slot.impl(), playerUuid, worldName,
                                    targetBlock.getX(), targetBlock.getY(), targetBlock.getZ());
                        }
                        ctx = context.get();
                        if (ctx != null) {
                            context.set(ctx.withVerdict(verdict).withReason(reason));
                        }
                    }
                }
            }
        } catch (Throwable e) {
            FAULTS.report(e);
        }
        return BlockInteractionUtils.isNaturalAction(ref, entityStore);
    }

    /**
     * Stage 4: If denied, skip block removal, resync client, and send denial message.
     */
    @Redirect(
        method = "performPickupByInteraction",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/server/core/modules/interaction/BlockHarvestUtils;removeBlock(Lcom/hypixel/hytale/math/vector/Vector3i;Lcom/hypixel/hytale/server/core/asset/type/blocktype/config/BlockType;ILcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/component/ComponentAccessor;)V")
    )
    private static void interceptRemoval(Vector3i blockPosition, BlockType blockType,
                                         int setBlockSettings, Ref<ChunkStore> chunkReference,
                                         ComponentAccessor<ChunkStore> chunkStore) {
        HarvestContext ctx = context.get();
        int verdict = ctx != null ? ctx.verdict() : ALLOW;

        if (verdict == DENY_WITH_MESSAGE || verdict == DENY_SILENT || verdict == DENY_MOD_HANDLES) {
            // Invalidate block to resync client
            try {
                BlockChunk blockChunk = (BlockChunk) chunkStore.getComponent(chunkReference, BlockChunk.getComponentType());
                if (blockChunk != null) {
                    BlockSection section = blockChunk.getSectionAtBlockY(blockPosition.getY());
                    if (section != null) {
                        section.invalidateBlock(blockPosition.getX(), blockPosition.getY(), blockPosition.getZ());
                    }
                }
            } catch (Exception ignored) {
            }

            // Send denial message for DENY_WITH_MESSAGE
            if (verdict == DENY_WITH_MESSAGE && ctx != null) {
                try {
                    Player player = ctx.actor();
                    Message msg = formatReason(ctx.reason());
                    if (player != null && msg != null) {
                        player.sendMessage(msg);
                    }
                } catch (Exception ignored) {
                }
            }
            return;
        }

        removeBlock(blockPosition, blockType, setBlockSettings, chunkReference, chunkStore);
    }

    /**
     * Stage 5: If denied, skip item pickup. Also evaluates pickup permission independently.
     */
    @Redirect(
        method = "performPickupByInteraction",
        at = @At(value = "INVOKE", target = "Lcom/hypixel/hytale/server/core/entity/ItemUtils;interactivelyPickupItem(Lcom/hypixel/hytale/component/Ref;Lcom/hypixel/hytale/server/core/inventory/ItemStack;Lcom/hypixel/hytale/math/vector/Vector3d;Lcom/hypixel/hytale/component/ComponentAccessor;)V")
    )
    private static void interceptCollection(Ref<EntityStore> ref, ItemStack itemStack,
                                            Vector3d origin, ComponentAccessor<EntityStore> componentAccessor) {
        HarvestContext ctx = context.get();
        int breakVerdict = ctx != null ? ctx.verdict() : ALLOW;

        // If block break was denied, skip pickup entirely
        if (breakVerdict == DENY_WITH_MESSAGE || breakVerdict == DENY_SILENT || breakVerdict == DENY_MOD_HANDLES) {
            return;
        }

        // Evaluate pickup permission independently
        try {
            PlayerRef playerRef = (PlayerRef) componentAccessor.getComponent(ref, PlayerRef.getComponentType());
            UUID playerUuid = playerRef != null ? playerRef.getUuid() : null;
            World world = ((EntityStore) componentAccessor.getExternalData()).getWorld();
            String worldName = world != null ? world.getName() : null;

            if (worldName != null && origin != null) {
                HookSlot slot = resolvePickupSlot();
                if (slot != null) {
                    int pickupVerdict = (int) slot.primary().invoke(
                            slot.impl(), playerUuid, worldName,
                            (int) origin.getX(), (int) origin.getY(), (int) origin.getZ());

                    if (pickupVerdict == DENY_WITH_MESSAGE || pickupVerdict == DENY_SILENT || pickupVerdict == DENY_MOD_HANDLES) {
                        // Send message for DENY_WITH_MESSAGE
                        if (pickupVerdict == DENY_WITH_MESSAGE && slot.hasSecondary()) {
                            String reason = (String) slot.secondary().invoke(
                                    slot.impl(), playerUuid, worldName,
                                    (int) origin.getX(), (int) origin.getY(), (int) origin.getZ());
                            Player player = (Player) componentAccessor.getComponent(ref, Player.getComponentType());
                            Message msg = formatReason(reason);
                            if (player != null && msg != null) {
                                player.sendMessage(msg);
                            }
                        }
                        return;
                    }
                }
            }
        } catch (Throwable e) {
            FAULTS.report(e);
        }

        ItemUtils.interactivelyPickupItem(ref, itemStack, origin, componentAccessor);
    }
}
