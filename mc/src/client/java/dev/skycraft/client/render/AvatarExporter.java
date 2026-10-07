package dev.skycraft.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.skycraft.SkyCraft;
import dev.skycraft.client.mixin.RenderSetupAccessor;
import dev.skycraft.client.mixin.RenderTypeAccessor;
import dev.skycraft.client.mixin.TextureBindingAccessor;
import dev.skycraft.client.mixin.TextureManagerAccessor;
import dev.skycraft.combat.SkyrimActorEntity;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Pose;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's own entity and particle rendering, captured as triangles for Skyrim: the renderers
 * submit into this collector instead of a GPU buffer, and the posed, animated geometry goes over
 * with its textures. Skyrim lights it (sun, shadows, fog, weather) like the blocks.
 *
 * <p>Two captures a frame: the player's body in third person (F5), relative to the feet Skyrim's
 * camera follows so it can't drift from the camera; and everything else (lit TNT, falling
 * blocks, minecarts, boats, ..., block entities (chests, beds, signs, banners, pistons while they
 * move, ...) and all particles) relative to a block near the camera. Arrows,
 * dropped items and thrown items have their own lighter path (WorldExporter). Render thread only.
 */
final class AvatarExporter implements SubmitNodeCollector {
	// Vertex flags: cutout, full-detail texture, lit by its own faces / without a normal / blended.
	private static final int SOLID = 1 | 8 | (7 << 4);
	private static final int PARTICLE = 1 | 8;
	private static final int PARTICLE_BLENDED = 2 | 8;
	// How a batch's raw UVs map into the combined atlas (texture 0).
	private static final int UV_RAW = 0, UV_BLOCK_ATLAS = 1, UV_ITEM_ATLAS = 2;
	private static final Direction[] FACES_AND_NONE = { null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };
	private static final double SCENE_RANGE = 64.0;
	private static final int SCENE_MAX_ENTITIES = 48;
	private static final double BLOCK_ENTITY_RANGE = 48.0;
	private static final int SCENE_MAX_BLOCK_ENTITIES = 256;
	private static boolean warnedBlockEntity;

	// Textures Skyrim holds, shared by both captures.
	private static final Map<Identifier, Integer> TEXTURE_IDS = new HashMap<>();
	private static final Set<Identifier> UNUSABLE = new HashSet<>();
	private static int nextTextureId = 1;
	private static @Nullable ModelBlockRenderer movingBlocks;
	private static boolean warnedEntity;

	private static final AvatarExporter AVATAR = new AvatarExporter();
	private static final AvatarExporter SCENE = new AvatarExporter();
	private static final AvatarExporter RAGDOLL = new AvatarExporter();
	private static long nextRagdollNanos;

	private final Map<Long, Batch> batches = new HashMap<>();
	private final Capture capture = new Capture();
	private boolean shown;
	private SkyAtlas atlas;
	// Added to every position (particles and their groups come relative to the camera).
	private float offX, offY, offZ;

	private AvatarExporter() {
	}

	/** Skyrim dropped everything (new link, new world, new atlas): textures go again. */
	static void reset() {
		TEXTURE_IDS.clear();
		UNUSABLE.clear();
		nextTextureId = 1;
		AVATAR.shown = false;
		SCENE.shown = false;
		RAGDOLL.shown = false;
		nextRagdollNanos = 0;
		movingBlocks = null;
	}

	static void frame(Minecraft minecraft, SkyAtlas atlas, float partialTick) {
		AVATAR.exportAvatar(minecraft, atlas, partialTick);
		SCENE.exportScene(minecraft, atlas, partialTick);
		long now = System.nanoTime();
		if (now >= nextRagdollNanos) {
			nextRagdollNanos = now + 1_000_000_000L;
			RAGDOLL.exportRagdoll(minecraft, atlas, partialTick);
		}
	}

	// ---- the two captures -------------------------------------------------------------------------

