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
    private record Key(int texture, boolean blended) { }
    private final Map<Key, CapturedMesh> batches = new LinkedHashMap<>();
    UnaryOperator<Vec3> transform = UnaryOperator.identity();
    private final CapturedMesh discarded = new CapturedMesh();
    @Override public VertexConsumer getBuffer(RenderType type) {
        ResourceLocation location = textureLocation(type);
        if (location == null || type.mode() != com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS) return discarded;
        int id = TextureExporter.texture(location); if (id < 0) return discarded;
        boolean blended = blended(type);
        var key = new Key(id, blended);
        if (!batches.containsKey(key) && batches.size() >= 64) return discarded;
        var mesh = batches.computeIfAbsent(key, k -> new CapturedMesh());
        mesh.finish(); mesh.transform = transform; mesh.flags = 8 | (blended ? 2 : 1); return mesh;
    }
    private static final Map<RenderType, Boolean> BLENDED = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Blended batches are drawn without depth writes, so only types that really blend may be marked.
     * Decided by the type's transparency shard (every description names its texture, so text
     * matching on "text" or "alpha" marks solid blocks blended and breaks occlusion).
     */
    static boolean blended(RenderType type) {
        return BLENDED.computeIfAbsent(type, t -> {
            Object shard = compositeShard(t, "transparencyState");
            String name = shard != null ? shard.toString() : t.toString();
            return shard != null ? !name.equals("no_transparency") : !name.contains("no_transparency");
        });
    }
    private static Object compositeShard(RenderType type, String field) {
        try {
            Field state = type.getClass().getDeclaredField("state"); state.setAccessible(true); Object composite = state.get(type);
            Field shard = composite.getClass().getDeclaredField(field); shard.setAccessible(true); return shard.get(composite);
        } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }
    /** CompositeRenderType is package private; inspect its documented Mojang state, never GPU internals. */
    private static ResourceLocation textureLocation(RenderType type) {
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
        var vertices = ByteBuffer.allocateDirect(total * Proto.REN_VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int first = 0;
        for (var e : used) { int count = e.getValue().count(); header.putInt(e.getKey().texture).putInt(first).putInt(count).putInt(e.getKey().blended ? 1 : 0); vertices.put(e.getValue().bytes()); first += count; }
        SkyLink.tryWriteRender(type, header.flip(), vertices.flip());
    }
}
