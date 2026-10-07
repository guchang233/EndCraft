package dev.skycraft.mixin;

import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyNet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Making weapons, tools and armour in Minecraft (crafting table, smithing table) trains Skyrim's
 * Smithing, worth more the better the material, like Skyrim's own forge (its XP grows with the
 * item's value).
 */
@Mixin(ItemStack.class)
public abstract class SmithingMixin {
	@Inject(method = "onCraftedBy", at = @At("HEAD"))
	private void skycraft$trainSmithing(Player player, int craftCount, CallbackInfo ci) {
		ItemStack stack = (ItemStack) (Object) this;
		if (!(player instanceof ServerPlayer serverPlayer) || !SkyNet.isHost(serverPlayer) || craftCount <= 0) {
			return;
		}
		if (!stack.has(DataComponents.EQUIPPABLE) && !stack.has(DataComponents.TOOL) && !stack.has(DataComponents.WEAPON) && !stack.is(Items.BOW)
			&& !stack.is(Items.CROSSBOW)) {
			return;
		}
		SkyLink.pushEvent(Proto.EV_SKILL_USE, Proto.SKILL_SMITHING, skycraft$worth(stack) * craftCount, 0.0F, 0.0F, 0.0F, 0);
	}

	@Unique
	private static float skycraft$worth(ItemStack stack) {
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		if (path.startsWith("netherite_")) {
			return 150.0F;
		}
		if (path.startsWith("diamond_")) {
			return 80.0F;
		}
		if (path.startsWith("iron_")) {
			return 40.0F;
		}
		if (path.startsWith("golden_") || path.startsWith("chainmail_")) {
			return 30.0F;
		}
		if (path.startsWith("copper_")) {
			return 25.0F;
		}
		if (path.startsWith("wooden_")) {
			return 15.0F;
		}
		return 20.0F;  // stone, leather, bows, shields, ...
	}
}
