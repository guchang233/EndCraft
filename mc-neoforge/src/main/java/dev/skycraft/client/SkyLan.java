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
 * Opens the bridge world to LAN from the hidden window: {@code /endcraft lan [offline] [cheats] [port]}
 * in any order, or {@code lan=true} in config/skycraft.properties. Guests join with a normal Minecraft client
 * carrying the same mods and see the host's terrain (SkyTerrainSync); the host sees them in the
 * host game.
 */
public final class SkyLan {
	private static boolean autoTried;
	// /endcraft join without an address: listening for LAN worlds until one appears or time runs out.
	private static net.minecraft.client.server.LanServerDetection.LanServerDetector detector;
	private static net.minecraft.client.server.LanServerDetection.LanServerList found;
	private static long searchUntil;

	private SkyLan() {
	}

	/**
	 * Publishes the integrated server. {@code offline} turns off Minecraft account verification for
	 * guests (needed when this machine cannot reach Mojang's session servers); {@code port} 0 picks
	 * the configured or a free port.
	 */
	public static int publish(Minecraft minecraft, boolean offline, int port) {
		return publish(minecraft, offline, false, port);
	}

	/** {@code options}: words "offline", "cheats" and a port number, in any order. */
	public static int command(Minecraft minecraft, String options) {
		boolean offline = false, cheats = false;
		int port = 0;
		for (String word : options.trim().split("\\s+")) {
			if (word.isEmpty()) continue;
			switch (word.toLowerCase(java.util.Locale.ROOT)) {
				case "offline" -> offline = true;
				case "cheats" -> cheats = true;
				default -> {
					try {
						port = Integer.parseInt(word);
					} catch (NumberFormatException e) {
						say(minecraft, "不认识的参数“" + word + "”。用法：/endcraft lan [offline] [cheats] [端口]");
						return 0;
					}
					if (port < 1024 || port > 65535) {
						say(minecraft, "端口需在 1024–65535 之间。");
						return 0;
					}
				}
			}
		}
		return publish(minecraft, offline, cheats, port);
	}

	/** {@code cheats}: guests may use commands (/gamemode, /tp, ...), like "Allow Cheats" in Open to LAN. */
	public static int publish(Minecraft minecraft, boolean offline, boolean cheats, int port) {
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
		boolean ok = server.publishServer(server.getDefaultGameType(), cheats, chosen);
		if (!ok && port > 0) {
			chosen = HttpUtil.getAvailablePort(); // the requested port is taken
			ok = server.publishServer(server.getDefaultGameType(), cheats, chosen);
		}
		if (!ok) {
			say(minecraft, "开放局域网失败，端口 " + chosen + " 无法监听。");
			return 0;
		}
		SkyCraft.LOG.info("SkyCraft: bridge world open to LAN on port {} ({}, cheats {})", chosen, offline ? "offline guests allowed" : "Minecraft accounts verified", cheats ? "on" : "off");
		say(minecraft, "局域网已开放，端口 " + chosen + (offline ? "（离线模式，不验证正版账号" : "（验证正版账号") + (cheats ? "，允许作弊）" : "）") + "。" + addresses(chosen)
			+ " 朋友需安装相同的 NeoForge 模组组合，在多人游戏中选择此世界或直接连接上面的地址。");
		return 1;
	}

	/**
	 * {@code /endcraft join [address]}: a bridged client (window hidden, no server menu) plays in a
	 * friend's world. Without an address, the first LAN world announced within 5 seconds.
	 */
	public static int join(Minecraft minecraft, String address) {
		if (!address.isBlank()) {
			say(minecraft, "正在加入 " + address.trim() + "……");
			MirrorWorld.joinFriend(minecraft, address);
			return 1;
		}
		if (detector != null) {
			return 1;
		}
		try {
			found = new net.minecraft.client.server.LanServerDetection.LanServerList();
			detector = new net.minecraft.client.server.LanServerDetection.LanServerDetector(found);
			detector.start();
		} catch (java.io.IOException e) {
			say(minecraft, "无法搜索局域网世界：" + e.getMessage());
			return 0;
		}
		searchUntil = System.currentTimeMillis() + 5000;
		say(minecraft, "正在搜索局域网世界……");
		return 1;
	}

	private static void searchTick(Minecraft minecraft) {
		if (detector == null) {
			return;
		}
		var servers = found.takeDirtyServers();
		boolean timedOut = System.currentTimeMillis() > searchUntil;
		if (servers == null && !timedOut) {
			return;
		}
		detector.interrupt();
		detector = null;
		if (servers == null || servers.isEmpty()) {
			say(minecraft, "没有找到局域网世界。请确认主机已执行 /endcraft lan，或用 /endcraft join <地址> 直接连接。");
			return;
		}
		var server = servers.get(0);
		say(minecraft, "找到“" + server.getMotd() + "”，正在加入 " + server.getAddress() + "……");
		MirrorWorld.joinFriend(minecraft, server.getAddress());
	}

	/** {@code lan=true} in config/skycraft.properties opens the bridge world to LAN once it has loaded. */
	static void tick(Minecraft minecraft) {
		searchTick(minecraft);
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
		boolean cheats = Boolean.parseBoolean(properties.getProperty("lan_cheats", "false").trim());
		publish(minecraft, !online, cheats, port);
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

	static void say(Minecraft minecraft, String text) {
		minecraft.gui.getChat().addMessage(Component.literal("[EndCraft] " + text));
	}
}
