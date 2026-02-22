package com.hyperprotect.mixin.intercept.building;

import com.hyperprotect.mixin.bridge.FaultReporter;
import com.hyperprotect.mixin.bridge.HookSlot;
import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.builtin.buildertools.BuilderToolsPacketHandler;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.buildertools.BuilderToolPasteClipboard;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
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
 * Intercepts clipboard paste operations in BuilderToolsPacketHandler.
 *
 * <p>Hook contract (builder_tools slot):
 * <pre>
 *   int evaluatePaste(UUID playerUuid, String worldName, int x, int y, int z)
 *     Verdict: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES
 *
 *   String fetchPasteDenyReason(UUID playerUuid, String worldName, int x, int y, int z)
 *     Returns deny message or null (optional method)
 * </pre>
 */
@Mixin(BuilderToolsPacketHandler.class)
public class PasteInterceptor {

    @Unique
    private static final FaultReporter FAULTS = new FaultReporter("Paste");

    @Unique
    private static volatile HookSlot cachedSlot;

    static {
        System.setProperty("hyperprotect.intercept.builder_tools", "true");
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
     * Inject at the start of handleBuilderToolPasteClipboard to gate paste action.
     */
    @Inject(
        method = "handleBuilderToolPasteClipboard",
        at = @At("HEAD"),
        cancellable = true
    )
    private void gatePasteAction(BuilderToolPasteClipboard packet, PlayerRef playerRef,
                                 Ref<EntityStore> ref, World world,
                                 Store<EntityStore> store, CallbackInfo ci) {
        try {
            HookSlot slot = cachedSlot;

            // Re-resolve if hook changed or not yet cached
            Object current = ProtectionBridge.get(ProtectionBridge.builder_tools);
            if (slot == null || slot.impl() != current) {
                if (current == null) return; // No hook = allow
                slot = ProtectionBridge.resolve(
                        ProtectionBridge.builder_tools,
                        "evaluatePaste",
                        MethodType.methodType(int.class,
                                UUID.class, String.class, int.class, int.class, int.class),
                        "fetchPasteDenyReason",
                        MethodType.methodType(String.class,
                                UUID.class, String.class, int.class, int.class, int.class));
                cachedSlot = slot;
            }

            UUID playerUuid = playerRef.getUuid();
            String worldName = world.getName();

            int verdict = (int) slot.primary().invoke(
                    slot.impl(), playerUuid, worldName,
                    packet.x, packet.y, packet.z);

            // Fail-open for negative/unknown values
            if (verdict < 0) return;

            switch (verdict) {
                case 0 -> { /* ALLOW */ }
                case 1 -> {
                    // DENY_WITH_MESSAGE — fetch reason and notify
                    if (slot.hasSecondary()) {
                        String reason = (String) slot.secondary().invoke(
                                slot.impl(), playerUuid, worldName,
                                packet.x, packet.y, packet.z);
                        Player player = store.getComponent(ref, Player.getComponentType());
                        if (player != null) {
                            Message msg = formatReason(reason);
                            if (msg != null) player.sendMessage(msg);
                        }
                    }
                    ci.cancel();
                }
                case 2, 3 -> {
                    // DENY_SILENT or DENY_MOD_HANDLES — cancel silently
                    ci.cancel();
                }
                default -> { /* Unknown positive = allow (fail-open) */ }
            }
        } catch (Throwable t) {
            FAULTS.report(t);
            // Fail-open: allow paste
        }
    }
}
