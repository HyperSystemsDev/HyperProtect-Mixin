package com.hyperprotect.mixin.intercept.containers;

import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.builtin.crafting.window.BenchWindow;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Captures workbench block position context in BenchWindow.onOpen0().
 * Stores the bench coordinates in a ThreadLocal so {@link CraftingGateInterceptor}
 * can use them for container_access permission checks during crafting.
 *
 * <p>This mixin does not perform hook checks — it only provides position context.
 */
@Mixin(BenchWindow.class)
public class BenchPositionCapture {

    /** ThreadLocal bench coordinates for use by CraftingGateInterceptor. */
    @Unique
    static final ThreadLocal<int[]> benchCoords = new ThreadLocal<>();

    static {
        System.setProperty("hyperprotect.intercept.workbench_context", "true");
    }

    /**
     * Redirect the setBench call to capture block position before it executes.
     */
    @Redirect(
        method = "onOpen0",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/builtin/crafting/component/CraftingManager;setBench(IIILcom/hypixel/hytale/server/core/asset/type/blocktype/config/BlockType;)V"
        )
    )
    private void captureBenchCoords(CraftingManager craftingManager, int x, int y, int z,
                                    BlockType blockType) {
        // Store position for CraftingGateInterceptor
        benchCoords.set(new int[]{x, y, z});

        // Call original
        craftingManager.setBench(x, y, z, blockType);
    }
}
