package dev.skycraft.client.render;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL32;

/** One in-flight atlas read; polling never waits for the GPU to finish. Render-thread only. */
final class TextureReadback implements AutoCloseable {
    private int buffer, bytes;
    private long fence;
    void request(int texture, int width, int height) {
        if (fence != 0) return;
        int size = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try (var state = new TextureReadState()) {
            if (buffer == 0) buffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
            if (bytes != size) {
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, size, GL15.GL_STREAM_READ);
                bytes = size;
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        } finally { GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture); }
    }
    boolean poll(ByteBuffer destination) {
        if (fence == 0) return false;
        int status = GL32.glClientWaitSync(fence, 0, 0);
        if (status == GL32.GL_WAIT_FAILED) { GL32.glDeleteSync(fence); fence = 0; return false; }
        if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) return false;
        if (destination.capacity() < bytes) throw new IllegalArgumentException("Atlas readback destination too small");
        int previousBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
            ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, bytes, null);
            if (mapped == null) return false;
            try { destination.clear(); destination.put(mapped).flip(); }
            finally { GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER); }
            return true;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousBuffer);
            GL32.glDeleteSync(fence); fence = 0;
        }
    }
    @Override public void close() {
        if (fence != 0) GL32.glDeleteSync(fence);
        if (buffer != 0) GL15.glDeleteBuffers(buffer);
        fence = 0; buffer = bytes = 0;
    }
}
