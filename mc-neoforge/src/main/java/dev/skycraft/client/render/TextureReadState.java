package dev.skycraft.client.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/** Shader/mod uploads may leave pixel-pack strides or a PBO bound; CPU reads need tightly packed RGBA. */
final class TextureReadState implements AutoCloseable {
    private final int buffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
    private final int alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
    private final int rowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
    private final int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
    private final int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
    TextureReadState() {
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
    }
    @Override public void close() {
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rowLength);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
    }
}
