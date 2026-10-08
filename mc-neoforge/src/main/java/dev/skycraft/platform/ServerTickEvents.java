package dev.skycraft.platform;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
public final class ServerTickEvents {
    public static final ServerTickEvents END_SERVER_TICK = new ServerTickEvents();
    public void register(Consumer<MinecraftServer> callback) {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> callback.accept(e.getServer()));
    }
}
