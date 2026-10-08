package dev.skycraft.client;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.skycraft.combat.SkyCombat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
public final class SkyCraftClient {
    public static void initialize(IEventBus modBus) {
        dev.skycraft.link.SkyLink.announceRunning();
        modBus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerEntityRenderer(SkyCombat.SKYRIM_ACTOR, NoopRenderer::new));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            SkyClient.clientTick(Minecraft.getInstance());
            ProbeTelemetry.tick(Minecraft.getInstance());
        });
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) -> e.getDispatcher().register(
            LiteralArgumentBuilder.<net.minecraft.commands.CommandSourceStack>literal("endcraft").then(
                LiteralArgumentBuilder.<net.minecraft.commands.CommandSourceStack>literal("recover").executes(c -> { SkyClient.requestRecovery(); return 1; }))));
        dev.skycraft.world.SkyCollision.setSmoothCollider(e -> dev.skycraft.link.SkyLink.active() &&
            (e instanceof net.minecraft.world.entity.vehicle.Boat || e instanceof net.minecraft.world.entity.vehicle.AbstractMinecart ||
             e instanceof net.minecraft.client.player.LocalPlayer || e instanceof net.minecraft.server.level.ServerPlayer p && dev.skycraft.net.SkyNet.isHost(p)));
    }
}
