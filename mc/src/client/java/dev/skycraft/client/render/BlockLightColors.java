package dev.skycraft.client.render;

import dev.skycraft.link.Proto;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The colour a light-emitting block casts on Skyrim's world. Minecraft's own light has no colour,
 * so these follow what the block looks like: flames warm orange, soul fire cyan, lava deep orange,
 * sea lanterns cool white, and so on. Packed as RGB8 (red in the low byte) with the
 * {@link Proto} light kind in the top byte.
 */
final class BlockLightColors {
	private BlockLightColors() {
	}

	static int of(BlockState state) {
		Block block = state.getBlock();
		String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
		// Most specific first: "soul_torch" is soul light, "redstone_torch" is redstone light.
		if (id.contains("soul")) {
			int kind = id.contains("lantern") ? Proto.LIGHT_STEADY : Proto.LIGHT_FLAME;
			return pack(90, 210, 255, kind) | (id.endsWith("fire") ? HAZARD_FIRE : 0);
		}
		if (id.contains("lava")) {
			return pack(255, 105, 25, Proto.LIGHT_LAVA) | HAZARD_LAVA;
		}
		if (id.equals("magma_block")) {
			return pack(255, 105, 25, Proto.LIGHT_LAVA) | HAZARD_MAGMA;
		}
		if (id.contains("redstone_lamp")) {
			return pack(255, 215, 150, Proto.LIGHT_STEADY);
		}
		if (id.contains("redstone")) {
			return pack(255, 55, 30, Proto.LIGHT_STEADY);
		}
		if (id.contains("jack_o_lantern")) {
			return pack(255, 165, 60, Proto.LIGHT_FLAME);
		}
		if (id.contains("torch")) {
			return pack(255, 185, 105, Proto.LIGHT_FLAME);
		}
		if (id.contains("lantern") && !id.contains("sea")) {
			return pack(255, 195, 120, Proto.LIGHT_FLAME);
		}
		if (id.contains("campfire")) {
			return pack(255, 165, 85, Proto.LIGHT_FLAME) | HAZARD_FIRE;
		}
		if (id.equals("fire")) {
			return pack(255, 145, 55, Proto.LIGHT_FLAME) | HAZARD_FIRE;
		}
		if (id.contains("candle")) {
			return pack(255, 180, 105, Proto.LIGHT_FLAME);
		}
		if (id.contains("furnace") || id.equals("smoker")) {
			return pack(255, 150, 60, Proto.LIGHT_FLAME);
		}
		if (id.equals("glowstone")) {
			return pack(255, 215, 140, Proto.LIGHT_STEADY);
		}
		if (id.equals("shroomlight")) {
			return pack(255, 175, 105, Proto.LIGHT_STEADY);
		}
		if (id.equals("sea_lantern") || id.equals("beacon") || id.equals("end_rod")) {
			return pack(205, 235, 255, Proto.LIGHT_STEADY);
		}
		if (id.equals("conduit")) {
			return pack(160, 225, 255, Proto.LIGHT_STEADY);
		}
		if (id.contains("ochre_froglight")) {
			return pack(255, 220, 140, Proto.LIGHT_STEADY);
		}
		if (id.contains("verdant_froglight")) {
			return pack(170, 255, 150, Proto.LIGHT_STEADY);
		}
		if (id.contains("pearlescent_froglight")) {
			return pack(255, 190, 240, Proto.LIGHT_STEADY);
		}
		if (id.contains("amethyst")) {
			return pack(190, 130, 255, Proto.LIGHT_STEADY);
		}
		if (id.contains("crying_obsidian") || id.equals("nether_portal") || id.equals("respawn_anchor") || id.equals("dragon_egg")) {
			return pack(150, 65, 255, Proto.LIGHT_STEADY);
		}
		if (id.startsWith("end_portal") || id.equals("end_gateway")) {
			return pack(120, 230, 200, Proto.LIGHT_STEADY);
		}
		if (id.contains("sculk")) {
			return pack(60, 200, 220, Proto.LIGHT_STEADY);
		}
		if (id.contains("glow") || id.contains("cave_vines")) {
			return pack(255, 210, 130, Proto.LIGHT_STEADY);
		}
		if (id.contains("copper_bulb")) {
			return pack(255, 190, 130, Proto.LIGHT_STEADY);
		}
		if (id.contains("spawner") || id.equals("vault") || id.equals("creaking_heart")) {
			return pack(255, 165, 90, Proto.LIGHT_FLAME);
		}
		return pack(255, 225, 190, Proto.LIGHT_STEADY);
	}

	// What standing in the block does to a Skyrim NPC (bits 4-7 of the top byte).
	private static final int HAZARD_FIRE = 1 << 28, HAZARD_LAVA = 2 << 28, HAZARD_MAGMA = 3 << 28;

	private static int pack(int r, int g, int b, int kind) {
		return r | g << 8 | b << 16 | kind << 24;
	}
}
