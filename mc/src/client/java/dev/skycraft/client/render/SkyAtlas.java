package dev.skycraft.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.skycraft.client.mixin.SpriteContentsAccessor;
import dev.skycraft.client.mixin.TextureAtlasAccessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Minecraft's block atlas with the item atlas stacked underneath, as one RGBA image for Skyrim.
 * Built on the CPU from each sprite's first animation frame.
 */
final class SkyAtlas {
	final int width, height;
	final ByteBuffer pixels;
	private final int blockW, blockH, itemW, itemH;
	final Object blockSprites, itemSprites; // identity of the atlases' sprite maps (changes on resource reload)

	private static final int CRACK_STAGES = 10;
	private static final int CRACK_SIZE = 16;
	// Minecraft's arrow entity textures (the top half of each 32x32 texture), after the cracks in the bottom strip.
	private static final String[] ARROW_TEXTURES = { "arrow", "arrow_tipped", "arrow_spectral" };
	private static final int ARROW_X = CRACK_STAGES * CRACK_SIZE;
	private static final int ARROW_W = 32;

	/**
	 * An animated sprite (water, lava, fire, portals, ...) where it sits in the combined atlas, with
	 * Minecraft's frame order and timing. Only its first frame is in the initial atlas; the rest
	 * are sent as they come up.
	 */
	private static final class Animation {
		NativeImage image;
		int x, y, w, h, pad;
		int[] index, time;
		int cycle, rowSize;
		boolean interpolate;
		long shown = Long.MIN_VALUE;
	}

	private final java.util.List<Animation> animations = new java.util.ArrayList<>();

