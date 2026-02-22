package com.hyperprotect.mixin.intercept.building;

import com.hyperprotect.mixin.bridge.FaultReporter;
import com.hyperprotect.mixin.bridge.HookSlot;
import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionSyncData;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.PlaceBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nonnull;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.UUID;

/**
 * Intercepts block placement in PlaceBlockInteraction.tick0().
 *
 * <p>Hook contract (block_place slot, index 18):
 * <ul>
 *   <li>Primary: {@code int evaluateBlockPlace(UUID, String, int, int, int)} — returns verdict</li>
 *   <li>Secondary: {@code String fetchBlockPlaceDenyReason(UUID, String, int, int, int)} — returns deny reason text</li>
 * </ul>
 *
 * <p>Verdict protocol: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES.
 * Fail-open on error.
 */
@Mixin(PlaceBlockInteraction.class)
public class BlockPlaceInterceptor {

    @Unique
    private static final FaultReporter FAULTS = new FaultReporter("BlockPlaceInterceptor");

    @Unique
    private static volatile HookSlot cachedSlot;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final MethodType FETCH_REASON_TYPE = MethodType.methodType(
            String.class, UUID.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.block_place", "true");
    }

    @Unique
    private static Message formatReason(String reason) {
        if (reason == null || reason.isEmpty()) return null;
        Object handle = ProtectionBridge.get(ProtectionBridge.format_handle);
        if (handle instanceof MethodHandle mh) {
            try { return (Message) mh.invoke(reason); } catch (Throwable ignored) {}
        }
        return Message.raw(reason);
    }

    @Unique
    private static HookSlot resolveSlot() {
        HookSlot cached = cachedSlot;
        Object current = ProtectionBridge.get(ProtectionBridge.block_place);
        if (current == null) {
            cachedSlot = null;
            return null;
        }
        if (cached != null && cached.impl() == current) {
            return cached;
        }
        try {
            cached = ProtectionBridge.resolve(
                    ProtectionBridge.block_place,
                    "evaluateBlockPlace", EVALUATE_TYPE,
                    "fetchBlockPlaceDenyReason", FETCH_REASON_TYPE);
            cachedSlot = cached;
            return cached;
        } catch (Exception e) {
            FAULTS.report("Failed to resolve block_place hook", e);
            return null;
        }
    }

    @Inject(
        method = "tick0",
        at = @At("HEAD"),
        cancellable = true
    )
    private void gateBlockPlace(boolean firstRun, float time,
                                @Nonnull InteractionType type,
                                @Nonnull InteractionContext context,
                                @Nonnull CooldownHandler cooldownHandler,
                                CallbackInfo ci) {
        if (!firstRun) return; // Only check on the initial placement tick

        try {
            HookSlot slot = resolveSlot();
            if (slot == null) return;

            InteractionSyncData clientState = context.getClientState();
            if (clientState == null) return;
            BlockPosition blockPosition = clientState.blockPosition;
            if (blockPosition == null) return;

            Ref<EntityStore> ref = context.getEntity();
            CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
            if (commandBuffer == null) return;

            PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) return;

            World world = commandBuffer.getExternalData().getWorld();
            if (world == null) return;

            UUID playerUuid = playerRef.getUuid();
            String worldName = world.getName();

            int verdict = (int) slot.primary().invoke(
                    slot.impl(), playerUuid, worldName,
                    blockPosition.x, blockPosition.y, blockPosition.z);

            if (verdict < 0) return; // Fail-open for negative/unknown values

            if (verdict == 1 || verdict == 2 || verdict == 3) {
                if (verdict == 1 && slot.hasSecondary()) {
                    String reason = (String) slot.secondary().invoke(
                            slot.impl(), playerUuid, worldName,
                            blockPosition.x, blockPosition.y, blockPosition.z);
                    Player player = commandBuffer.getComponent(ref, Player.getComponentType());
                    if (player != null) {
                        Message msg = formatReason(reason);
                        if (msg != null) player.sendMessage(msg);
                    }
                }
                context.getState().state = InteractionState.Failed;
                ci.cancel();
            }
        } catch (Throwable t) {
            FAULTS.report(t);
            // Fail-open: allow block placement
        }
    }
}
