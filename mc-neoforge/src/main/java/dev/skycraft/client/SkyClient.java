package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.client.render.WorldExporter;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.world.SkyCollision;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Per-frame glue between the Minecraft client and Skyrim. Everything here runs on the render
 * thread, called from MinecraftMixin.
 */
public final class SkyClient {
	private static final boolean SHOW_WINDOW = Boolean.getBoolean("skycraft.showWindow");
	// Started by Skyrim (SkyCraft's bundled instance passes -Dskycraft.startHidden=true): no window and
	// no title-screen music from the first frame, even while Skyrim is paused (Alt-Tabbed) and the
	// two haven't linked up yet. Otherwise the window only goes once Skyrim is there.
	private static final boolean START_HIDDEN = Boolean.getBoolean("skycraft.startHidden");
	private static boolean startedHidden;

	private static final SkyLink.SkyState sky = new SkyLink.SkyState();
	private static final SkyLink.McState mc = new SkyLink.McState();
	private static volatile boolean linked;
	private static boolean tookOver;
	private static boolean windowHidden;
	private static int appliedViewportW, appliedViewportH;

	// Teleport / hold state: Skyrim decides where the player is after loads, doors and respawns.
	private static int lastTeleportSeq = -1;
	private static int teleportAck;
	private static boolean teleportPending;
	private static LocalPlayer lastPlayer;
	private static net.minecraft.client.CameraType lastLivingCamera = net.minecraft.client.CameraType.THIRD_PERSON_BACK;
	private static boolean hadPlayer;
	private static Vec3 holdPos;
    private static boolean recoveryRequested;
	private static Vec3 unlinkedHold;
	private static long holdSince;
	private static long qpcFreq;
	private static LocalPlayer eyePlayer;
	private static float eyeSmoothed;
	private static long frameCounter;
	private static int lastPacedSeq;
	private static boolean skyrimStalled;
	private static int exporterErrors;

	public static void requestRecovery() {
        recoveryRequested = true;
        Minecraft.getInstance().options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
    }

    private SkyClient() {
	}

	public static boolean linked() {
		return linked;
	}

	/**
	 * True once Skyrim has connected in this session. From then on Minecraft never touches the
	 * real mouse or keyboard again (even if Skyrim closes), since its window is hidden.
	 */
	public static boolean tookOver() {
		return tookOver;
	}

	public static SkyLink.SkyState sky() {
		return sky;
	}

	/** Start of Minecraft.runTick: pull state and input from Skyrim before anything else runs. */
	public static void beginFrame() {
		SkyLink.poll();
		quitWithSkyrim(Minecraft.getInstance());
		if (START_HIDDEN && !startedHidden) {
			startedHidden = true;
			Minecraft minecraft = Minecraft.getInstance();
			hideWindowOnce(minecraft);
			minecraft.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
			minecraft.getMusicManager().stopPlaying();
		}
		boolean nowLinked = SkyLink.active();
		if (nowLinked) {
			SkyLink.readSkyState(sky); // on a torn read we simply keep last frame's state
			dev.skycraft.world.SkyWater.refresh();
		} else {
			dev.skycraft.world.SkyWater.clear();
		}
		if (nowLinked != linked) {
			linked = nowLinked;
			SkyCraft.LOG.info("SkyCraft: Skyrim link {}", linked ? "up" : "down");
			if (linked) {
				tookOver = true;
				unlinkedHold = null;
				SkyCollision.startConsumer();
				applyLinkedOptions();
			} else {
				InputBridge.releaseAll();
				LocalPlayer player = Minecraft.getInstance().player;
				unlinkedHold = player != null ? player.position() : null;
			}
		}
		if (!linked) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		hideWindowOnce(minecraft);
		applyViewportSize(minecraft);
		MirrorWorld.openWhenReady(minecraft);

		if (sky.menuOpen() || sky.loading()) {
			InputBridge.releaseAll();
		}
		InputBridge.drain(minecraft);
		ProxySync.frame(minecraft);

		LocalPlayer player = minecraft.player;
		if (player == null) {
			lastPlayer = null;
			return;
		}

		// A new player object means we just joined or respawned: put it where Skyrim's player is.
		if (player != lastPlayer) {
			// Vanilla resets the camera during respawn. Retain the user's last living view.
			if (hadPlayer) minecraft.options.setCameraType(lastLivingCamera);
			hadPlayer = true;
			lastPlayer = player;
			teleportPending = true;
		}
		if (sky.teleportSeq != lastTeleportSeq) {
			lastTeleportSeq = sky.teleportSeq;
            recoveryRequested = false;
			teleportPending = true;
		}
		if (teleportPending && sky.inGame() && !sky.loading()) {
			requestTeleport(minecraft, sky.x, sky.y, sky.z, sky.yaw, sky.pitch);
			teleportAck = sky.teleportSeq;
			teleportPending = false;
			holdPos = new Vec3(sky.x, sky.y, sky.z);
		}

		// Look direction is driven by Skyrim (zero-latency camera); MC uses it for everything else.
		if (minecraft.screen == null) {
			player.setYRot(sky.yaw);
			player.setXRot(sky.pitch);
			player.yRotO = sky.yaw;
			player.xRotO = sky.pitch;
		}
	}

