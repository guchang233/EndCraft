package dev.skycraft.client;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.skycraft.combat.SkyCombat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
public final class SkyCraftClient {
    public static void initialize(IEventBus modBus) {
        dev.skycraft.link.SkyLink.announceRunning();
        modBus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerEntityRenderer(SkyCombat.SKYRIM_ACTOR.get(), NoopRenderer::new));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            SkyClient.clientTick(Minecraft.getInstance());
            SkyLan.tick(Minecraft.getInstance());
            ProbeTelemetry.tick(Minecraft.getInstance());
        });
        NeoForge.EVENT_BUS.addListener(dev.skycraft.client.render.GuestTerrainRenderer::render);
        // A LAN guest keeps the host's terrain only while on the host's server.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            if (!dev.skycraft.link.SkyLink.active()) dev.skycraft.world.SkyCollision.acceptClear(-1);
        });
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) -> e.getDispatcher().register(
            LiteralArgumentBuilder.<CommandSourceStack>literal("endcraft")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("recover").executes(c -> { SkyClient.requestRecovery(); return 1; }))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("lan")
                    .executes(c -> SkyLan.publish(Minecraft.getInstance(), false, 0))
                    .then(RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("port", IntegerArgumentType.integer(1024, 65535))
                        .executes(c -> SkyLan.publish(Minecraft.getInstance(), false, IntegerArgumentType.getInteger(c, "port"))))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("offline")
                        .executes(c -> SkyLan.publish(Minecraft.getInstance(), true, 0))
                        .then(RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("port", IntegerArgumentType.integer(1024, 65535))
                            .executes(c -> SkyLan.publish(Minecraft.getInstance(), true, IntegerArgumentType.getInteger(c, "port"))))))));
        dev.skycraft.world.SkyCollision.setSmoothCollider(e -> dev.skycraft.link.SkyLink.active() &&
            (e instanceof net.minecraft.world.entity.vehicle.Boat || e instanceof net.minecraft.world.entity.vehicle.AbstractMinecart ||
             e instanceof net.minecraft.client.player.LocalPlayer || e instanceof net.minecraft.server.level.ServerPlayer p && dev.skycraft.net.SkyNet.isHost(p)));
    }
}
