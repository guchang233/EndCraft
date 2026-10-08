package dev.skycraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.WeightedPressurePlateBlock;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Pressure plates notice anything in their whole block, not just its bottom quarter. On uneven
 * Skyrim ground the plate sits at its block's floor while whoever stands on it is on the terrain,
 * often a little higher up inside that block, so they walked over plates without pressing them.
 */
@Mixin({ PressurePlateBlock.class, WeightedPressurePlateBlock.class })
public abstract class PressurePlateMixin {
	@Unique
	private static final AABB SKYCRAFT$TOUCH = new AABB(1.0 / 16.0, 0.0, 1.0 / 16.0, 15.0 / 16.0, 1.0, 15.0 / 16.0);

	@WrapOperation(
		method = "getSignalStrength",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/AABB;move(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/AABB;")
	)
	private AABB skycraft$wholeBlock(AABB touch, BlockPos pos, Operation<AABB> original) {
		return original.call(SKYCRAFT$TOUCH, pos);
	}
}