	// Minecraft is started with Skyrim (the SKSE plugin launches it), so it goes when that Skyrim has
	// closed for good: saved and shut down the normal way. -Dskycraft.quitWithSkyrim=false keeps it
	// running instead (development: restarting Skyrim without restarting Minecraft).
	private static final boolean QUIT_WITH_SKYRIM = Boolean.parseBoolean(System.getProperty("skycraft.quitWithSkyrim", "false"));
	private static long skyrimGoneSince;
	private static long nextSkyrimCheck;
	// Started hidden by Skyrim but never connected: nobody can see or use this Minecraft, and it
	// would stop the next Skyrim from starting a fresh one ("already running"). It goes after this.
	private static final long NEVER_CONNECTED_QUIT_MS = 10 * 60 * 1000;
	private static final long STARTED_AT = System.currentTimeMillis();
	private static boolean gaveUpWaiting;

	private static void quitWithSkyrim(Minecraft minecraft) {
		int pid = SkyLink.skyrimPid();
		long now = System.currentTimeMillis();
		if (QUIT_WITH_SKYRIM && START_HIDDEN && pid == 0 && !tookOver && !gaveUpWaiting && now - STARTED_AT > NEVER_CONNECTED_QUIT_MS) {
			gaveUpWaiting = true;
			SkyCraft.LOG.warn("SkyCraft: started hidden but Skyrim never connected in {} minutes; quitting", NEVER_CONNECTED_QUIT_MS / 60000);
			minecraft.stop();
			return;
		}
		if (!QUIT_WITH_SKYRIM || pid == 0 || now < nextSkyrimCheck) {
			return;
		}
		nextSkyrimCheck = now + 1000;
		if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
			skyrimGoneSince = 0;
			return;
		}
		if (skyrimGoneSince == 0) {
			skyrimGoneSince = now;
		} else if (now - skyrimGoneSince > 5000) {
			SkyCraft.LOG.info("SkyCraft: Skyrim (pid {}) has closed; saving and quitting", pid);
			minecraft.stop();
		}
	}

	/** Called at the end of every client tick. */
	public static void clientTick(Minecraft minecraft) {
		MirrorWorld.tick(minecraft);

		SkyDigClient.tick(minecraft);
		freezeWhileUnlinked(minecraft);
		holdUntilReady(minecraft);
		publishTick(minecraft);
	}

	/**
	 * Skyrim went quiet (a long loading screen, a stall, or it closed). Its collision around the
	 * player may be about to change (interior doors), so keep the player exactly where they were
	 * instead of letting them fall; Skyrim puts them where they belong when it's back.
	 */
	private static void freezeWhileUnlinked(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (linked || !tookOver || player == null) {
			return;
		}
		if (unlinkedHold == null) {
			unlinkedHold = player.position();
		}
		player.setDeltaMovement(Vec3.ZERO);
		player.setPos(unlinkedHold.x, unlinkedHold.y, unlinkedHold.z);
		player.xo = unlinkedHold.x;
		player.yo = unlinkedHold.y;
		player.zo = unlinkedHold.z;
		player.fallDistance = 0;
	}

	/**
	 * Hands Skyrim the raw physics tick (previous + latest feet, smoothed eye height, walk bob) with a
	 * QueryPerformanceCounter timestamp. Skyrim interpolates between them on its own frame clock,
	 * exactly like Minecraft's renderer does with partial ticks.
	 */
	private static void publishTick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!linked || player == null) {
			return;
		}
		if (qpcFreq == 0) {
			qpcFreq = SkyLink.qpcFrequency();
		}
		float tickMs = minecraft.level != null ? minecraft.level.tickRateManager().millisecondsPerTick() : 50.0F;
		// The tick really "happened" partial ticks ago (DeltaTracker keeps the remainder).
		float remainder = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
		mc.tickQpc = SkyLink.qpc() - (long) (remainder * tickMs * qpcFreq / 1000.0);
		mc.tickMs = tickMs;
		mc.prevX = player.xo;
		mc.prevY = player.yo;
		mc.prevZ = player.zo;
		mc.curX = player.getX();
		mc.curY = player.getY();
		mc.curZ = player.getZ();
		// Same smoothing as Camera.tick(): eye height eases halfway toward the target each tick.
		if (player != eyePlayer) {
			eyePlayer = player;
			eyeSmoothed = player.getEyeHeight();
		}
		mc.eyeHeightO = eyeSmoothed;
		eyeSmoothed += (player.getEyeHeight() - eyeSmoothed) * 0.5F;
		mc.eyeHeightT = eyeSmoothed;
		boolean bob = minecraft.options.bobView().get();
        mc.walkDistO = bob ? player.walkDistO : 0;
        mc.walkDist = bob ? player.walkDist : 0;
        mc.bobO = bob ? player.oBob : 0;
        mc.bob = bob ? player.bob : 0;

		SkyLink.writeMcState(mc);
	}

	/** Freeze the player until Skyrim's collision around them has arrived. */
	private static void holdUntilReady(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!linked || player == null) {
			return;
		}
		if (!sky.inGame() || sky.loading()) {
			// Skyrim is on its main menu or a loading screen: park the player where they are.
			if (holdPos == null) {
				holdPos = player.position();
			}
			teleportPending = true;
		}
		if (holdPos == null) {
			holdSince = 0;
			return;
		}
		if (holdSince == 0) {
			holdSince = System.currentTimeMillis();
		}
		int bx = (int) Math.floor(holdPos.x), by = (int) Math.floor(holdPos.y), bz = (int) Math.floor(holdPos.z);
		boolean known = SkyCollision.isKnown(bx, by - 1, bz) && SkyCollision.isKnown(bx, by, bz)
			&& SkyCollision.isKnown(bx, by - SkyCollision.REGION_SIZE, bz);
		// Release only after a measured surface exists under this exact footprint.
		// Nearby occupied cells do not prove that a gap beneath the player is safe.
		var supportBox = new net.minecraft.world.phys.AABB(holdPos.x - .29, holdPos.y - .08, holdPos.z - .29,
			holdPos.x + .29, holdPos.y + .02, holdPos.z + .29);
		boolean blockSupport = player.level().getBlockCollisions(player, supportBox).iterator().hasNext();
		boolean ready = known && (blockSupport || Double.isFinite(SkyCollider.groundAt(holdPos.x, holdPos.y, holdPos.z, 2.5)));
		if (ready && sky.inGame() && !sky.loading()) {
			// Skyrim's feet can sit a fraction of a voxel inside our ground layer. Minecraft's
			// collision never pushes you out of a shape, so you'd drop through: lift out first.
			Vec3 safe = liftOutOfGeometry(player, holdPos);
			if (safe.y != holdPos.y) {
				player.setPos(safe.x, safe.y, safe.z);
				player.yo = safe.y;
				SkyCraft.LOG.info("SkyCraft: lifted player {} blocks out of the ground", String.format("%.3f", safe.y - holdPos.y));
			}
			holdPos = null;
			return;
		}
		player.setDeltaMovement(Vec3.ZERO);
		player.setPos(holdPos.x, holdPos.y, holdPos.z);
		player.xo = holdPos.x;
		player.yo = holdPos.y;
		player.zo = holdPos.z;
		player.fallDistance = 0;
	}

	private static Vec3 liftOutOfGeometry(LocalPlayer player, Vec3 pos) {
		// Stand on the exact Skyrim ground if it is slightly above the feet (up to 2.5 blocks).
		double ground = SkyCollider.groundAt(pos.x, pos.y, pos.z, 2.5);
		Vec3 safe = !Double.isNaN(ground) && ground > pos.y ? new Vec3(pos.x, ground, pos.z) : pos;
		// Host teleports know nothing about saved MC blocks. Lift out of real MC solids
		// too, otherwise a destination inside an old wall traps the player indefinitely.
		for (int i = 0; i < 16; i++) {
			var box = player.getBoundingBox().move(safe.subtract(player.position())).deflate(1.0E-5);
			double top = safe.y;
			for (var shape : player.level().getBlockCollisions(player, box)) {
				if (!shape.isEmpty()) top = Math.max(top, shape.max(net.minecraft.core.Direction.Axis.Y) + .001);
			}
			if (top <= safe.y) break;
			safe = new Vec3(safe.x, top, safe.z);
		}
		return safe;
	}

	private static void requestTeleport(Minecraft minecraft, double x, double y, double z, float yaw, float pitch) {
		LocalPlayer player = minecraft.player;
		player.setPos(x, y, z);
		player.setDeltaMovement(Vec3.ZERO);

		player.fallDistance = 0;
		var server = minecraft.getSingleplayerServer();
		if (server != null) {
			var uuid = player.getUUID();
			server.execute(() -> {
				ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
				if (sp != null) {
					sp.stopFallFlying();
                    sp.teleportTo(x, y, z);
					sp.setYRot(yaw);
					sp.setXRot(pitch);
					sp.fallDistance = 0;
				}
			});
		} else if (dev.skycraft.platform.NeoPackets.canSend(dev.skycraft.net.SkyNet.Teleport.TYPE)) {
			// In a friend's world: their server would reject a jump this large as moving too quickly.
			dev.skycraft.platform.NeoPackets.send(new dev.skycraft.net.SkyNet.Teleport(x, y, z, yaw, pitch));
		}
		SkyCraft.LOG.info("SkyCraft: teleported to {} {} {}", x, y, z);
	}

	/** After GameRenderer.render(): report the player to Skyrim and ship the overlay frame. */
	public static void afterRender() {
		if (!linked) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		int flags = 0;
		if (player != null && minecraft.level != null) {
			float partial = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
			Vec3 feet = player.getPosition(partial);
			Camera camera = minecraft.gameRenderer.getMainCamera();
			flags |= Proto.MC_IN_WORLD;
            if (recoveryRequested) flags |= Proto.MC_RECOVER;
            if (player.getAbilities().invulnerable) flags |= Proto.MC_INVULNERABLE;
			if (player.onGround()) {
				flags |= Proto.MC_ON_GROUND;
			}
			if (player.isShiftKeyDown()) {
				flags |= Proto.MC_SNEAKING;
			}
			if (player.isSprinting()) {
				flags |= Proto.MC_SPRINTING;
			}
			if (player.isDeadOrDying()) {
				flags |= Proto.MC_DEAD;
			}
			if (player.isSwimming()) {
				flags |= Proto.MC_SWIMMING;
			}
			if (player.getAbilities().flying) {
				flags |= Proto.MC_FLYING;
			}
			if (player.isFallFlying()) flags |= Proto.MC_FALL_FLYING;
			mc.x = feet.x;
			mc.y = feet.y;
			mc.z = feet.z;
			mc.yaw = player.getYRot();
			mc.pitch = player.getXRot();
			// The eye, not the camera: in third person Minecraft's camera sits behind or in front.
			Vec3 eye = camera.isDetached() ? player.getEyePosition(partial) : camera.getPosition();
			mc.eyeHeight = (float) (eye.y - feet.y);
			mc.eyeX = eye.x;
			mc.eyeY = eye.y;
			mc.eyeZ = eye.z;
			mc.fov = minecraft.options.fov().get().floatValue();
			// Minecraft's F5 camera: Skyrim puts its camera where Minecraft's would be.
			mc.cameraMode = minecraft.options.getCameraType().ordinal();
			// A respawn packet can replace the player after beginFrame; do not remember
			// its temporary first-person camera before the next frame restores it.
			if (player == lastPlayer && !player.isDeadOrDying()) {
				lastLivingCamera = minecraft.options.getCameraType();
			}
			mc.cameraDistance = camera.isDetached() ? (float) camera.getPosition().distanceTo(player.getEyePosition(partial)) : 0.0F;
			// Walk bob, exactly what GameRenderer.bobView() uses this frame.
            boolean bob = minecraft.options.bobView().get();
            mc.bobPhase = bob ? -(player.walkDist + (player.walkDist - player.walkDistO) * partial) : 0;
            mc.bobAmount = bob ? net.minecraft.util.Mth.lerp(partial, player.oBob, player.bob) : 0;

		}
		if (minecraft.screen != null) {
			flags |= Proto.MC_SCREEN_OPEN;
		}
		mc.flags = flags;
		mc.sensitivity = minecraft.options.sensitivity().get().floatValue();
		mc.teleportAck = holdPos == null ? teleportAck : teleportAck - 1; // not "arrived" until we are released
		mc.guiScale = (int) minecraft.getWindow().getGuiScale();
		mc.frameCounter = ++frameCounter;
		SkyLink.writeMcState(mc);

		if ((flags & Proto.MC_IN_WORLD) != 0) {
			try {
				WorldExporter.frame(minecraft, minecraft.getTimer().getGameTimeDeltaPartialTick(false));
			} catch (RuntimeException e) {
				if (exporterErrors++ < 5) {
					SkyCraft.LOG.error("SkyCraft: world export failed", e);
				}
			}
			FrameExporter.capture(minecraft);
		}
	}

	/** End of the frame: render at most once per Skyrim frame instead of spinning freely. */
	public static void paceFrame() {
		if (!linked) {
			return;
		}
		if (skyrimStalled && (SkyLink.skyStateSeq() >>> 1) == lastPacedSeq) {
			return; // Skyrim is paused (menu / alt-tab): don't block every frame waiting for it
		}
		skyrimStalled = false;
		long deadline = System.nanoTime() + 25_000_000L;
		// SkyState.seq advances by 2 per Skyrim frame (odd while writing).
		while ((SkyLink.skyStateSeq() >>> 1) == lastPacedSeq && System.nanoTime() < deadline) {
			Thread.onSpinWait();
			if (deadline - System.nanoTime() > 2_000_000L) {
				Thread.yield();
			}
		}
		int seqNow = SkyLink.skyStateSeq() >>> 1;
		skyrimStalled = seqNow == lastPacedSeq;
		lastPacedSeq = seqNow;
	}

	private static void applyLinkedOptions() {
		Minecraft minecraft = Minecraft.getInstance();
		var options = minecraft.options;
		options.pauseOnLostFocus = false;

		options.enableVsync().set(false);
		options.framerateLimit().set(120);
		// Minecraft doesn't draw the world itself; these only decide how far out placed blocks,
		// arrows and Skyrim NPC stand-ins stay loaded and simulated.
		options.renderDistance().set(8);
		options.simulationDistance().set(8);
		options.autoJump().set(false);
		options.onboardAccessibility = false;
		if (options.tutorialStep != net.minecraft.client.tutorial.TutorialSteps.NONE) {
			minecraft.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
		}
		options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
		options.save();
	}

	private static void hideWindowOnce(Minecraft minecraft) {
		if (windowHidden || SHOW_WINDOW) {
			return;
		}
		windowHidden = true;
		GLFW.glfwHideWindow(minecraft.getWindow().getWindow());
		SkyCraft.LOG.info("SkyCraft: game window hidden (run with -Dskycraft.showWindow=true to keep it)");
	}

	private static void applyViewportSize(Minecraft minecraft) {
		int w = Math.min(sky.viewportW, Proto.MAX_OVERLAY_W);
		int h = Math.min(sky.viewportH, Proto.MAX_OVERLAY_H);
		if (w <= 0 || h <= 0 || (w == appliedViewportW && h == appliedViewportH)) {
			return;
		}
		appliedViewportW = w;
		appliedViewportH = h;
		// Keep roughly the previous logical HUD size while drawing every physical pixel.
		minecraft.getWindow().setWindowed(w, h);
		minecraft.options.guiScale().set(Math.max(1, Math.min(w / 640, h / 360)));
		minecraft.resizeDisplay();
		SkyCraft.LOG.info("SkyCraft: sizing overlay to Skyrim viewport {}x{}", w, h);
	}
}
