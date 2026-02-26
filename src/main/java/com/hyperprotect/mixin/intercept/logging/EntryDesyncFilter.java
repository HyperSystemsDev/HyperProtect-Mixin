package com.hyperprotect.mixin.intercept.logging;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.InteractionEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
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
    private static final AtomicLong faultCount = new AtomicLong();

    @Unique
    private static volatile Object[] hookCache;

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(boolean.class);

    static {
        System.setProperty("hyperprotect.intercept.interaction_entry_desync", "true");
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static Object getBridge(int slot) {
        try {
            Object bridge = System.getProperties().get("hyperprotect.bridge");
            if (bridge == null) return null;
            return ((AtomicReferenceArray<Object>) bridge).get(slot);
        } catch (Exception e) {
            return null;
        }
    }

    @Unique
    private static void reportFault(Throwable t) {
        long count = faultCount.incrementAndGet();
        if (count == 1 || count % 100 == 0) {
            System.err.println("[HyperProtect] EntryDesyncFilter error #" + count + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    @Unique
    private static Object[] resolveHook() {
        Object[] cached = hookCache;
        Object impl = getBridge(12);
        if (impl == null) {
            hookCache = null;
            return null;
        }
        if (cached != null && cached[0] == impl) {
            return cached;
        }
        try {
            MethodHandle primary = MethodHandles.publicLookup().findVirtual(
                impl.getClass(), "isLogFiltered", EVALUATE_TYPE);
            cached = new Object[] { impl, primary };
            hookCache = cached;
            return cached;
        } catch (Exception e) {
            reportFault(e);
            return null;
        }
    }

    @Unique
    private static boolean isFiltered() {
        try {
            Object[] hook = resolveHook();
            if (hook == null) return false;
            return (boolean) ((MethodHandle) hook[1]).invoke(hook[0]);
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
