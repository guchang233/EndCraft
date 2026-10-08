package dev.skycraft.platform;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
public final class NeoPackets {
    public static boolean canSend(CustomPacketPayload.Type<?> type) { return net.minecraft.client.Minecraft.getInstance().getConnection() != null; }
    public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) { return player.connection.hasChannel(type.id()); }
    public static void send(CustomPacketPayload payload) { PacketDistributor.sendToServer(payload); }
    public static void send(ServerPlayer player, CustomPacketPayload payload) { PacketDistributor.sendToPlayer(player, payload); }
}
