package com.hyperprotect.mixin.intercept.building;

import com.hyperprotect.mixin.bridge.FaultReporter;
import com.hyperprotect.mixin.bridge.HookSlot;
import com.hyperprotect.mixin.bridge.ProtectionBridge;
import com.hypixel.hytale.server.core.asset.type.blocktick.BlockTickStrategy;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.asset.type.fluid.FluidTicker;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.invoke.MethodType;

/**
 * Intercepts fire fluid spreading in {@code FluidTicker.process()}.
 *
 * <p>When the fire hook returns a deny verdict, the fire fluid is removed and the
 * tick returns {@link BlockTickStrategy#SLEEP} to halt propagation.
 *
 * <p>Hook contract (fire_spread slot):
 * <ul>
 *   <li>Primary: {@code int evaluateFlame(String, int, int, int)} -- returns verdict</li>
 * </ul>
 *
 * <p>Verdict protocol: 0=ALLOW (let fire spread), any positive=DENY (block fire spread, no messaging).
 * Negative/unknown values = ALLOW (fail-open).
 */
@Mixin(FluidTicker.class)
public abstract class FlameTickInterceptor {

    // --- Verdict constant ---

    @Unique private static final int ALLOW = 0;

    // --- FaultReporter ---

    @Unique
    private static final FaultReporter FAULTS = new FaultReporter("FlameTickInterceptor");

    // --- Cached HookSlot (volatile for cross-thread visibility) ---

    @Unique
    private static volatile HookSlot cachedSlot;

    // --- MethodType for hook resolution ---

    @Unique
    private static final MethodType EVALUATE_TYPE = MethodType.methodType(
            int.class, String.class, int.class, int.class, int.class);

    static {
        System.setProperty("hyperprotect.intercept.fire_spread", "true");
    }

    @Shadow
    protected abstract BlockTickStrategy spread(World world, long tick, FluidTicker.Accessor accessor,
                                                FluidSection fluidSection, BlockSection blockSection,
                                                Fluid fluid, int fluidId, byte fluidLevel,
                                                int worldX, int worldY, int worldZ);

    // --- Hook resolution ---

    @Unique
    private static HookSlot resolveSlot() {
        HookSlot cached = cachedSlot;
        Object current = ProtectionBridge.get(ProtectionBridge.fire_spread);
        if (current == null) {
            cachedSlot = null;
            return null;
        }
        if (cached != null && cached.impl() == current) {
            return cached;
        }
        try {
            cached = ProtectionBridge.resolve(
                    ProtectionBridge.fire_spread,
                    "evaluateFlame", EVALUATE_TYPE);
            cachedSlot = cached;
            return cached;
        } catch (Exception e) {
            FAULTS.report("Failed to resolve fire_spread hook", e);
            return null;
        }
    }

    // --- Injection point ---

    @Redirect(
        method = "process",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hypixel/hytale/server/core/asset/type/fluid/FluidTicker;spread(Lcom/hypixel/hytale/server/core/universe/world/World;JLcom/hypixel/hytale/server/core/asset/type/fluid/FluidTicker$Accessor;Lcom/hypixel/hytale/server/core/universe/world/chunk/section/FluidSection;Lcom/hypixel/hytale/server/core/universe/world/chunk/section/BlockSection;Lcom/hypixel/hytale/server/core/asset/type/fluid/Fluid;IBIII)Lcom/hypixel/hytale/server/core/asset/type/blocktick/BlockTickStrategy;"
        ),
        require = 0
    )
    private BlockTickStrategy onSpread(FluidTicker self, World world, long tick,
                                       FluidTicker.Accessor accessor, FluidSection fluidSection,
                                       BlockSection blockSection, Fluid fluid, int fluidId,
                                       byte fluidLevel, int worldX, int worldY, int worldZ) {

        if (isFlameSource(self) && queryFlameVerdict(world, worldX, worldY, worldZ)) {
            fluidSection.setFluid(worldX, worldY, worldZ, 0, (byte) 0);
            return BlockTickStrategy.SLEEP;
        }

        return this.spread(world, tick, accessor, fluidSection, blockSection,
                fluid, fluidId, fluidLevel, worldX, worldY, worldZ);
    }

    /**
     * Checks if the ticker is a fire-type fluid ticker.
     * Uses {@code contains("Fire")} for broader matching than exact class name equality.
     */
    @Unique
    private static boolean isFlameSource(FluidTicker ticker) {
        return ticker.getClass().getSimpleName().contains("Fire");
    }

    /**
     * Queries the fire spread hook for a verdict.
     *
     * @return true if fire spread should be blocked, false to allow
     */
    @Unique
    private static boolean queryFlameVerdict(World world, int x, int y, int z) {
        try {
            HookSlot slot = resolveSlot();
            if (slot == null) return false; // No hook = allow (fail-open)

            String worldName = world.getName();
            int verdict = (int) slot.primary().invoke(slot.impl(), worldName, x, y, z);

            // Any positive verdict = block fire spread
            return verdict > ALLOW;
        } catch (Throwable e) {
            FAULTS.report(e);
            return false; // Fail-open
        }
    }
}
