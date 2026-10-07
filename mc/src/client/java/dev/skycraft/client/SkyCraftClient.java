package dev.skycraft.client;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.NoopRenderer;
import dev.skycraft.combat.SkyCombat;

public final class SkyCraftClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        dev.skycraft.link.SkyLink.announceRunning();
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("endcraft")
                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("recover").executes(context -> {
                    SkyClient.requestRecovery();
                    context.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("已请求恢复角色与桥接，切换为第三人称。"));
                    return 1;
                }))));
        ClientTickEvents.END_CLIENT_TICK.register(SkyClient::clientTick);
        ClientTickEvents.END_CLIENT_TICK.register(ProbeTelemetry::tick);
        EntityRendererRegistry.register(SkyCombat.SKYRIM_ACTOR, NoopRenderer::new);
		dev.skycraft.world.SkyCollision.setSmoothCollider(e ->
            (dev.skycraft.link.SkyLink.active() && (e instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat
                || e instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart)) ||
            (e instanceof net.minecraft.client.player.LocalPlayer && SkyClient.linked()) ||
            (e instanceof net.minecraft.server.level.ServerPlayer sp && dev.skycraft.net.SkyNet.isHost(sp) && dev.skycraft.link.SkyLink.active()));
    }
}
