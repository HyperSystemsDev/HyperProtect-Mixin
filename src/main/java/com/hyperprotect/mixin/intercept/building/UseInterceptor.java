package com.hyperprotect.mixin.intercept.building;

import com.hyperprotect.mixin.bridge.FaultReporter;
import com.hyperprotect.mixin.bridge.HookSlot;
import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.ChangeStateInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.UUID;

/**
 * Intercepts block state changes in ChangeStateInteraction.
 * Covers doors, buttons, levers, campfire toggles, lantern toggles, etc.
 *
 * <p>Hook contract (use slot, index 20):
 * <ul>
 *   <li>Primary: {@code int evaluateUse(UUID, String, int, int, int)} — returns verdict</li>
 *   <li>Secondary: {@code String fetchUseDenyReason(UUID, String, int, int, int)} — returns deny reason text</li>
 * </ul>
 *
 * <p>Verdict protocol: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES.
 * Fail-open on error.
 */
@Mixin(ChangeStateInteraction.class)
public class UseInterceptor {

    @Unique private static final int ALLOW             = 0;
    @Unique private static final int DENY_WITH_MESSAGE = 1;
    @Unique private static final int DENY_SILENT       = 2;
    @Unique private static final int DENY_MOD_HANDLES  = 3;

    @Unique
    private static final FaultReporter FAULTS = new FaultReporter("UseInterceptor");

    @Unique
    private static volatile HookSlot cachedSlot;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final MethodType FETCH_REASON_TYPE = MethodType.methodType(
            String.class, UUID.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.use", "true");
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
        Object current = ProtectionBridge.get(ProtectionBridge.use);
        if (current == null) {
            cachedSlot = null;
            return null;
        }
        if (cached != null && cached.impl() == current) {
            return cached;
        }
        try {
            cached = ProtectionBridge.resolve(
                    ProtectionBridge.use,
                    "evaluateUse", EVALUATE_TYPE,
                    "fetchUseDenyReason", FETCH_REASON_TYPE);
            cachedSlot = cached;
            return cached;
        } catch (Exception e) {
            FAULTS.report("Failed to resolve use hook", e);
            return null;
        }
    }

    @Inject(
        method = "interactWithBlock",
        at = @At("HEAD"),
        cancellable = true
    )
    private void gateStateChange(World world, CommandBuffer<EntityStore> commandBuffer,
                                  InteractionType type, InteractionContext context,
                                  ItemStack itemInHand, Vector3i targetBlock,
                                  CooldownHandler cooldownHandler, CallbackInfo ci) {
        try {
            HookSlot slot = resolveSlot();
            if (slot == null) return;

            Ref<EntityStore> ref = context.getEntity();
            Player player = commandBuffer.getComponent(ref, Player.getComponentType());
            if (player == null) return;

            PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) return;

            UUID playerUuid = playerRef.getUuid();
            String worldName = world.getName();

            int verdict = (int) slot.primary().invoke(
                    slot.impl(), playerUuid, worldName,
                    targetBlock.getX(), targetBlock.getY(), targetBlock.getZ());

            if (verdict < 0) return; // Fail-open

            if (verdict == DENY_WITH_MESSAGE || verdict == DENY_SILENT || verdict == DENY_MOD_HANDLES) {
                if (verdict == DENY_WITH_MESSAGE && slot.hasSecondary()) {
                    String reason = (String) slot.secondary().invoke(
                            slot.impl(), playerUuid, worldName,
                            targetBlock.getX(), targetBlock.getY(), targetBlock.getZ());
                    Message msg = formatReason(reason);
                    if (msg != null) {
                        player.sendMessage(msg);
                    }
                }
                context.getState().state = InteractionState.Failed;
                ci.cancel();
            }
        } catch (Throwable t) {
            FAULTS.report(t);
            // Fail-open: allow state change
        }
    }
}
