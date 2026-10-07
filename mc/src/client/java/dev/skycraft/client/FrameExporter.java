package dev.skycraft.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.lang.foreign.MemorySegment;
import net.minecraft.client.Minecraft;

/**
 * Copies Minecraft's main render target (hand + HUD + screens on a transparent background) back
 * from the GPU and publishes it to Skyrim through the overlay triple buffer.
 *
 * The copy is asynchronous: a frame is captured into one of a few staging buffers and shipped
 * once the GPU says the copy finished, typically a frame later.
 */
public final class FrameExporter {
	private static final int STAGING = 3;
	private static final int FREE = 0;
	private static final int PENDING = 1;
	private static final int READY = 2;

	private static final Staging[] staging = new Staging[STAGING];
	private static long nextFrameId = 1;
	private static boolean loggedFormat;
	private static long lastCaptureNanos;

	private static final class Staging {
		GpuBuffer buffer;
		int width;
		int height;
		volatile int state = FREE;
		long frameId;
	}

	private FrameExporter() {
	}

	public static void capture(Minecraft minecraft) {
		shipReadyFrames();
		long now = System.nanoTime();
		// Full-resolution HUD readback need not run at the host's uncapped scene rate.
		if (now - lastCaptureNanos < 33_333_333L) return;

		RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
		GpuTexture color = target.getColorTexture();
		if (color == null) {
			return;
		}
		int width = target.width;
		int height = target.height;
		if (width > Proto.MAX_OVERLAY_W || height > Proto.MAX_OVERLAY_H) {
			return;
		}
		if (!loggedFormat) {
			loggedFormat = true;
			SkyCraft.LOG.info("SkyCraft: overlay capture {}x{} format {}", width, height, color.getFormat());
		}

		Staging slot = null;
		for (Staging s : staging) {
			if (s != null && s.state == FREE) {
				slot = s;
				break;
			}
		}
		if (slot == null) {
			for (int i = 0; i < STAGING; i++) {
				if (staging[i] == null) {
					staging[i] = slot = new Staging();
					break;
				}
			}
		}
		if (slot == null) {
			return; // all staging buffers still in flight; skip this frame
		}

		long bytes = (long) width * height * 4L;
		if (slot.buffer == null || slot.width != width || slot.height != height) {
			if (slot.buffer != null) {
				slot.buffer.close();
			}
			slot.buffer = RenderSystem.getDevice().createBuffer(() -> "SkyCraft overlay readback", 9, bytes);
			slot.width = width;
			slot.height = height;
		}
		final Staging captured = slot;
		captured.state = PENDING;
		captured.frameId = nextFrameId++;
		lastCaptureNanos = now;
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(color, captured.buffer, 0L, () -> captured.state = READY, 0);
	}

	/** Maps the newest finished readback and copies it into shared memory. */
	private static void shipReadyFrames() {
		Staging newest = null;
		for (Staging s : staging) {
			if (s != null && s.state == READY && (newest == null || s.frameId > newest.frameId)) {
				newest = s;
			}
		}
		if (newest == null) {
			return;
		}
		final Staging finished=newest;
		SkyLink.withSegment(shm -> {
		if (shm != null) {
			long bytes = (long) finished.width * finished.height * 4L;
			try (GpuBufferSlice.MappedView view = finished.buffer.map(true, false)) {
				MemorySegment src = MemorySegment.ofBuffer(view.data());
				MemorySegment.copy(src, 0, shm, SkyLink.overlayBackSlotOffset(), Math.min(bytes, src.byteSize()));
			}
			SkyLink.publishOverlay(finished.width, finished.height, true, finished.frameId);
		}
		return null;
		});
		// Anything older than what we just shipped is useless now.
		for (Staging s : staging) {
			if (s != null && s.state == READY && s.frameId <= newest.frameId) {
				s.state = FREE;
			}
		}
	}
}
