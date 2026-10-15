package dev.skycraft.client;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Mesh overlay preserves Endfield's scene depth; raster preview runs the complete MC pipeline. */
public final class RenderBackend {
    private static boolean preview = "shader-preview".equals(System.getProperty("endcraft.renderBackend", "mesh"));
    private RenderBackend() { }
    public static boolean preview() { return preview; }
    public static boolean meshOverlay() { return SkyClient.linked() && !preview; }
    public static int select(CommandSourceStack source, boolean raster) {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object config = api.getMethod("getConfig").invoke(api.getMethod("getInstance").invoke(null));
            Class<?> configApi = Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig");
            boolean enabled = (boolean) configApi.getMethod("areShadersEnabled").invoke(config);
            if (enabled != raster) configApi.getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, raster);
        } catch (ClassNotFoundException absent) {
            // Vanilla raster preview is also useful for diagnosing effects missing from mesh capture.
        } catch (ReflectiveOperationException | RuntimeException error) {
            dev.skycraft.SkyCraft.LOG.error("EndCraft: could not switch Iris pipeline", error);
            source.sendFailure(Component.literal("EndCraft：Iris 渲染管线切换失败，保留原模式，请查看日志。"));
            return 0;
        }
        preview = raster;
        source.sendSuccess(() -> Component.literal(raster
            ? "EndCraft：光影预览已开启。显示 MC 完整画面；终末地原生场景会被覆盖。"
            : "EndCraft：已恢复终末地场景与 MC 网格桥接。"), false);
        return 1;
    }
}
