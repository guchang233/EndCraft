package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/** Opens (or creates) the void "mirror" world automatically once Skyrim is connected. */
public final class MirrorWorld {
	private static final ResourceKey<WorldPreset> PRESET =
		ResourceKey.create(Registries.WORLD_PRESET, ResourceLocation.fromNamespaceAndPath(SkyCraft.MOD_ID, "mirror"));
	private static boolean attempted;
	private static boolean cameraSwitched;
	private static long lastLog;
	// /join: a friend's world for this session (the e4mc link their "Open to LAN" shows); null: our own.
	private static @org.jspecify.annotations.Nullable String sessionJoin;
	// Shown in chat once the player is in a world again (why they're back in their own, ...).
	private static @org.jspecify.annotations.Nullable String pendingNote;

	private MirrorWorld() {
	}

	/**
	 * The address in config/skycraft.properties ({@code join=abc-def.e4mc.link}), if any. Written
	 * with the template below the first time, so there's something to fill in.
	 */
	private static @org.jspecify.annotations.Nullable String joinAddress(Minecraft minecraft) {
		java.nio.file.Path file = minecraft.gameDirectory.toPath().resolve("config").resolve("skycraft.properties");
		java.util.Properties props = new java.util.Properties();
		try {
			if (!java.nio.file.Files.exists(file)) {
				java.nio.file.Files.createDirectories(file.getParent());
				java.nio.file.Files.writeString(file, """
					# SkyCraft
					# To play in a friend's world instead of your own: put their address after join=
					# (the link e4mc shows them when they open their world to LAN), then restart Minecraft.
					join=
					""");
			}
			try (var in = java.nio.file.Files.newBufferedReader(file)) {
				props.load(in);
			}
		} catch (java.io.IOException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't read {}", file, e);
			return null;
		}
		String join = props.getProperty("join", "").trim();
		return join.isEmpty() ? null : join;
	}

	/** /join: leave this world and play in a friend's (their e4mc link, or any server address). */
	public static void joinFriend(Minecraft minecraft, String link) {
		// People paste all sorts: "https://abc-def.e4mc.link/", " abc-def.e4mc.link ".
		String address = link.trim().replaceFirst("^[A-Za-z]+://", "").replaceAll("/+$", "");
		if (address.isEmpty()) {
			return;
		}
		SkyCraft.LOG.info("SkyCraft: /join {}", address);
		sessionJoin = address;
		leaveWorld(minecraft);
	}

	/** The friend's world we're in (the address we joined), or null in our own. */
	public static @org.jspecify.annotations.Nullable String friendAddress(Minecraft minecraft) {
		if (sessionJoin != null) {
			return sessionJoin;
		}
		var server = minecraft.isLocalServer() ? null : minecraft.getCurrentServer();
		return server != null ? server.ip : null;
	}

