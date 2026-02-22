package com.hyperprotect.mixin.intercept.logging;

import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.InteractionEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.logging.Level;

/**
 * Suppresses desync logging in InteractionEntry.setClientState().
 * Desync detection messages are downgraded to FINEST when filtering is active.
 *
 * Hook contract (interaction_log):
 *   boolean isLogFiltered() → true = filter, false = normal logging
 */
@Mixin(InteractionEntry.class)
public class EntryDesyncFilter {

    @Unique
    private static volatile MethodHandle cachedHandle;

    @Unique
    private static volatile Object cachedImpl;

    static {
        System.setProperty("hyperprotect.intercept.interaction_entry_desync", "true");
    }

    @Unique
    private static boolean isFiltered() {
        try {
            Object impl = ProtectionBridge.get(ProtectionBridge.interaction_log);
            if (impl == null) return false;

            MethodHandle mh = cachedHandle;
            if (cachedImpl != impl || mh == null) {
                var slot = ProtectionBridge.resolve(ProtectionBridge.interaction_log,
                        "isLogFiltered", MethodType.methodType(boolean.class));
                if (slot == null) return false;
                cachedHandle = mh = slot.primary();
                cachedImpl = impl;
            }

            return (boolean) mh.invoke(impl);
        } catch (Throwable e) {
            return false;
        }
    }

    @Redirect(
        method = "setClientState",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/logger/HytaleLogger;at(Ljava/util/logging/Level;)Lcom/hypixel/hytale/logger/HytaleLogger$Api;"
        ),
        require = 0
    )
    private HytaleLogger.Api filterDesyncOutput(HytaleLogger logger, Level level) {
        if (isFiltered()) {
            return logger.at(Level.FINEST);
        }
        return logger.at(level);
    }
}
