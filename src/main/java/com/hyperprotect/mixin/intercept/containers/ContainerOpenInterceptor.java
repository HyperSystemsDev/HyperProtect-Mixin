package com.hyperprotect.mixin.intercept.containers;

import com.hyperprotect.mixin.bridge.FaultReporter;
import com.hyperprotect.mixin.bridge.HookSlot;
import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenContainerInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.UUID;

/**
 * Intercepts container (chest) opening in OpenContainerInteraction.interactWithBlock().
 *
 * <p>Hook contract (container_open slot, index 17):
 * <ul>
 *   <li>Primary: {@code int evaluateContainerOpen(UUID, String, int, int, int)} — returns verdict</li>
 *   <li>Secondary: {@code String fetchContainerOpenDenyReason(UUID, String, int, int, int)} — returns deny reason text</li>
 * </ul>
 *
 * <p>Verdict protocol: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES.
 * Fail-open on error.
 */
@Mixin(OpenContainerInteraction.class)
public class ContainerOpenInterceptor {

    @Unique
    private static final FaultReporter FAULTS = new FaultReporter("ContainerOpenInterceptor");

    @Unique
    private static volatile HookSlot cachedSlot;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final MethodType FETCH_REASON_TYPE = MethodType.methodType(
            String.class, UUID.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.container_open", "true");
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
        Object current = ProtectionBridge.get(ProtectionBridge.container_open);
        if (current == null) {
            cachedSlot = null;
            return null;
        }
        if (cached != null && cached.impl() == current) {
            return cached;
        }
        try {
            cached = ProtectionBridge.resolve(
                    ProtectionBridge.container_open,
                    "evaluateContainerOpen", EVALUATE_TYPE,
                    "fetchContainerOpenDenyReason", FETCH_REASON_TYPE);
            cachedSlot = cached;
            return cached;
        } catch (Exception e) {
            FAULTS.report("Failed to resolve container_open hook", e);
            return null;
        }
    }

    @Inject(
        method = "interactWithBlock",
        at = @At("HEAD"),
        cancellable = true
    )
    private void gateContainerOpen(@Nonnull World world,
                                   @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                   @Nonnull InteractionType type,
                                   @Nonnull InteractionContext context,
                                   @Nullable ItemStack itemInHand,
                                   @Nonnull Vector3i pos,
                                   @Nonnull CooldownHandler cooldownHandler,
                                   CallbackInfo ci) {
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
                    pos.getX(), pos.getY(), pos.getZ());

            if (verdict < 0) return; // Fail-open for negative/unknown values

            if (verdict == 1 || verdict == 2 || verdict == 3) {
                if (verdict == 1 && slot.hasSecondary()) {
                    String reason = (String) slot.secondary().invoke(
                            slot.impl(), playerUuid, worldName,
                            pos.getX(), pos.getY(), pos.getZ());
                    Message msg = formatReason(reason);
                    if (msg != null) player.sendMessage(msg);
                }
                ci.cancel();
            }
        } catch (Throwable t) {
            FAULTS.report(t);
            // Fail-open: allow container opening
        }
    }
}
