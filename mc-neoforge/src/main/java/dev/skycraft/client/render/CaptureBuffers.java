package dev.skycraft.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

final class CaptureBuffers implements MultiBufferSource {
    private record Key(int texture, boolean blended, com.mojang.blaze3d.vertex.VertexFormat.Mode mode) { }
    private final Map<Key, CapturedMesh> batches = new LinkedHashMap<>();
    UnaryOperator<Vec3> transform = CapturedMesh.IDENTITY;
    private ByteBuffer transfer = ByteBuffer.allocateDirect(1024).order(ByteOrder.LITTLE_ENDIAN);
    private final VertexConsumer discarded = new VertexConsumer() {
        public VertexConsumer addVertex(float x, float y, float z) { return this; }
        public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        public VertexConsumer setUv(float u, float v) { return this; }
        public VertexConsumer setUv1(int u, int v) { return this; }
        public VertexConsumer setUv2(int u, int v) { return this; }
        public VertexConsumer setNormal(float x, float y, float z) { return this; }
    };
    private final CapturedMesh discardedLines = new CapturedMesh();
    void reset() {
        batches.values().forEach(CapturedMesh::reset);
        discardedLines.reset(); transform = CapturedMesh.IDENTITY;
    }
    @Override public VertexConsumer getBuffer(RenderType type) {
        // Untextured text backgrounds cannot be represented as an atlas mesh.
        if (name(type).startsWith("text_background")) return discarded;
        if (type.mode() == com.mojang.blaze3d.vertex.VertexFormat.Mode.LINES) return lines(); // F3+B hitboxes
        ResourceLocation location = textureLocation(type);
        if (location == null || type.mode() != com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS
                && type.mode() != com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES) {
            unsupported(type); return discarded;
        }
        return textured(location, blended(type), type.mode());
    }
    VertexConsumer textured(ResourceLocation location, boolean blended) {
        return textured(location, blended, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
    }
    private VertexConsumer textured(ResourceLocation location, boolean blended, com.mojang.blaze3d.vertex.VertexFormat.Mode mode) {
        int id = TextureExporter.texture(location); if (id < 0) return discarded;
        var key = new Key(id, blended, mode);
        if (!batches.containsKey(key) && batches.size() >= 64) return discarded;
        var mesh = batches.computeIfAbsent(key, k -> new CapturedMesh(mode));
        mesh.finish(); mesh.transform = transform; mesh.flags = 8 | (blended ? 2 : 1); return mesh;
    }
    /** Lines drawn as thin opaque prisms in the block-atlas batch (LineCapture). */
    LineCapture lines() {
        return lines(LineCapture.HITBOX_HALF_WIDTH);
    }
    LineCapture lines(float halfWidth) {
        int id = TextureExporter.texture(TextureAtlas.LOCATION_BLOCKS);
        var key = new Key(id, false, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
        if (id < 0 || !batches.containsKey(key) && batches.size() >= 64) return new LineCapture(discardedLines, halfWidth);
        var mesh = batches.computeIfAbsent(key, k -> new CapturedMesh());
        mesh.finish(); mesh.transform = transform; mesh.flags = 8 | 1;
        return new LineCapture(mesh, halfWidth);
    }
    private static final Map<RenderType, Boolean> BLENDED = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Blended batches are drawn without depth writes, so only types that really blend may be marked.
     * Decided by the type's transparency shard (every description names its texture, so text
     * matching on "text" or "alpha" marks solid blocks blended and breaks occlusion).
     */
    static boolean blended(RenderType type) {
        return BLENDED.computeIfAbsent(type, t -> {
            // Player and mob skins use entity_translucent only so a skin's outer layer can have
            // holes; their pixels are opaque or clear. Drawn as blended they lose depth and body
            // parts cover each other in draw order. Translucent blocks (block atlas) still blend.
            if (name(t).startsWith("entity_translucent") && !TextureAtlas.LOCATION_BLOCKS.equals(textureLocation(t))) return false;
            Object shard = compositeShard(t, "transparencyState");
            String name = shard != null ? shard.toString() : t.toString();
            return shard != null ? !name.equals("no_transparency") : !name.contains("no_transparency");
        });
    }
    /** The render type's registered name, e.g. "entity_translucent" (from "RenderType[name:state]"). */
    static String name(RenderType type) {
        return NAMES.computeIfAbsent(type, t -> {
            String text = t.toString();
            int start = text.indexOf('['), end = text.indexOf(':');
            return start >= 0 && end > start ? text.substring(start + 1, end) : text;
        });
    }
    private static final Map<RenderType, String> NAMES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> UNSUPPORTED = new java.util.HashSet<>();
    private static void unsupported(RenderType type) {
        String id = name(type) + "/" + type.mode();
        if (UNSUPPORTED.size() < 32 && UNSUPPORTED.add(id))
            dev.skycraft.SkyCraft.LOG.warn("EndCraft mesh exporter cannot capture render type {} (no texture or unsupported primitive)", id);
    }
    private static Object compositeShard(RenderType type, String field) {
        try {
            Field state = type.getClass().getDeclaredField("state"); state.setAccessible(true); Object composite = state.get(type);
            Field shard = composite.getClass().getDeclaredField(field); shard.setAccessible(true); return shard.get(composite);
        } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }
    /** CompositeRenderType is package private; inspect its documented Mojang state, never GPU internals. */
    private static final Map<RenderType, Optional<ResourceLocation>> TEXTURES = new java.util.concurrent.ConcurrentHashMap<>();
    private static ResourceLocation textureLocation(RenderType type) {
        return TEXTURES.computeIfAbsent(type, t -> Optional.ofNullable(findTextureLocation(t))).orElse(null);
    }
    private static ResourceLocation findTextureLocation(RenderType type) {
        if (type == RenderType.solid() || type == RenderType.cutout() || type == RenderType.cutoutMipped() || type == RenderType.translucent()) return TextureAtlas.LOCATION_BLOCKS;
        try {
            Field state = type.getClass().getDeclaredField("state"); state.setAccessible(true); Object composite = state.get(type);
            Field texture = composite.getClass().getDeclaredField("textureState"); texture.setAccessible(true); Object shard = texture.get(composite);
            for (Class<?> cls = shard.getClass(); cls != null; cls = cls.getSuperclass()) {
                try {
                    Method method = cls.getDeclaredMethod("cutoutTexture"); method.setAccessible(true);
                    var result = (Optional<?>) method.invoke(shard);
                    return result.isPresent() && result.get() instanceof ResourceLocation id ? id : null;
                } catch (NoSuchMethodException ignored) { }
            }
        } catch (ReflectiveOperationException e) { }
        return null;
    }
    void send(int type, Vec3 origin) {
        var used = batches.entrySet().stream().filter(e -> e.getValue().count() > 0).toList();
        int total = used.stream().mapToInt(e -> e.getValue().count()).sum();
        if (total > 500000) throw new IllegalStateException("Dynamic mesh budget exceeded");
        var header = ByteBuffer.allocate((origin == null ? 0 : 24) + 8 + used.size() * 16).order(ByteOrder.LITTLE_ENDIAN);
        if (origin != null) header.putDouble(origin.x).putDouble(origin.y).putDouble(origin.z);
        header.putInt(used.size()).putInt(total);
        int required = total * Proto.REN_VERTEX_BYTES;
        if (transfer.capacity() < required) transfer = ByteBuffer.allocateDirect(Math.max(required, transfer.capacity() * 2)).order(ByteOrder.LITTLE_ENDIAN);
        var vertices = transfer; vertices.clear();
        int first = 0;
        for (var e : used) { int count = e.getValue().count(); header.putInt(e.getKey().texture).putInt(first).putInt(count).putInt(e.getKey().blended ? 1 : 0); vertices.put(e.getValue().bytes()); first += count; }
        SkyLink.tryWriteRender(type, header.flip(), vertices.flip());
        batches.entrySet().removeIf(e -> e.getValue().count() == 0);
    }
}
