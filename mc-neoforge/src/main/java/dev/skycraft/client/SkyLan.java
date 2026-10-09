package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;

/**
 * Opens the bridge world to LAN from the hidden window: {@code /endcraft lan [offline] [port]},
 * or {@code lan=true} in config/skycraft.properties. Guests join with a normal Minecraft client
 * carrying the same mods and see the host's terrain (SkyTerrainSync); the host sees them in the
 * host game.
 */
public final class SkyLan {
	private static boolean autoTried;

	private SkyLan() {
	}

	/**
	 * Publishes the integrated server. {@code offline} turns off Minecraft account verification for
	 * guests (needed when this machine cannot reach Mojang's session servers); {@code port} 0 picks
	 * the configured or a free port.
	 */
	public static int publish(Minecraft minecraft, boolean offline, int port) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.player == null) {
			say(minecraft, "只有自己打开的世界才能开放局域网。");
			return 0;
		}
		if (server.isPublished()) {
			say(minecraft, "局域网已开放，端口 " + server.getPort() + "。" + addresses(server.getPort()));
			return 1;
		}
		if (offline) {
			server.setUsesAuthentication(false);
		}
		int chosen = port > 0 ? port : HttpUtil.getAvailablePort();
		boolean ok = server.publishServer(server.getDefaultGameType(), false, chosen);
		if (!ok && port > 0) {
			chosen = HttpUtil.getAvailablePort(); // the requested port is taken
			ok = server.publishServer(server.getDefaultGameType(), false, chosen);
		}
		if (!ok) {
			say(minecraft, "开放局域网失败，端口 " + chosen + " 无法监听。");
			return 0;
		}
		SkyCraft.LOG.info("SkyCraft: bridge world open to LAN on port {} ({})", chosen, offline ? "offline guests allowed" : "Minecraft accounts verified");
		say(minecraft, "局域网已开放，端口 " + chosen + (offline ? "（离线模式，不验证正版账号）" : "（验证正版账号）") + "。" + addresses(chosen)
			+ " 朋友需安装相同的 NeoForge 模组组合，在多人游戏中选择此世界或直接连接上面的地址。");
		return 1;
	}

	/** {@code lan=true} in config/skycraft.properties opens the bridge world to LAN once it has loaded. */
	static void tick(Minecraft minecraft) {
		if (autoTried || !SkyClient.linked() || minecraft.player == null || minecraft.getSingleplayerServer() == null) {
			return;
		}
		autoTried = true;
		var properties = MirrorWorld.properties(minecraft);
		if (!Boolean.parseBoolean(properties.getProperty("lan", "false").trim())) {
			return;
		}
		int port = 0;
		try {
			port = Integer.parseInt(properties.getProperty("lan_port", "0").trim());
		} catch (NumberFormatException ignored) {
		}
		boolean online = Boolean.parseBoolean(properties.getProperty("lan_online_mode", "true").trim());
		publish(minecraft, !online, port);
	}

	private static String addresses(int port) {
		List<String> found = new ArrayList<>();
		try {
			for (var network : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
					continue;
				}
				for (var address : java.util.Collections.list(network.getInetAddresses())) {
					if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
						found.add(address.getHostAddress() + ":" + port);
					}
				}
			}
		} catch (java.net.SocketException e) {
			SkyCraft.LOG.warn("SkyCraft: couldn't list network addresses", e);
		}
		return found.isEmpty() ? "" : " 地址：" + String.join("、", found) + "。";
	}

	private static void say(Minecraft minecraft, String text) {
		minecraft.gui.getChat().addMessage(Component.literal("[EndCraft] " + text));
	}
}
