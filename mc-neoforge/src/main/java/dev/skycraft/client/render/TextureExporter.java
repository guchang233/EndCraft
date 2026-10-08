package dev.skycraft.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;

final class TextureExporter {
    private static final Map<ResourceLocation, Integer> IDS = new HashMap<>();
    private static int nextId = 1;
    private static int atlasGl = -1, atlasW, atlasH;
    private static ByteBuffer atlasPixels;
    private static long nextAnimation;
    static void reset() { IDS.clear(); nextId = 1; atlasGl = -1; atlasPixels = null; }
    static int texture(ResourceLocation location) {
        if (location.equals(TextureAtlas.LOCATION_BLOCKS)) return 0;
        Integer cached = IDS.get(location); if (cached != null) return cached;
        var mc = Minecraft.getInstance();
        int id = nextId;
        var texture = mc.getTextureManager().getTexture(location);
        ByteBuffer pixels;
        int width, height;
        int old = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getId());
            width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return -1;
            pixels = ByteBuffer.allocateDirect(width * height * 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        } finally { GL11.glBindTexture(GL11.GL_TEXTURE_2D, old); }
        var header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(id).putInt(width).putInt(height).putInt(0).flip();
        if (!SkyLink.writeRender(Proto.REN_TEXTURE, header, pixels)) return -1;
        IDS.put(location, id); nextId++; return id;
    }
    static boolean atlas(Minecraft mc) {
        var texture = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        int old = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getId());
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0 || (long) width * height * 4 > Proto.RENDER_RING_BYTES / 2) return false;
            if (atlasGl == texture.getId() && width == atlasW && height == atlasH) return true;
            ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            var header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(width).putInt(height).flip();
            if (!SkyLink.writeRender(Proto.REN_ATLAS, header, pixels)) return false;
            atlasGl = texture.getId(); atlasW = width; atlasH = height; atlasPixels = pixels; return true;
        } finally { GL11.glBindTexture(GL11.GL_TEXTURE_2D, old); }
    }
    /** Bounded dirty tiles, so a backed-up render ring is retried rather than marked delivered. */
    static void animate() {
        if (atlasPixels == null || System.nanoTime() < nextAnimation) return;
        nextAnimation = System.nanoTime() + 200_000_000;
        ByteBuffer current = ByteBuffer.allocateDirect(atlasPixels.capacity());
        int old = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try { GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasGl); GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, current); }
        finally { GL11.glBindTexture(GL11.GL_TEXTURE_2D, old); }
        int sent = 0;
        for (int y = 0; y < atlasH && sent < 32; y += 32) for (int x = 0; x < atlasW && sent < 32; x += 32) {
            int w = Math.min(32, atlasW - x), h = Math.min(32, atlasH - y); boolean changed = false;
            for (int row = 0; row < h && !changed; row++) for (int col = 0; col < w * 4; col++) {
                int o = ((y + row) * atlasW + x) * 4 + col;
                if (current.get(o) != atlasPixels.get(o)) { changed = true; break; }
            }
            if (!changed) continue;
            ByteBuffer pixels = ByteBuffer.allocateDirect(w * h * 4);
            for (int row = 0; row < h; row++) pixels.put(current.slice(((y + row) * atlasW + x) * 4, w * 4));
            var header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(x).putInt(y).putInt(w).putInt(h).flip();
            if (!SkyLink.tryWriteRender(Proto.REN_ATLAS_REGION, header, pixels.flip())) return;
            for (int row = 0; row < h; row++) atlasPixels.put(((y + row) * atlasW + x) * 4, current, ((y + row) * atlasW + x) * 4, w * 4);
            sent++;
        }
    }
}
