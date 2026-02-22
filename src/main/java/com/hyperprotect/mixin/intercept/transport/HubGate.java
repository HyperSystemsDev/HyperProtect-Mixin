package com.hyperprotect.mixin.intercept.transport;

import com.hyperprotect.mixin.bridge.FaultReporter;
import com.hyperprotect.mixin.bridge.HookSlot;
import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.builtin.creativehub.interactions.HubPortalInteraction;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
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
 * Intercepts hub portal interaction in HubPortalInteraction.
 * Uses player position since {@code firstRun()} has no target block parameter.
 *
 * <p>Hook contract (portal slot, index 10):
 * <ul>
 *   <li>Primary: {@code int evaluateGateway(UUID, String, int, int, int)} — returns verdict</li>
 *   <li>Secondary: {@code String fetchGatewayDenyReason(UUID, String, int, int, int)} — returns deny reason text</li>
 * </ul>
 *
 * <p>Verdict protocol: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES.
 * Fail-open on error.
 */
@Mixin(HubPortalInteraction.class)
public class HubGate {

    @Unique private static final int ALLOW             = 0;
    @Unique private static final int DENY_WITH_MESSAGE = 1;
    @Unique private static final int DENY_SILENT       = 2;
    @Unique private static final int DENY_MOD_HANDLES  = 3;

    @Unique
    private static final FaultReporter FAULTS = new FaultReporter("HubGate");

    @Unique
    private static volatile HookSlot cachedSlot;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final MethodType FETCH_REASON_TYPE = MethodType.methodType(
            String.class, UUID.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.hub_portal", "true");
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
        Object current = ProtectionBridge.get(ProtectionBridge.portal);
        if (current == null) {
            cachedSlot = null;
            return null;
        }
        if (cached != null && cached.impl() == current) {
            return cached;
        }
        try {
            cached = ProtectionBridge.resolve(
                    ProtectionBridge.portal,
                    "evaluateGateway", EVALUATE_TYPE,
                    "fetchGatewayDenyReason", FETCH_REASON_TYPE);
            cachedSlot = cached;
            return cached;
        } catch (Exception e) {
            FAULTS.report("Failed to resolve portal hook", e);
            return null;
        }
    }

    @Inject(
        method = "firstRun",
        at = @At("HEAD"),
        cancellable = true
    )
    private void gateGatewayUse(InteractionType type, InteractionContext context,
                                CooldownHandler cooldownHandler, CallbackInfo ci) {
        try {
            HookSlot slot = resolveSlot();
            if (slot == null) return;

            CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
            if (commandBuffer == null) return;

            Ref<EntityStore> ref = context.getEntity();
            Player player = commandBuffer.getComponent(ref, Player.getComponentType());
            if (player == null) return;

            PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) return;

            World world = ((EntityStore) commandBuffer.getExternalData()).getWorld();
            if (world == null) return;

            TransformComponent transform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) return;
            Vector3d pos = transform.getPosition();

            UUID playerUuid = playerRef.getUuid();
            String worldName = world.getName();
            int x = (int) pos.getX();
            int y = (int) pos.getY();
            int z = (int) pos.getZ();

            int verdict = (int) slot.primary().invoke(
                    slot.impl(), playerUuid, worldName, x, y, z);

            if (verdict < 0) return; // Fail-open for negative/unknown values

            if (verdict == DENY_WITH_MESSAGE || verdict == DENY_SILENT || verdict == DENY_MOD_HANDLES) {
                if (verdict == DENY_WITH_MESSAGE && slot.hasSecondary()) {
                    String reason = (String) slot.secondary().invoke(
                            slot.impl(), playerUuid, worldName, x, y, z);
                    Message msg = formatReason(reason);
                    if (msg != null) {
                        player.sendMessage(msg);
                    }
                }
                ci.cancel();
            }
        } catch (Throwable t) {
            FAULTS.report(t);
            // Fail-open: allow hub portal use
        }
    }
}