	private void exportAvatar(Minecraft minecraft, SkyAtlas atlas, float partialTick) {
		this.atlas = atlas;
		var player = minecraft.player;
		Camera camera = minecraft.gameRenderer.mainCamera();
		if (player == null || !camera.isDetached()) {
			this.sendEmpty(Proto.REN_AVATAR, false);
			return;
		}
		this.begin();
		try {
			var dispatcher = minecraft.getEntityRenderDispatcher();
			dispatcher.prepare(camera, minecraft.crosshairPickEntity);
			EntityRenderState state = dispatcher.extractEntity(player, partialTick);
			CameraRenderState cameraState = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
			// At the origin: positions come out relative to the player's feet.
			dispatcher.submit(state, cameraState, 0.0, 0.0, 0.0, new PoseStack(), this);
		} catch (RuntimeException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't capture the player model", e);
			return;
		}
		this.send(Proto.REN_AVATAR, null);
	}

	/**
	 * The player's body standing still, facing +Z, feet at the origin, split into Minecraft's six
	 * parts, for Skyrim to hang on its ragdoll when the player dies. Kept up to date while alive
	 * (skin and armour change), so the last one before death is the one that falls.
	 */
	private void exportRagdoll(Minecraft minecraft, SkyAtlas atlas, float partialTick) {
		this.atlas = atlas;
		var player = minecraft.player;
		if (player == null || player.isDeadOrDying() || player.isSpectator()) {
			return;
		}
		this.begin();
		try {
			var dispatcher = minecraft.getEntityRenderDispatcher();
			Camera camera = minecraft.gameRenderer.mainCamera();
			dispatcher.prepare(camera, minecraft.crosshairPickEntity);
			EntityRenderState state = dispatcher.extractEntity(player, partialTick);
			state.ageInTicks = 0.0F;
			state.nameTag = null;
			if (state instanceof LivingEntityRenderState living) {
				living.bodyRot = living.yRot = living.xRot = 0.0F;
				living.deathTime = 0.0F;
				living.walkAnimationPos = living.walkAnimationSpeed = 0.0F;
				living.hasRedOverlay = false;
				living.isAutoSpinAttack = false;
				living.pose = Pose.STANDING;
			}
			if (state instanceof ArmedEntityRenderState armed) {
				// Everything in hand drops when a Minecraft player dies.
				armed.rightHandItemState.clear();
				armed.leftHandItemState.clear();
				armed.currentSwing = null;
				armed.swingAnimation = 0.0F;
			}
			if (state instanceof HumanoidRenderState humanoid) {
				humanoid.swimAmount = 0.0F;
				humanoid.isCrouching = humanoid.isFallFlying = humanoid.isVisuallySwimming = humanoid.isPassenger = humanoid.isUsingItem = false;
			}
			if (state instanceof AvatarRenderState avatar) {
				avatar.arrowCount = avatar.stingerCount = 0;
				avatar.parrotOnLeftShoulder = avatar.parrotOnRightShoulder = null;
			}
			CameraRenderState cameraState = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
			dispatcher.submit(state, cameraState, 0.0, 0.0, 0.0, new PoseStack(), this);
		} catch (RuntimeException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't capture the player's body for the ragdoll", e);
			return;
		}
		this.sendParts(Proto.REN_RAGDOLL);
	}

	/** Which of Minecraft's six parts a point of the standing, +Z-facing body belongs to. */
	private static int partAt(float x, float y) {
		if (y >= 1.5F) {
			return Proto.PART_HEAD;
		}
		if (y >= 0.75F) {
			// The arms hang outside the body's 8-pixel width; its right side is -X facing +Z.
			return x < -0.255F ? Proto.PART_RIGHT_ARM : x > 0.255F ? Proto.PART_LEFT_ARM : Proto.PART_BODY;
		}
		return x < 0.0F ? Proto.PART_RIGHT_LEG : Proto.PART_LEFT_LEG;
	}

