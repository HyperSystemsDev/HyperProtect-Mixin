package com.hyperprotect.mixin.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Eagerly-cached MethodHandle wrapper for a hook implementation.
 *
 * Created by {@link ProtectionBridge#resolve} when a mixin interceptor first
 * needs to call a hook. Stores pre-resolved MethodHandles for the primary
 * evaluation method and an optional secondary (deny-reason) method.
 *
 * Interceptors cache the HookSlot and compare {@link #impl()} against the
 * current bridge array value to detect hook replacement.
 */
public final class HookSlot {

    private final Object impl;
    private final MethodHandle primary;
    private final MethodHandle secondary; // nullable

    private HookSlot(Object impl, MethodHandle primary, MethodHandle secondary) {
        this.impl = impl;
        this.primary = primary;
        this.secondary = secondary;
    }

    /**
     * Creates a HookSlot with eagerly-resolved MethodHandles.
     *
     * @param impl           the hook object
     * @param primaryName    name of the primary method (e.g. "evaluate")
     * @param primaryType    expected MethodType of the primary method
     * @param secondaryName  name of the secondary method (e.g. "fetchDenyReason"), or null
     * @param secondaryType  expected MethodType of the secondary method, or null
     * @return resolved HookSlot
     * @throws IllegalArgumentException if primary method cannot be resolved
     */
    static HookSlot of(Object impl,
                       String primaryName,
                       MethodType primaryType,
                       String secondaryName,
                       MethodType secondaryType) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> hookClass = impl.getClass();

            MethodHandle primary = lookup.findVirtual(hookClass, primaryName, primaryType);

            MethodHandle secondary = null;
            if (secondaryName != null && secondaryType != null) {
                try {
                    secondary = lookup.findVirtual(hookClass, secondaryName, secondaryType);
                } catch (NoSuchMethodException ignored) {
                    // Secondary is optional
                }
            }

            return new HookSlot(impl, primary, secondary);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalArgumentException(
                    "Cannot resolve " + primaryName + " on " + impl.getClass().getName(), e);
        }
    }

    /** The hook implementation object. */
    public Object impl() { return impl; }

    /** Pre-resolved MethodHandle for the primary evaluation method. */
    public MethodHandle primary() { return primary; }

    /** Pre-resolved MethodHandle for the secondary (deny-reason) method, or null. */
    public MethodHandle secondary() { return secondary; }

    /** Whether a secondary method was resolved. */
    public boolean hasSecondary() { return secondary != null; }
}
