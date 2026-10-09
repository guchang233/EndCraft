package dev.skycraft;

import dev.skycraft.combat.SkyCombat;
import dev.skycraft.world.SkyDig;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(SkyCraft.MOD_ID)
public final class SkyCraft {
    public static final String MOD_ID = "skycraft";
    public static final String WORLD_NAME = "EndCraft-Neo-Bridge";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public SkyCraft(IEventBus modBus, ModContainer container) {
        modBus.addListener((RegisterEvent e) -> {
            e.register(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, helper -> helper.register(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MOD_ID, "dug"), SkyDig.DUG));
        });
        modBus.addListener(dev.skycraft.net.SkyNet::register);
        SkyCombat.init(modBus);
        dev.skycraft.platform.ServerTickEvents.END_SERVER_TICK.register(dev.skycraft.net.SkyTerrainSync::tick);
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> configureServer(e.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p && p.server.getWorldData().getLevelName().equals(WORLD_NAME)) {
                if (dev.skycraft.net.SkyNet.isHost(p)) p.server.getPlayerList().op(p.getGameProfile());
                else bringToHost(p);
                if (p.getInventory().isEmpty()) {
                    for (var item : new net.minecraft.world.item.Item[]{Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.OAK_PLANKS, Items.GLASS, Items.WATER_BUCKET, Items.FIREWORK_ROCKET, Items.TNT, Items.OAK_BOAT, Items.MINECART})
                        p.getInventory().add(new ItemStack(item, item.getDefaultMaxStackSize()));
                    p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
                }
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p && !e.isEndConquered() && p.server.getWorldData().getLevelName().equals(WORLD_NAME)
                && !dev.skycraft.net.SkyNet.isHost(p)) bringToHost(p);
        });
        if (FMLEnvironment.dist == Dist.CLIENT) dev.skycraft.client.SkyCraftClient.initialize(modBus);
    }

    /**
     * The bridge world is void except where the host's terrain is, which follows the host: a LAN
     * guest joins (and respawns) beside the host instead of at the world spawn.
     */
    private static void bringToHost(ServerPlayer guest) {
        for (ServerPlayer host : guest.server.getPlayerList().getPlayers()) {
            if (host != guest && dev.skycraft.net.SkyNet.isHost(host)) {
                guest.teleportTo(host.serverLevel(), host.getX(), host.getY() + 0.1, host.getZ(), host.getYRot(), 0.0F);
                guest.fallDistance = 0.0F;
                LOG.info("SkyCraft: LAN guest {} placed beside the host", guest.getName().getString());
                return;
            }
        }
    }

    private static void configureServer(MinecraftServer server) {
        if (!server.getWorldData().getLevelName().equals(WORLD_NAME)) return;
        var rules = server.getGameRules();
        for (var key : java.util.List.of(GameRules.RULE_DAYLIGHT, GameRules.RULE_WEATHER_CYCLE, GameRules.RULE_DOMOBSPAWNING, GameRules.RULE_DOINSOMNIA, GameRules.RULE_DO_PATROL_SPAWNING, GameRules.RULE_DO_TRADER_SPAWNING, GameRules.RULE_ANNOUNCE_ADVANCEMENTS))
            rules.getRule(key).set(false, server);
        rules.getRule(GameRules.RULE_KEEPINVENTORY).set(true, server);
        rules.getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(true, server);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
    }
}
