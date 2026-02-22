package com.hyperprotect.mixin;

import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hyperprotect.mixin.msg.ChatFormatter;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.jetbrains.annotations.NotNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;

/**
 * Early plugin entry point for HyperProtect-Mixin.
 * Initializes the protection bridge and stores cross-classloader references.
 */
public class HyperProtectMixinPlugin extends JavaPlugin {

    public HyperProtectMixinPlugin(@NotNull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        super.setup();

        // Initialize the bridge array
        AtomicReferenceArray<Object> bridge = ProtectionBridge.init();

        // Cache the ChatFormatter.format MethodHandle for cross-classloader access
        try {
            MethodHandle formatHandle = MethodHandles.publicLookup().findStatic(
                    ChatFormatter.class, "format",
                    MethodType.methodType(Message.class, String.class));
            bridge.set(ProtectionBridge.format_handle, formatHandle);
        } catch (Exception e) {
            getLogger().at(Level.WARNING).log("Failed to cache ChatFormatter handle: " + e.getMessage());
        }

        // Store classloader for fallback access
        System.getProperties().put("hyperprotect.bridge.loader", getClass().getClassLoader());

        // Mark bridge as active with version for updater detection
        System.setProperty("hyperprotect.bridge.active", "true");
        System.setProperty("hyperprotect.bridge.version", "1.0.0");

        getLogger().at(Level.INFO).log("HyperProtect-Mixin loaded!");
        getLogger().at(Level.INFO).log("Protection hooks: block-break, block-place, explosion, entity-damage, " +
                "auto-pickup, fire-spread, command, builder-tools, death-drop, durability, " +
                "container-access, container-open, mob-spawn, teleporter, portal, " +
                "hammer, use, seat, respawn, interaction-log");
    }
}