	private SkyAtlas(TextureAtlas blocks, TextureAtlas items, ResourceManager resources) {
		TextureAtlasAccessor b = (TextureAtlasAccessor) blocks;
		TextureAtlasAccessor i = (TextureAtlasAccessor) items;
		this.blockW = b.skycraft$width();
		this.blockH = b.skycraft$height();
		this.itemW = i.skycraft$width();
		this.itemH = i.skycraft$height();
		this.width = Math.max(Math.max(this.blockW, this.itemW), ARROW_X + ARROW_TEXTURES.length * ARROW_W);
		this.height = this.blockH + this.itemH + CRACK_SIZE;
		this.pixels = ByteBuffer.allocateDirect(this.width * this.height * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (TextureAtlasSprite sprite : b.skycraft$sprites().values()) {
			this.copy(sprite, this.blockW, this.blockH, 0);
		}
		for (TextureAtlasSprite sprite : i.skycraft$sprites().values()) {
			this.copy(sprite, this.itemW, this.itemH, this.blockH);
		}
		this.copyCracks(resources);
		this.copyArrows(resources);
		this.blockSprites = b.skycraft$sprites();
		this.itemSprites = i.skycraft$sprites();
	}

	static SkyAtlas build(Minecraft minecraft) {
		var atlases = minecraft.getAtlasManager();
		return new SkyAtlas(atlases.getAtlasOrThrow(AtlasIds.BLOCKS), atlases.getAtlasOrThrow(AtlasIds.ITEMS), minecraft.getResourceManager());
	}

	/** Minecraft's block-breaking crack overlays (separate textures, not in the block atlas), in a strip along the bottom. */
	private void copyCracks(ResourceManager resources) {
		int y0 = this.blockH + this.itemH;
		for (int stage = 0; stage < CRACK_STAGES; stage++) {
			var id = Identifier.withDefaultNamespace("textures/block/destroy_stage_" + stage + ".png");
			var resource = resources.getResource(id);
			if (resource.isEmpty()) {
				continue;
			}
			try (var in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
				for (int y = 0; y < CRACK_SIZE; y++) {
					for (int x = 0; x < CRACK_SIZE; x++) {
						int argb = image.getPixel(x * image.getWidth() / CRACK_SIZE, y * image.getHeight() / CRACK_SIZE);
						int o = ((y0 + y) * this.width + stage * CRACK_SIZE + x) * 4;
						this.pixels.put(o, (byte) (argb >> 16));
						this.pixels.put(o + 1, (byte) (argb >> 8));
						this.pixels.put(o + 2, (byte) argb);
						this.pixels.put(o + 3, (byte) (argb >>> 24));
					}
				}
			} catch (java.io.IOException e) {
				dev.skycraft.SkyCraft.LOG.warn("SkyCraft: couldn't read {}", id, e);
			}
		}
	}

	/** The arrow entity textures' top halves (side view and fletching end), scaled to 32x16. */
	private void copyArrows(ResourceManager resources) {
		int y0 = this.blockH + this.itemH;
		for (int t = 0; t < ARROW_TEXTURES.length; t++) {
			var id = Identifier.withDefaultNamespace("textures/entity/projectiles/" + ARROW_TEXTURES[t] + ".png");
			var resource = resources.getResource(id);
			if (resource.isEmpty()) {
				continue;
			}
			try (var in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
				for (int y = 0; y < CRACK_SIZE; y++) {
					for (int x = 0; x < ARROW_W; x++) {
						int argb = image.getPixel(x * image.getWidth() / 32, y * image.getHeight() / 32);
						int o = ((y0 + y) * this.width + ARROW_X + t * ARROW_W + x) * 4;
						this.pixels.put(o, (byte) (argb >> 16));
						this.pixels.put(o + 1, (byte) (argb >> 8));
						this.pixels.put(o + 2, (byte) argb);
						this.pixels.put(o + 3, (byte) (argb >>> 24));
					}
				}
			} catch (java.io.IOException e) {
				dev.skycraft.SkyCraft.LOG.warn("SkyCraft: couldn't read {}", id, e);
			}
		}
	}

	/**
	 * Atlas rects of an arrow texture (0 plain, 1 tipped, 2 spectral) as used by Minecraft's arrow
	 * model: the side view {@code (0,0)-(16,5)}, fletching end at u0, then the back {@code (0,5)-(5,10)}.
	 */
	float[] arrowUv(int variant) {
		float x0 = ARROW_X + Math.clamp(variant, 0, ARROW_TEXTURES.length - 1) * ARROW_W, y0 = this.blockH + this.itemH;
		return new float[] {
			x0 / this.width, y0 / this.height, (x0 + 16) / this.width, (y0 + 5) / this.height,
			x0 / this.width, (y0 + 5) / this.height, (x0 + 5) / this.width, (y0 + 10) / this.height,
		};
	}

	/** Atlas rect of crack stage 0-9. */
	float[] crackUv(int stage) {
		int s = Math.clamp(stage, 0, CRACK_STAGES - 1);
		float y0 = (float) (this.blockH + this.itemH) / this.height, y1 = (float) (this.blockH + this.itemH + CRACK_SIZE) / this.height;
		return new float[] { (float) (s * CRACK_SIZE) / this.width, y0, (float) ((s + 1) * CRACK_SIZE) / this.width, y1 };
	}

	/** Atlas rect {u0, v0, u1, v1} of a sprite. */
	float[] rect(TextureAtlasSprite s) {
		return new float[] { this.u(s, s.getU0()), this.v(s, s.getV0()), this.u(s, s.getU1()), this.v(s, s.getV1()) };
	}

	/** True if the game's atlases were rebuilt (resource pack change) since this copy was made. */
	boolean stale(Minecraft minecraft) {
		var atlases = minecraft.getAtlasManager();
		return ((TextureAtlasAccessor) atlases.getAtlasOrThrow(AtlasIds.BLOCKS)).skycraft$sprites() != this.blockSprites
			|| ((TextureAtlasAccessor) atlases.getAtlasOrThrow(AtlasIds.ITEMS)).skycraft$sprites() != this.itemSprites;
	}

	/**
	 * Copies a sprite's first frame to where its UVs point: sprites sit inside a padded cell
	 * ({@code getX/getY} is the cell corner, the image starts {@code padding} in). The padding is
	 * filled with the nearest edge pixel, as Minecraft does, so mipmaps don't fade the edges.
	 */
	private void copy(TextureAtlasSprite sprite, int atlasW, int atlasH, int yOffset) {
		NativeImage image = ((SpriteContentsAccessor) sprite.contents()).skycraft$originalImage();
		int w = Math.min(sprite.contents().width(), image.getWidth());
		int h = Math.min(sprite.contents().height(), image.getHeight());
		int imageX = Math.round(sprite.getU0() * atlasW), imageY = Math.round(sprite.getV0() * atlasH);
		int pad = Math.max(0, Math.min(imageX - sprite.getX(), imageY - sprite.getY()));
		for (int y = -pad; y < h + pad; y++) {
			int ty = imageY + y + yOffset;
			if (ty < 0 || ty >= this.height) {
				continue;
			}
			int sy = Math.clamp(y, 0, h - 1);
			for (int x = -pad; x < w + pad; x++) {
				int tx = imageX + x;
				if (tx < 0 || tx >= this.width) {
					continue;
				}
				int argb = image.getPixel(Math.clamp(x, 0, w - 1), sy);
				int o = (ty * this.width + tx) * 4;
				this.pixels.put(o, (byte) (argb >> 16));
				this.pixels.put(o + 1, (byte) (argb >> 8));
				this.pixels.put(o + 2, (byte) argb);
				this.pixels.put(o + 3, (byte) (argb >>> 24));
			}
		}
		Animation animation = animationOf(sprite.contents());
		if (animation != null) {
			animation.image = image;
			animation.x = imageX;
			animation.y = imageY + yOffset;
			animation.w = w;
			animation.h = h;
			animation.pad = pad;
			this.animations.add(animation);
		}
	}

	/** Minecraft's frame list for an animated sprite (private in SpriteContents), or null. */
	private static Animation animationOf(net.minecraft.client.renderer.texture.SpriteContents contents) {
		try {
			var field = net.minecraft.client.renderer.texture.SpriteContents.class.getDeclaredField("animatedTexture");
			field.setAccessible(true);
			Object animated = field.get(contents);
			if (animated == null) {
				return null;
			}
			Class<?> type = animated.getClass();
			var framesField = type.getDeclaredField("frames");
			var rowField = type.getDeclaredField("frameRowSize");
			var interpolateField = type.getDeclaredField("interpolateFrames");
			framesField.setAccessible(true);
			rowField.setAccessible(true);
			interpolateField.setAccessible(true);
			java.util.List<?> frames = (java.util.List<?>) framesField.get(animated);
			if (frames.size() < 2) {
				return null;
			}
			Animation a = new Animation();
			a.index = new int[frames.size()];
			a.time = new int[frames.size()];
			for (int k = 0; k < frames.size(); k++) {
				Object frame = frames.get(k);
				var indexField = frame.getClass().getDeclaredField("index");
				var timeField = frame.getClass().getDeclaredField("time");
				indexField.setAccessible(true);
				timeField.setAccessible(true);
				a.index[k] = indexField.getInt(frame);
				a.time[k] = Math.max(1, timeField.getInt(frame));
				a.cycle += a.time[k];
			}
			a.rowSize = Math.max(1, rowField.getInt(animated));
			a.interpolate = interpolateField.getBoolean(animated);
			return a;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	int animatedSprites() {
		return this.animations.size();
	}

	/** A patch of the combined atlas: where, and RGBA pixels. */
	record Region(int x, int y, int w, int h, ByteBuffer pixels) {
	}

	/**
	 * Brings animated sprites to Minecraft's frame for this game tick, like its own texture
	 * animation (blending frames where the sprite interpolates). Hands each changed sprite to
	 * {@code send}; false from it means "not delivered, try again next time".
	 */
	void animate(long tick, java.util.function.Predicate<Region> send) {
		for (Animation a : this.animations) {
			long t = Math.floorMod(tick, (long) a.cycle);
			int i = 0;
			while (t >= a.time[i]) {
				t -= a.time[i];
				i++;
			}
			long key = a.interpolate ? ((long) i << 20) | t : i;
			if (key == a.shown) {
				continue;
			}
			int next = (i + 1) % a.index.length;
			float blend = a.interpolate ? (float) t / a.time[i] : 0.0F;
			int pw = a.w + 2 * a.pad, ph = a.h + 2 * a.pad;
			ByteBuffer out = ByteBuffer.allocateDirect(pw * ph * 4).order(ByteOrder.LITTLE_ENDIAN);
			int fx0 = (a.index[i] % a.rowSize) * a.w, fy0 = (a.index[i] / a.rowSize) * a.h;
			int fx1 = (a.index[next] % a.rowSize) * a.w, fy1 = (a.index[next] / a.rowSize) * a.h;
			for (int y = -a.pad; y < a.h + a.pad; y++) {
				int sy = Math.clamp(y, 0, a.h - 1);
				for (int x = -a.pad; x < a.w + a.pad; x++) {
					int sx = Math.clamp(x, 0, a.w - 1);
					int c = a.image.getPixel(fx0 + sx, fy0 + sy);
					if (blend > 0.0F) {
						c = mix(c, a.image.getPixel(fx1 + sx, fy1 + sy), blend);
					}
					out.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
				}
			}
			out.flip();
			if (send.test(new Region(a.x - a.pad, a.y - a.pad, pw, ph, out))) {
				a.shown = key;
			}
		}
	}

	private static int mix(int a, int b, float t) {
		int r = 0;
		for (int shift = 0; shift < 32; shift += 8) {
			int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
			r |= (Math.round(ca + (cb - ca) * t) & 0xFF) << shift;
		}
		return r;
	}

	/**
	 * A whole Minecraft atlas (such as the particle atlas) as one image, built from each sprite's
	 * first frame the same way as the combined atlas. Caller closes it.
	 */
	static NativeImage image(TextureAtlas atlas) {
		TextureAtlasAccessor a = (TextureAtlasAccessor) atlas;
		int w = a.skycraft$width(), h = a.skycraft$height();
		NativeImage out = new NativeImage(w, h, true);
		for (TextureAtlasSprite sprite : a.skycraft$sprites().values()) {
			NativeImage image = ((SpriteContentsAccessor) sprite.contents()).skycraft$originalImage();
			int sw = Math.min(sprite.contents().width(), image.getWidth());
			int sh = Math.min(sprite.contents().height(), image.getHeight());
			int imageX = Math.round(sprite.getU0() * w), imageY = Math.round(sprite.getV0() * h);
			int pad = Math.max(0, Math.min(imageX - sprite.getX(), imageY - sprite.getY()));
			for (int y = -pad; y < sh + pad; y++) {
				for (int x = -pad; x < sw + pad; x++) {
					int tx = imageX + x, ty = imageY + y;
					if (tx >= 0 && tx < w && ty >= 0 && ty < h) {
						out.setPixel(tx, ty, image.getPixel(Math.clamp(x, 0, sw - 1), Math.clamp(y, 0, sh - 1)));
					}
				}
			}
		}
		return out;
	}

	/** Maps a sprite-atlas UV into this combined image. */
	float u(TextureAtlasSprite sprite, float u) {
		return u * (isItem(sprite) ? this.itemW : this.blockW) / this.width;
	}

	float v(TextureAtlasSprite sprite, float v) {
		return isItem(sprite) ? (this.blockH + v * this.itemH) / this.height : v * this.blockH / this.height;
	}

	float blockU(float u) {
		return u * this.blockW / this.width;
	}

	float blockV(float v) {
		return v * this.blockH / this.height;
	}

	float itemU(float u) {
		return u * this.itemW / this.width;
	}

	float itemV(float v) {
		return (this.blockH + v * this.itemH) / this.height;
	}

	private static boolean isItem(TextureAtlasSprite sprite) {
		return sprite.atlasLocation().equals(TextureAtlas.LOCATION_ITEMS);
	}
}
