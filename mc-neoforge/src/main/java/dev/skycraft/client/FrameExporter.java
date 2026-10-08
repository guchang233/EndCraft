package dev.skycraft.client;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

/** Bounded OpenGL PBO readback for 1.21.1; never waits on an unfinished GPU fence. */
public final class FrameExporter {
    private static final class Slot { int buffer, width, height; long fence, frame; }
    private static final Slot[] SLOTS = {new Slot(), new Slot(), new Slot()};
    private static long nextFrame, lastCapture;
    public static void capture(Minecraft mc) {
        int previousBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            Slot newest = null;
            for (Slot s : SLOTS) if (s.fence != 0) {
                int result = GL32.glClientWaitSync(s.fence, 0, 0);
                if (result == GL32.GL_ALREADY_SIGNALED || result == GL32.GL_CONDITION_SATISFIED)
                    if (newest == null || s.frame > newest.frame) newest = s;
            }
            if (newest != null) {
                final Slot ready = newest;
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, ready.buffer);
                ByteBuffer view = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, (long) ready.width * ready.height * 4, null);
                if (view != null) {
                    try { SkyLink.withSegment(shm -> { if (shm != null) {
                        MemorySegment.copy(MemorySegment.ofBuffer(view), 0, shm, SkyLink.overlayBackSlotOffset(), view.remaining());
                        SkyLink.publishOverlay(ready.width, ready.height, true, ready.frame);
                    } return null; }); } finally { GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER); }
                }
                for (Slot s : SLOTS) if (s.fence != 0 && s.frame <= ready.frame) { GL32.glDeleteSync(s.fence); s.fence = 0; }
            }
            long now = System.nanoTime();
            if (now - lastCapture < 33_333_333) return;
            var target = mc.getMainRenderTarget();
            if (target.width > Proto.MAX_OVERLAY_W || target.height > Proto.MAX_OVERLAY_H) return;
            Slot free = null; for (Slot s : SLOTS) if (s.fence == 0) { free = s; break; }
            if (free == null) return;
            if (free.buffer == 0) free.buffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, free.buffer);
            if (free.width != target.width || free.height != target.height) {
                free.width = target.width; free.height = target.height;
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) free.width * free.height * 4, GL15.GL_STREAM_READ);
            }
            int previousFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int previousAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
            try {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                GL11.glReadPixels(0, 0, free.width, free.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            } finally {
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousAlignment);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousFramebuffer);
            }
            free.frame = ++nextFrame;
            free.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            lastCapture = now;
        } finally { GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousBuffer); }
    }
}