	/** /leave: back to our own world. */
	public static void leaveFriend(Minecraft minecraft) {
		if (sessionJoin == null) {
			minecraft.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal("You're already in your own world."));
			return;
		}
		SkyCraft.LOG.info("SkyCraft: /leave {}", sessionJoin);
		sessionJoin = null;
		pendingNote = "Back in your own world.";
		leaveWorld(minecraft);
	}

	private static void leaveWorld(Minecraft minecraft) {
		attempted = false;
		cameraSwitched = false;
		minecraft.disconnect();
		minecraft.setScreen(new TitleScreen());  // openWhenReady takes it from the title screen
	}

	/** Every client tick: a note for the player once they're in a world again. */
	public static void tick(Minecraft minecraft) {
		if (pendingNote != null && minecraft.player != null) {
			minecraft.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(pendingNote));
			pendingNote = null;
		}
		// The host only draws Minecraft's Steve when the camera is detached, and seeing that body is
		// the point of the bridge. Switch once the mirror world is up so the player does not have to
		// press F5. Done exactly once: a later F5 back to first person is left alone.
		if (attempted && !cameraSwitched && minecraft.player != null
				&& minecraft.options.getCameraType().isFirstPerson()) {
			minecraft.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
			cameraSwitched = true;
			SkyCraft.LOG.info("SkyCraft: switched to third person so the host draws Steve");
		}
	}

	public static void openWhenReady(Minecraft minecraft) {
		// Couldn't reach a friend's world, or it closed under us: back to our own, and say why.
		if (minecraft.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen && minecraft.level == null) {
			pendingNote = sessionJoin != null
				? "Couldn't stay in " + sessionJoin + " (check the link, and that your friend's world is still open to LAN). You're back in your own world."
				: "Disconnected. You're back in your own world.";
			SkyCraft.LOG.info("SkyCraft: disconnected; back to the mirror world");
			sessionJoin = null;
			attempted = false;
			minecraft.setScreen(new TitleScreen());
			return;
		}
		// Create/Sable worldgen marks the mirror world experimental, so 1.21.1 asks about a backup on
		// every open. The window is hidden while linked; nobody can answer, so continue without one.
		if (attempted && minecraft.level == null && sessionJoin == null
			&& minecraft.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen backup) {
			SkyCraft.LOG.info("SkyCraft: confirming the experimental-settings prompt for the mirror world");
			((dev.skycraft.client.mixin.BackupConfirmAccess) backup).endcraft$onProceed().proceed(false, false);
			return;
		}
		if (attempted && minecraft.level == null && minecraft.screen != null && System.currentTimeMillis() - lastLog > 5000) {
			lastLog = System.currentTimeMillis();
			SkyCraft.LOG.info("SkyCraft: still not in the mirror world; current screen {}", minecraft.screen.getClass().getName());
		}
		if (attempted || minecraft.level != null || minecraft.getOverlay() != null) {
			return;
		}
		// Wait for the menu to settle on the title screen; skip any first-launch prompts in front of it.
		if (!(minecraft.screen instanceof TitleScreen)) {
			if (minecraft.screen != null && System.currentTimeMillis() - lastLog > 5000) {
				lastLog = System.currentTimeMillis();
				SkyCraft.LOG.info("SkyCraft: waiting on screen {} before opening the mirror world", minecraft.screen.getClass().getName());
			}
			if (minecraft.screen == null || minecraft.screen.getClass().getName().contains("Onboarding")) {
				minecraft.setScreen(new TitleScreen());
			}
			return;
		}
		TitleScreen title = (TitleScreen) minecraft.screen;
		attempted = true;
		// Multiplayer: join a friend's world (their e4mc link, or any server address) instead.
		String join = sessionJoin != null ? sessionJoin : joinAddress(minecraft);
		if (join != null) {
			SkyCraft.LOG.info("SkyCraft: joining {}", join);
			pendingNote = "Joined " + join + ". Type /leave to go back to your own world.";
			net.minecraft.client.gui.screens.ConnectScreen.startConnecting(title, minecraft, net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(join),
				new net.minecraft.client.multiplayer.ServerData("SkyCraft", join, net.minecraft.client.multiplayer.ServerData.Type.OTHER), false, null);
			return;
		}
		if (minecraft.getLevelSource().levelExists(SkyCraft.WORLD_NAME)) {
			SkyCraft.LOG.info("SkyCraft: opening mirror world");
			minecraft.createWorldOpenFlows().openWorld(SkyCraft.WORLD_NAME, () -> minecraft.setScreen(title));
			return;
		}
		SkyCraft.LOG.info("SkyCraft: creating mirror world");
		LevelSettings settings = new LevelSettings(
			SkyCraft.WORLD_NAME,
			GameType.SURVIVAL,
			false, Difficulty.NORMAL, true, new net.minecraft.world.level.GameRules(),
			WorldDataConfiguration.DEFAULT
		);
		minecraft.createWorldOpenFlows().createFreshLevel(
			SkyCraft.WORLD_NAME,
			settings,
			new WorldOptions(0L, false, false),
			registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(PRESET).value().createWorldDimensions(),
			title
		);
	}
}