	/** Like send, with each batch split by the part its quads belong to (RenBatch flags bits 8-11). */
	private void sendParts(int message) {
		this.capture.flush();
		record Group(Batch batch, int part, int[] quads, int count) {
		}
		List<Group> groups = new ArrayList<>();
		int vertices = 0;
		for (Batch b : this.batches.values()) {
			int quadCount = b.count / 4;
			if (quadCount == 0) {
				continue;
			}
			int[][] byPart = new int[7][quadCount];
			int[] counts = new int[7];
			for (int q = 0; q < quadCount; q++) {
				float cx = 0.0F, cy = 0.0F;
				for (int k = 0; k < 4; k++) {
					int o = (q * 4 + k) * 8;
					cx += Float.intBitsToFloat(b.data[o]);
					cy += Float.intBitsToFloat(b.data[o + 1]);
				}
				int part = partAt(cx * 0.25F, cy * 0.25F);
				byPart[part][counts[part]++] = q;
			}
			for (int part = 1; part < 7; part++) {
				if (counts[part] > 0) {
					groups.add(new Group(b, part, byPart[part], counts[part]));
					vertices += counts[part] * 6;
				}
			}
		}
		if (groups.isEmpty()) {
			return;
		}
		ByteBuffer header = ByteBuffer.allocate(8 + groups.size() * 16).order(ByteOrder.LITTLE_ENDIAN);
		header.putInt(groups.size()).putInt(vertices);
		ByteBuffer body = ByteBuffer.allocateDirect(vertices * Proto.REN_VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
		int first = 0;
		for (Group g : groups) {
			int count = g.count() * 6;
			header.putInt(g.batch().texture).putInt(first).putInt(count).putInt((g.batch().translucent ? 1 : 0) | g.part() << 8);
			for (int i = 0; i < g.count(); i++) {
				g.batch().writeQuad(body, g.quads()[i]);
			}
			first += count;
		}
		header.flip();
		body.flip();
		if (SkyLink.tryWriteRender(message, header, body)) {
			this.shown = true;
		}
	}

	private void exportScene(Minecraft minecraft, SkyAtlas atlas, float partialTick) {
		this.atlas = atlas;
		var level = minecraft.level;
		var player = minecraft.player;
		if (level == null || player == null) {
			return;
		}
		Camera camera = minecraft.gameRenderer.mainCamera();
		Vec3 cam = camera.position();
		double[] origin = { Math.floor(cam.x), Math.floor(cam.y), Math.floor(cam.z) };
		this.begin();
		var dispatcher = minecraft.getEntityRenderDispatcher();
		dispatcher.prepare(camera, minecraft.crosshairPickEntity);
		CameraRenderState cameraState = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		PoseStack pose = new PoseStack();
		int entities = 0;
		for (Entity e : level.entitiesForRendering()) {
			if (e == player || e instanceof ItemEntity || e instanceof AbstractArrow || e instanceof ItemSupplier || e instanceof SkyrimActorEntity
				|| e.distanceToSqr(cam) > SCENE_RANGE * SCENE_RANGE || entities >= SCENE_MAX_ENTITIES) {
				continue;
			}
			entities++;
			try {
				EntityRenderState state = dispatcher.extractEntity(e, partialTick);
				dispatcher.submit(state, cameraState, state.x - origin[0], state.y - origin[1], state.z - origin[2], pose, this);
			} catch (RuntimeException ex) {
				if (!warnedEntity) {
					warnedEntity = true;
					SkyCraft.LOG.warn("SkyCraft: couldn't capture {} for Skyrim", e, ex);
				}
			}
		}
		submitBlockEntities(minecraft, level, cam, origin, partialTick, cameraState, pose);
		// Particles were extracted this frame relative to Minecraft's camera.
		this.capture.flush();
		this.offX = (float) (cam.x - origin[0]);
		this.offY = (float) (cam.y - origin[1]);
		this.offZ = (float) (cam.z - origin[2]);
		try {
			for (var group : minecraft.gameRenderer.gameRenderState().levelRenderState.particlesRenderState.particles) {
				group.submit(this, cameraState);
			}
		} catch (RuntimeException ex) {
			SkyCraft.LOG.warn("SkyCraft: couldn't capture particles for Skyrim", ex);
		}
		this.capture.flush();
		this.offX = this.offY = this.offZ = 0.0F;
		this.send(Proto.REN_SCENE, origin);
	}

	/**
	 * Blocks Minecraft draws with their own renderer rather than as block models, so they aren't in
	 * the section meshes: chests, beds, signs, banners, shulker boxes, heads, bells, lecterns, pots,
	 * campfire items, spawners, and blocks being pushed by a piston (a moving block entity until
	 * the push ends). Minecraft only draws the ones in its visible sections; its world isn't drawn
	 * here, so they're taken straight from the loaded chunks around the camera.
	 */
	private void submitBlockEntities(Minecraft minecraft, net.minecraft.client.multiplayer.ClientLevel level, Vec3 cam, double[] origin, float partialTick,
		CameraRenderState cameraState, PoseStack pose) {
		var dispatcher = minecraft.getBlockEntityRenderDispatcher();
		dispatcher.prepare(cam);
		double range2 = BLOCK_ENTITY_RANGE * BLOCK_ENTITY_RANGE;
		int count = 0;
		int cx0 = (int) Math.floor((cam.x - BLOCK_ENTITY_RANGE) / 16.0), cx1 = (int) Math.floor((cam.x + BLOCK_ENTITY_RANGE) / 16.0);
		int cz0 = (int) Math.floor((cam.z - BLOCK_ENTITY_RANGE) / 16.0), cz1 = (int) Math.floor((cam.z + BLOCK_ENTITY_RANGE) / 16.0);
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				var chunk = level.getChunkSource().getChunk(cx, cz, false);
				if (chunk == null) {
					continue;
				}
				for (var blockEntity : chunk.getBlockEntities().values()) {
					var pos = blockEntity.getBlockPos();
					if (blockEntity.isRemoved() || pos.distToCenterSqr(cam) > range2 || count >= SCENE_MAX_BLOCK_ENTITIES) {
						continue;
					}
					try {
						// Renderers that draw off screen (beacon beams) only answer to the global pass.
						net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState state = dispatcher.tryExtractRenderState(blockEntity, partialTick, null, false);
						if (state == null) {
							state = dispatcher.tryExtractRenderState(blockEntity, partialTick, null, true);
						}
						if (state == null) {
							continue;
						}
						count++;
						pose.pushPose();
						pose.translate(pos.getX() - origin[0], pos.getY() - origin[1], pos.getZ() - origin[2]);
						dispatcher.submit(state, pose, this, cameraState);
						pose.popPose();
					} catch (RuntimeException ex) {
						if (!warnedBlockEntity) {
							warnedBlockEntity = true;
							SkyCraft.LOG.warn("SkyCraft: couldn't capture {} at {} for Skyrim", blockEntity.getType(), pos, ex);
						}
					}
				}
			}
		}
	}

	private void begin() {
		for (Batch b : this.batches.values()) {
			b.clear();
		}
	}

	/** Header: [origin (3 doubles), scene only] batchCount, vertexCount, then batches; body: triangles. */
	private void send(int message, double @Nullable [] origin) {
		this.capture.flush();
		List<Batch> used = new ArrayList<>();
		int vertices = 0;
		for (Batch b : this.batches.values()) {
			if (b.count >= 4) {
				used.add(b);
				vertices += b.count / 4 * 6;
			}
		}
		if (used.isEmpty()) {
			this.sendEmpty(message, origin != null);
			return;
		}
		ByteBuffer header = ByteBuffer.allocate((origin != null ? 24 : 0) + 8 + used.size() * 16).order(ByteOrder.LITTLE_ENDIAN);
		if (origin != null) {
			header.putDouble(origin[0]).putDouble(origin[1]).putDouble(origin[2]);
		}
		header.putInt(used.size()).putInt(vertices);
		ByteBuffer body = ByteBuffer.allocateDirect(vertices * Proto.REN_VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
		int first = 0;
		for (Batch b : used) {
			int count = b.count / 4 * 6;
			header.putInt(b.texture).putInt(first).putInt(count).putInt(b.translucent ? 1 : 0);
			b.writeTriangles(body);
			first += count;
		}
		header.flip();
		body.flip();
		if (SkyLink.tryWriteRender(message, header, body)) {
			this.shown = true;
		}
	}

	private void sendEmpty(int message, boolean withOrigin) {
		if (!this.shown) {
			return;
		}
		ByteBuffer header = ByteBuffer.allocate((withOrigin ? 24 : 0) + 8).order(ByteOrder.LITTLE_ENDIAN);
		if (withOrigin) {
			header.putDouble(0).putDouble(0).putDouble(0);
		}
		header.putInt(0).putInt(0).flip();
		this.shown = !SkyLink.writeRender(message, header, null);
	}

	// ---- batches ---------------------------------------------------------------------------------

	private Batch batch(int texture, int uvMode, int flags) {
		long key = ((long) texture << 16) | ((long) uvMode << 8) | flags;
		return this.batches.computeIfAbsent(key, k -> new Batch(texture, uvMode, flags));
	}

	/** Triangles for one texture and one kind of surface. Vertices arrive as quads. */
	private final class Batch {
		final int texture;
		final int uvMode;
		final int flags;
		final boolean translucent;
		int[] data = new int[8 * 256];
		int count;

		Batch(int texture, int uvMode, int flags) {
			this.texture = texture;
			this.uvMode = uvMode;
			this.flags = flags;
			this.translucent = (flags & 2) != 0;
		}

		void clear() {
			this.count = 0;
		}

		void add(float x, float y, float z, float u, float v, int argb, int light, int overlay) {
			if ((this.count + 1) * 8 > this.data.length) {
				this.data = java.util.Arrays.copyOf(this.data, this.data.length * 2);
			}
			if (this.uvMode == UV_BLOCK_ATLAS) {
				u = AvatarExporter.this.atlas.blockU(u);
				v = AvatarExporter.this.atlas.blockV(v);
			} else if (this.uvMode == UV_ITEM_ATLAS) {
				u = AvatarExporter.this.atlas.itemU(u);
				v = AvatarExporter.this.atlas.itemV(v);
			}
			// Minecraft's red "hurt" flash is an overlay texture: tint instead. Its white flash
			// (lit TNT about to go) glows instead.
			if (((overlay >>> 16) & 0xFFFF) < 8) {
				int r = (argb >> 16) & 0xFF, g = (int) (((argb >> 8) & 0xFF) * 0.55F), b = (int) ((argb & 0xFF) * 0.55F);
				argb = (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
			}
			if ((overlay & 0xFFFF) >= 8) {
				light = 0xF000F0;
			}
			int o = this.count * 8;
			this.data[o] = Float.floatToRawIntBits(x + AvatarExporter.this.offX);
			this.data[o + 1] = Float.floatToRawIntBits(y + AvatarExporter.this.offY);
			this.data[o + 2] = Float.floatToRawIntBits(z + AvatarExporter.this.offZ);
			this.data[o + 3] = Float.floatToRawIntBits(u);
			this.data[o + 4] = Float.floatToRawIntBits(v);
			this.data[o + 5] = argb;
			this.data[o + 6] = ((light >> 4) & 0xF) | (((light >> 20) & 0xF) << 8);
			this.data[o + 7] = this.flags;
			this.count++;
		}

		void writeTriangles(ByteBuffer out) {
			for (int q = 0; q + 4 <= this.count; q += 4) {
				this.writeQuad(out, q / 4);
			}
		}

		void writeQuad(ByteBuffer out, int quad) {
			for (int k : new int[] { 0, 1, 2, 0, 2, 3 }) {
				int o = (quad * 4 + k) * 8;
				out.putInt(this.data[o]).putInt(this.data[o + 1]).putInt(this.data[o + 2]).putInt(this.data[o + 3]).putInt(this.data[o + 4]);
				int argb = this.data[o + 5];
				out.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
				out.putInt(this.data[o + 6]).putInt(this.data[o + 7]);
			}
		}
	}

	/** A VertexConsumer that records into the current batch (models call addVertex + setters). */
	private final class Capture implements VertexConsumer {
		private Batch batch;
		private boolean pending;
		private float x, y, z, u, v;
		private int color, light, overlay;

		void begin(Batch b) {
			this.flush();
			this.batch = b;
		}

		void flush() {
			if (this.pending && this.batch != null) {
				this.batch.add(this.x, this.y, this.z, this.u, this.v, this.color, this.light, this.overlay);
			}
			this.pending = false;
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			this.flush();
			this.x = x;
			this.y = y;
			this.z = z;
			this.color = -1;
			this.light = 0xF000F0;
			this.overlay = OverlayTexture.NO_OVERLAY;
			this.pending = true;
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			this.color = (a << 24) | (r << 16) | (g << 8) | b;
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			this.color = color;
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			this.u = u;
			this.v = v;
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			this.overlay = (u & 0xFFFF) | (v << 16);
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			this.light = (u & 0xFFFF) | (v << 16);
			return this;
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this;
		}
	}

	// ---- textures --------------------------------------------------------------------------------

	/** The batch for a render type's texture, sending the texture to Skyrim the first time. Null: can't show it. */
	private @Nullable Batch batchFor(RenderType renderType) {
		RenderTypeAccessor type = (RenderTypeAccessor) renderType;
		String name = type.skycraft$name();
		if (name.contains("glint") || name.contains("outline") || name.contains("shadow")) {
			return null;
		}
		Object binding = ((RenderSetupAccessor) (Object) type.skycraft$state()).skycraft$textures().get("Sampler0");
		if (binding == null) {
			return null;
		}
		Identifier texture = ((TextureBindingAccessor) binding).skycraft$location();
		if (texture.equals(TextureAtlas.LOCATION_BLOCKS)) {
			return this.batch(0, UV_BLOCK_ATLAS, SOLID);
		}
		if (texture.equals(TextureAtlas.LOCATION_ITEMS)) {
			return this.batch(0, UV_ITEM_ATLAS, SOLID);
		}
		int id = textureId(texture);
		return id < 0 ? null : this.batch(id, UV_RAW, SOLID);
	}

	private static int textureId(Identifier texture) {
		Integer known = TEXTURE_IDS.get(texture);
		if (known != null) {
			return known;
		}
		if (UNUSABLE.contains(texture)) {
			return -1;
		}
		NativeImage image = readTexture(texture);
		if (image == null) {
			UNUSABLE.add(texture);
			SkyCraft.LOG.info("SkyCraft: texture {} isn't available to Skyrim; what uses it is left out", texture);
			return -1;
		}
		int id = nextTextureId++;
		try (image) {
			int w = image.getWidth(), h = image.getHeight();
			ByteBuffer pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int argb = image.getPixel(x, y);
					pixels.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
				}
			}
			pixels.flip();
			ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(id).putInt(w).putInt(h).putInt(0).flip();
			if (!SkyLink.writeRender(Proto.REN_TEXTURE, header, pixels)) {
				nextTextureId--;
				return -1;
			}
		}
		TEXTURE_IDS.put(texture, id);
		SkyCraft.LOG.info("SkyCraft: sent texture {} to Skyrim (id {})", texture, id);
		return id;
	}

	/** A texture's pixels: resource packs, a runtime texture (downloaded skins), or an atlas. Caller closes it. */
	private static @Nullable NativeImage readTexture(Identifier texture) {
		Minecraft minecraft = Minecraft.getInstance();
		var resource = minecraft.getResourceManager().getResource(texture);
		if (resource.isPresent()) {
			try (var in = resource.get().open()) {
				return NativeImage.read(in);
			} catch (java.io.IOException e) {
				SkyCraft.LOG.warn("SkyCraft: couldn't read {}", texture, e);
				return null;
			}
		}
		var registered = ((TextureManagerAccessor) minecraft.getTextureManager()).skycraft$byPath().get(texture);
		if (registered instanceof DynamicTexture dynamic && dynamic.getPixels() != null) {
			NativeImage copy = new NativeImage(dynamic.getPixels().getWidth(), dynamic.getPixels().getHeight(), false);
			copy.copyFrom(dynamic.getPixels());
			return copy;
		}
		if (registered instanceof TextureAtlas atlas) {
			return SkyAtlas.image(atlas);
		}
		return null;
	}

	// ---- geometry --------------------------------------------------------------------------------

	private void addQuad(Batch batch, Matrix4f pose, BakedQuad quad, int[] tintLayers, int lightCoords, int overlayCoords) {
		var material = quad.materialInfo();
		int layer = material.isTinted() ? material.tintIndex() : -1;
		int color = layer >= 0 && layer < tintLayers.length ? tintLayers[layer] : -1;
		var sprite = material.sprite();
		Vector3f p = new Vector3f();
		for (int k = 0; k < 4; k++) {
			pose.transformPosition(quad.position(k), p);
			long uv = quad.packedUV(k);
			batch.add(p.x(), p.y(), p.z(), this.atlas.u(sprite, UVPair.unpackU(uv)), this.atlas.v(sprite, UVPair.unpackV(uv)), color, lightCoords, overlayCoords);
		}
	}

	// ---- SubmitNodeCollector: models, items, blocks and particles are kept, the rest skipped -------

	@Override
	public OrderedSubmitNodeCollector order(int order) {
		return this;
	}

	@Override
	public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int lightCoords, int overlayCoords,
		int tintedColor, @Nullable UvMapping uvMapping, int outlineColor) {
		Batch batch = this.batchFor(renderType);
		if (batch == null) {
			return;
		}
		this.capture.begin(batch);
		VertexConsumer buffer = uvMapping != null ? uvMapping.wrap(this.capture) : this.capture;
		model.setupAnim(state);
		model.renderToBuffer(poseStack, buffer, lightCoords, overlayCoords, tintedColor);
		this.capture.flush();
	}

	@Override
	public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords, int overlayCoords, int outlineColor, int[] tintLayers,
		ItemQuads quads, ItemStackRenderState.FoilType foilType) {
		this.capture.flush();
		Batch batch = this.batch(0, UV_RAW, SOLID);
		Matrix4f pose = poseStack.last().pose();
		for (BakedQuad quad : quads.all()) {
			this.addQuad(batch, pose, quad, tintLayers, lightCoords, overlayCoords);
		}
	}

	/** Blocks drawn as entities: lit TNT, TNT minecarts, blocks carried by endermen, ... */
	@Override
	public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts, int[] tintLayers, int lightCoords, int overlayCoords,
		int outlineColor) {
		this.capture.flush();
		Batch batch = this.batch(0, UV_RAW, SOLID);
		Matrix4f pose = poseStack.last().pose();
		for (BlockStateModelPart part : parts) {
			for (Direction face : FACES_AND_NONE) {
				for (BakedQuad quad : part.getQuads(face)) {
					this.addQuad(batch, pose, quad, tintLayers, lightCoords, overlayCoords);
				}
			}
		}
	}

	// Fabric's renderer API routes block models and items through its own variants (which also
	// carry a Fabric mesh): lit TNT, held items, blocks held by endermen all come this way.

	@Override
	public void submitBlockModel(PoseStack poseStack, java.util.function.Function<ChunkSectionLayer, RenderType> renderTypes, boolean translucentLayer,
		List<BlockStateModelPart> parts, net.fabricmc.fabric.api.client.renderer.v1.mesh.@Nullable Mesh mesh, int[] tintLayers, int lightCoords, int overlayCoords,
		int outlineColor) {
		this.submitBlockModel(poseStack, (RenderType) null, parts, tintLayers, lightCoords, overlayCoords, outlineColor);
		this.addMesh(poseStack.last().pose(), mesh, lightCoords, overlayCoords);
	}

	@Override
	public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords, int overlayCoords, int outlineColor, int[] tintLayers,
		ItemQuads quads, @Nullable MeshView mesh, ItemStackRenderState.FoilType foilType) {
		this.submitItem(poseStack, displayContext, lightCoords, overlayCoords, outlineColor, tintLayers, quads, foilType);
		this.addMesh(poseStack.last().pose(), mesh, lightCoords, overlayCoords);
	}

	@Override
	public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, net.fabricmc.fabric.api.client.renderer.v1.mesh.@Nullable Mesh mesh,
		int progress, boolean isBlockTranslucent) {
	}

	/** A Fabric mesh's quads (atlas UVs, per-vertex colour and light). */
	private void addMesh(Matrix4f pose, @Nullable MeshView mesh, int lightCoords, int overlayCoords) {
		if (mesh == null || mesh.size() == 0) {
			return;
		}
		Vector3f p = new Vector3f();
		mesh.forEach(quad -> {
			Batch batch = this.batch(0, quad.atlas() == QuadAtlas.ITEM ? UV_ITEM_ATLAS : UV_BLOCK_ATLAS, SOLID);
			for (int k = 0; k < 4; k++) {
				pose.transformPosition(quad.x(k), quad.y(k), quad.z(k), p);
				int light = Math.max(quad.lightmap(k), lightCoords);
				batch.add(p.x(), p.y(), p.z(), quad.u(k), quad.v(k), quad.color(k), light, overlayCoords);
			}
		});
	}

	/** Falling sand, gravel, anvils, concrete powder: Minecraft's block renderer, posed. */
	@Override
	public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state, int outlineColor) {
		this.capture.flush();
		Minecraft minecraft = Minecraft.getInstance();
		if (movingBlocks == null) {
			movingBlocks = new ModelBlockRenderer(false, true, minecraft.getBlockColors());
		}
		var model = minecraft.getModelManager().getBlockStateModelSet().get(state.blockState);
		Batch batch = this.batch(0, UV_RAW, SOLID);
		Matrix4f pose = new Matrix4f(poseStack.last().pose());
		Vector3f p = new Vector3f();
		movingBlocks.tesselateBlock((float x, float y, float z, BakedQuad quad, QuadInstance instance) -> {
			var sprite = quad.materialInfo().sprite();
			int emission = quad.materialInfo().lightEmission();
			for (int k = 0; k < 4; k++) {
				var q = quad.position(k);
				pose.transformPosition(q.x() + x, q.y() + y, q.z() + z, p);
				long uv = quad.packedUV(k);
				batch.add(p.x(), p.y(), p.z(), this.atlas.u(sprite, UVPair.unpackU(uv)), this.atlas.v(sprite, UVPair.unpackV(uv)), instance.getColor(k),
					instance.getLightCoordsWithEmission(k, emission), OverlayTexture.NO_OVERLAY);
			}
		}, 0.0F, 0.0F, 0.0F, state, state.blockPos, state.blockState, model, state.blockState.getSeed(state.randomSeedPos));
	}

	/** Particles: smoke, explosions, block debris, crits, ... in their atlas (particles, blocks or items). */
	@Override
	public void submitQuadParticleGroup(QuadParticleRenderState particles) {
		this.capture.flush();
		for (var layer : particles.layers()) {
			Identifier texture = layer.textureAtlasLocation();
			int flags = layer.translucent() ? PARTICLE_BLENDED : PARTICLE;
			Batch batch;
			if (texture.equals(TextureAtlas.LOCATION_BLOCKS)) {
				batch = this.batch(0, UV_BLOCK_ATLAS, flags);
			} else if (texture.equals(TextureAtlas.LOCATION_ITEMS)) {
				batch = this.batch(0, UV_ITEM_ATLAS, flags);
			} else {
				int id = textureId(texture);
				if (id < 0) {
					continue;
				}
				batch = this.batch(id, UV_RAW, flags);
			}
			this.capture.begin(batch);
			particles.buildLayer(layer, this.capture);
			this.capture.flush();
		}
	}

	@Override
	public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
	}

	@Override
	public void submitNameTag(PoseStack poseStack, @Nullable Vec3 nameTagAttachment, int offset, Component name, boolean seeThrough, int lightCoords,
		CameraRenderState camera) {
	}

	@Override
	public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow, Font.DisplayMode displayMode, int lightCoords,
		int color, int backgroundColor, int outlineColor) {
	}

	@Override
	public void submitTextBackground(PoseStack poseStack, float x0, float y0, float x1, float y1, int color, Font.DisplayMode displayMode, int lightCoords) {
	}

	@Override
	public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
	}

	@Override
	public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
	}

	@Override
	public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int lightCoords, int overlayCoords,
		int tintedColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
	}

	@Override
	public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress, boolean isBlockTranslucent) {
	}

	@Override
	public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color, float width, boolean afterTerrain) {
	}

	@Override
	public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
	}

	@Override
	public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
	}
}
