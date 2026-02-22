package com.hyperprotect.mixin.intercept.containers;

import com.hyperprotect.mixin.bridge.FaultReporter;
import com.hyperprotect.mixin.bridge.HookSlot;
import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.UUID;

/**
 * Intercepts crafting in CraftingManager.craftItem() to check container access.
 * Uses bench position from the CraftingManager shadow fields (x, y, z).
 *
 * <p>Hook contract (container_access slot):
 * <pre>
 *   int evaluateCrafting(UUID playerUuid, String worldName, int x, int y, int z)
 *     Verdict: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES
 *
 *   String fetchCraftingDenyReason(UUID playerUuid, String worldName, int x, int y, int z)
 *     Returns deny message or null (optional method)
 * </pre>
 */
@Mixin(CraftingManager.class)
public class CraftingGateInterceptor {

    @Shadow
    private int x;

    @Shadow
    private int y;

    @Shadow
    private int z;

    @Unique
    private static final FaultReporter FAULTS = new FaultReporter("CraftingGate");

    @Unique
    private static volatile HookSlot cachedSlot;

    static {
        System.setProperty("hyperprotect.intercept.container_access", "true");
    }

    @Unique
    private static Message formatReason(String reason) {
        if (reason == null || reason.isEmpty()) return null;
        Object handle = ProtectionBridge.get(ProtectionBridge.format_handle);
        if (handle instanceof java.lang.invoke.MethodHandle mh) {
            try { return (Message) mh.invoke(reason); } catch (Throwable ignored) {}
        }
        return Message.raw(reason);
    }

    /**
     * Inject at the start of craftItem to gate crafting access.
     * Uses the bench position stored in the CraftingManager component fields.
     */
    @Inject(
        method = "craftItem",
        at = @At("HEAD"),
        cancellable = true
    )
    private void gateCraftingAccess(Ref<EntityStore> ref,
                                    ComponentAccessor<EntityStore> componentAccessor,
                                    CraftingRecipe recipe, int quantity,
                                    ItemContainer itemContainer,
                                    CallbackInfoReturnable<Boolean> cir) {
        try {
            HookSlot slot = cachedSlot;

            // Re-resolve if hook changed or not yet cached
            Object current = ProtectionBridge.get(ProtectionBridge.container_access);
            if (slot == null || slot.impl() != current) {
                if (current == null) return; // No hook = allow
                slot = ProtectionBridge.resolve(
                        ProtectionBridge.container_access,
                        "evaluateCrafting",
                        MethodType.methodType(int.class,
                                UUID.class, String.class, int.class, int.class, int.class),
                        "fetchCraftingDenyReason",
                        MethodType.methodType(String.class,
                                UUID.class, String.class, int.class, int.class, int.class));
                cachedSlot = slot;
            }

            PlayerRef playerRef = componentAccessor.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) return;

            // Get world name from the component accessor context
            String worldName = null;
            try {
                worldName = ((EntityStore) componentAccessor.getExternalData())
                        .getWorld().getName();
            } catch (Exception ignored) {}
            if (worldName == null) return;

            UUID playerUuid = playerRef.getUuid();

            int verdict = (int) slot.primary().invoke(
                    slot.impl(), playerUuid, worldName,
                    this.x, this.y, this.z);

            // Fail-open for negative/unknown values
            if (verdict < 0) return;

            switch (verdict) {
                case 0 -> { /* ALLOW */ }
                case 1 -> {
                    // DENY_WITH_MESSAGE — fetch reason and notify
                    if (slot.hasSecondary()) {
                        String reason = (String) slot.secondary().invoke(
                                slot.impl(), playerUuid, worldName,
                                this.x, this.y, this.z);
                        Player player = componentAccessor.getComponent(ref, Player.getComponentType());
                        if (player != null) {
                            Message msg = formatReason(reason);
                            if (msg != null) player.sendMessage(msg);
                        }
                    }
                    cir.setReturnValue(false); // Deny crafting
                }
                case 2, 3 -> {
                    // DENY_SILENT or DENY_MOD_HANDLES — deny without message
                    cir.setReturnValue(false);
                }
                default -> { /* Unknown positive = allow (fail-open) */ }
            }
        } catch (Throwable t) {
            FAULTS.report(t);
            // Fail-open: allow crafting
        }
    }
}
