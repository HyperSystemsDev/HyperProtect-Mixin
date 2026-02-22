package com.hyperprotect.mixin.intercept.commands;

import com.hyperprotect.mixin.bridge.FaultReporter;
import com.hyperprotect.mixin.bridge.HookSlot;
import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.entity.entities.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.concurrent.CompletableFuture;

/**
 * Intercepts command execution in CommandManager.handleCommand().
 * Only player commands are gated; console commands pass through unconditionally.
 *
 * <p>Hook contract (command slot):
 * <pre>
 *   int evaluateCommand(Player player, String commandString)
 *     Verdict: 0=ALLOW, 1=DENY_WITH_MESSAGE, 2=DENY_SILENT, 3=DENY_MOD_HANDLES
 *
 *   String fetchCommandDenyReason(Player player, String commandString)
 *     Returns deny message or null (optional method)
 * </pre>
 */
@Mixin(CommandManager.class)
public class CommandGateInterceptor {

    @Unique
    private static final FaultReporter FAULTS = new FaultReporter("CommandGate");

    @Unique
    private static volatile HookSlot cachedSlot;

    static {
        System.setProperty("hyperprotect.intercept.command", "true");
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
     * Inject at the start of handleCommand to gate command dispatch.
     * Console senders pass through unconditionally.
     */
    @Inject(
        method = "handleCommand",
        at = @At("HEAD"),
        cancellable = true
    )
    private void gateCommandDispatch(CommandSender sender, String commandString,
                                     CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (!(sender instanceof Player player)) {
            return; // Console commands pass through
        }

        try {
            HookSlot slot = cachedSlot;

            // Re-resolve if hook changed or not yet cached
            Object current = ProtectionBridge.get(ProtectionBridge.command);
            if (slot == null || slot.impl() != current) {
                if (current == null) return; // No hook = allow
                slot = ProtectionBridge.resolve(
                        ProtectionBridge.command,
                        "evaluateCommand",
                        MethodType.methodType(int.class, Player.class, String.class),
                        "fetchCommandDenyReason",
                        MethodType.methodType(String.class, Player.class, String.class));
                cachedSlot = slot;
            }

            int verdict = (int) slot.primary().invoke(slot.impl(), player, commandString);

            // Fail-open for negative/unknown values
            if (verdict < 0) return;

            switch (verdict) {
                case 0 -> { /* ALLOW */ }
                case 1 -> {
                    // DENY_WITH_MESSAGE — fetch reason and notify
                    if (slot.hasSecondary()) {
                        String reason = (String) slot.secondary().invoke(
                                slot.impl(), player, commandString);
                        Message msg = formatReason(reason);
                        if (msg != null) player.sendMessage(msg);
                    }
                    cir.setReturnValue(CompletableFuture.completedFuture(null));
                }
                case 2, 3 -> {
                    // DENY_SILENT or DENY_MOD_HANDLES — cancel silently
                    cir.setReturnValue(CompletableFuture.completedFuture(null));
                }
                default -> { /* Unknown positive = allow (fail-open) */ }
            }
        } catch (Throwable t) {
            FAULTS.report(t);
            // Fail-open: allow command
        }
    }
}
