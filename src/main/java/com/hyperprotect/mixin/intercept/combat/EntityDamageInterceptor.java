package com.hyperprotect.mixin.intercept.combat;

import com.hyperprotect.mixin.bridge.FaultReporter;
import com.hyperprotect.mixin.bridge.HookSlot;
import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.EntitySnapshot;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.DamageEntityInteraction;
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
 * Intercepts player-initiated entity damage in DamageEntityInteraction.tick0().
 * Covers PvP combat and player-vs-entity damage.
 *
 * <p>Hook contract (entity_damage slot, index 16):
 * <ul>
 *   <li>Primary: {@code int evaluateEntityDamage(UUID attackerUuid, UUID targetUuid, String worldName, int x, int y, int z)}
 *       — targetUuid is null for non-player targets; x/y/z are the target's block position</li>
 *   <li>Secondary: {@code String fetchEntityDamageDenyReason(UUID attackerUuid, UUID targetUuid, String worldName, int x, int y, int z)}
 *       — returns deny reason text sent to the attacker</li>
 * </ul>
 *
 * <p>Verdict protocol: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES.
 * Fail-open on error.
 */
@Mixin(DamageEntityInteraction.class)
public class EntityDamageInterceptor {

    @Unique
    private static final FaultReporter FAULTS = new FaultReporter("EntityDamageInterceptor");

    @Unique
    private static volatile HookSlot cachedSlot;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, UUID.class, UUID.class, String.class, int.class, int.class, int.class);

    @Unique
    private static final MethodType FETCH_REASON_TYPE = MethodType.methodType(
            String.class, UUID.class, UUID.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.entity_damage", "true");
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
        Object current = ProtectionBridge.get(ProtectionBridge.entity_damage);
        if (current == null) {
            cachedSlot = null;
            return null;
        }
        if (cached != null && cached.impl() == current) {
            return cached;
        }
        try {
            cached = ProtectionBridge.resolve(
                    ProtectionBridge.entity_damage,
                    "evaluateEntityDamage", EVALUATE_TYPE,
                    "fetchEntityDamageDenyReason", FETCH_REASON_TYPE);
            cachedSlot = cached;
            return cached;
        } catch (Exception e) {
            FAULTS.report("Failed to resolve entity_damage hook", e);
            return null;
        }
    }

    @Inject(
        method = "tick0",
        at = @At("HEAD"),
        cancellable = true
    )
    private void gateEntityDamage(boolean firstRun, float time,
                                  @Nonnull InteractionType type,
                                  @Nonnull InteractionContext context,
                                  @Nonnull CooldownHandler cooldownHandler,
                                  CallbackInfo ci) {
        try {
            HookSlot slot = resolveSlot();
            if (slot == null) return;

            Ref<EntityStore> targetRef = context.getTargetEntity();
            if (targetRef == null || !targetRef.isValid()) return;

            CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
            if (commandBuffer == null) return;

            Ref<EntityStore> attackerRef = context.getEntity();
            if (attackerRef == null || !attackerRef.isValid()) return;

            PlayerRef attackerPlayerRef = commandBuffer.getComponent(attackerRef, PlayerRef.getComponentType());
            if (attackerPlayerRef == null) return; // Not a player attacker — skip

            World world = commandBuffer.getExternalData().getWorld();
            if (world == null) return;

            UUID attackerUuid = attackerPlayerRef.getUuid();
            String worldName = world.getName();

            // Resolve target UUID (null for non-player targets)
            PlayerRef targetPlayerRef = commandBuffer.getComponent(targetRef, PlayerRef.getComponentType());
            UUID targetUuid = targetPlayerRef != null ? targetPlayerRef.getUuid() : null;

            // Get target position from entity snapshot
            int x = 0, y = 0, z = 0;
            try {
                EntitySnapshot targetSnapshot = context.getSnapshot(targetRef, commandBuffer);
                if (targetSnapshot != null) {
                    Vector3d pos = targetSnapshot.getPosition();
                    x = (int) pos.x;
                    y = (int) pos.y;
                    z = (int) pos.z;
                }
            } catch (Exception ignored) {
                // Position unavailable — pass (0,0,0), consumer should handle gracefully
            }

            int verdict = (int) slot.primary().invoke(
                    slot.impl(), attackerUuid, targetUuid, worldName, x, y, z);

            if (verdict < 0) return; // Fail-open for negative/unknown values

            if (verdict == 1 || verdict == 2 || verdict == 3) {
                if (verdict == 1 && slot.hasSecondary()) {
                    String reason = (String) slot.secondary().invoke(
                            slot.impl(), attackerUuid, targetUuid, worldName, x, y, z);
                    Player player = commandBuffer.getComponent(attackerRef, Player.getComponentType());
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
            // Fail-open: allow damage
        }
    }
}
