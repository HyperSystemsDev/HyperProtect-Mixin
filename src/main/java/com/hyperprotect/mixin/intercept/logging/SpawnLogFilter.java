package com.hyperprotect.mixin.intercept.logging;

import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.spawning.util.FloodFillPositionSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.logging.Level;

/**
 * Suppresses verbose spawn-related logging from FloodFillPositionSelector.
 * When the interaction_log hook indicates filtering is active, WARNING-level
 * spawn debug messages are downgraded to FINEST (effectively silenced).
 *
 * Hook contract (interaction_log):
 *   boolean isLogFiltered() → true = filter, false = normal logging
 */
@Mixin(FloodFillPositionSelector.class)
public class SpawnLogFilter {

    @Unique
    private static volatile MethodHandle cachedHandle;

    @Unique
    private static volatile Object cachedImpl;

    static {
        System.setProperty("hyperprotect.intercept.interaction_log", "true");
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
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/logger/HytaleLogger;at(Ljava/util/logging/Level;)Lcom/hypixel/hytale/logger/HytaleLogger$Api;"
        ),
        require = 0
    )
    private HytaleLogger.Api filterLogOutput(HytaleLogger logger, Level level) {
        if (isFiltered() && level == Level.WARNING) {
            return logger.at(Level.FINEST);
        }
        return logger.at(level);
    }
}
