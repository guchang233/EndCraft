package dev.skycraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skycraft.SkyCraft;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Discord Rich Presence: the player's Discord status shows SkyCraft (solo, hosting, or in a friend's
 * world), and while hosting it carries a Join button. A friend with SkyCraft running who clicks it
 * gets the host's e4mc link here, and joins exactly as with /join.
 *
 * <p>Talks to the Discord app on this PC over its local IPC pipe (\\.\pipe\discord-ipc-N) from one
 * background thread: frames are sent and, only when some have arrived, read, so a read never blocks
 * a write on the same pipe. Without Discord running it quietly retries now and then.
 */
public final class DiscordPresence {
	// The SkyCraft application on Discord's developer portal (public; not a secret).
	private static final String APP_ID = System.getProperty("skycraft.discordAppId", "1554889902309642280");
	// e4mc's addresses, as it prints them in chat (visible, or only in its click-to-copy action).
	private static final Pattern E4MC_LINK = Pattern.compile("[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.e4mc\\.link", Pattern.CASE_INSENSITIVE);
	private static final int OP_HANDSHAKE = 0, OP_FRAME = 1, OP_CLOSE = 2, OP_PING = 3, OP_PONG = 4;
	private static final long START = System.currentTimeMillis() / 1000L;

	private static volatile @Nullable String hostLink;       // our world's e4mc link while it's open to LAN
	private static volatile @Nullable String wantedActivity; // SET_ACTIVITY args, as the render thread last described them
	private static long nextDescribe;

	private DiscordPresence() {
	}

	public static void start() {
		if ("0".equals(APP_ID) || APP_ID.isBlank()) {
			SkyCraft.LOG.info("SkyCraft: Discord Rich Presence off (no application id)");
			return;
		}
		Thread thread = new Thread(DiscordPresence::run, "SkyCraft Discord");
		thread.setDaemon(true);
		thread.start();
	}

	/** Every chat line (ChatComponentMixin): e4mc's "Local game hosted on domain [...]" gives the link. */
	public static void onChat(Component message) {
		String link = findLink(message);
		if (link != null) {
			hostLink = link;
			SkyCraft.LOG.info("SkyCraft: this world is open to friends at {}", link);
		}
	}

	private static @Nullable String findLink(Component message) {
		Matcher m = E4MC_LINK.matcher(message.getString());
		if (m.find()) {
			return m.group().toLowerCase();
		}
		for (Component part : message.toFlatList()) {
			if (part.getStyle().getClickEvent() instanceof ClickEvent.CopyToClipboard copy) {
				m = E4MC_LINK.matcher(copy.value());
				if (m.find()) {
					return m.group().toLowerCase();
				}
			}
		}
		return null;
	}

	/** Render thread, every client tick: what the status should say now. */
	public static void tick(Minecraft minecraft) {
		long now = System.currentTimeMillis();
		if (now < nextDescribe) {
			return;
		}
		nextDescribe = now + 1000;
		var server = minecraft.getSingleplayerServer();
		if (server == null || !server.isPublished()) {
			hostLink = null;
		}
		JsonObject activity = null;
		// While in a world, whatever Skyrim is doing: Alt-Tabbed, Skyrim pauses and the link goes quiet.
		if (minecraft.player != null && minecraft.getConnection() != null) {
			int players = minecraft.getConnection().getOnlinePlayers().size();
			String friend = MirrorWorld.friendAddress(minecraft);
			activity = new JsonObject();
			activity.addProperty("details", "Playing Skyrim as a Minecraft player");
			JsonObject timestamps = new JsonObject();
			timestamps.addProperty("start", START);
			activity.add("timestamps", timestamps);
			JsonObject assets = new JsonObject();
			assets.addProperty("large_image", "skycraft");
			assets.addProperty("large_text", "SkyCraft");
			activity.add("assets", assets);
			String joinable = hostLink != null ? hostLink : friend;
			if (hostLink != null) {
				activity.addProperty("state", "Hosting a world");
			} else if (friend != null) {
				activity.addProperty("state", "In a friend's world");
			} else {
				activity.addProperty("state", "Playing solo");
			}
			if (joinable != null) {
				// Anyone who can see the status may join the same world (its link is the secret).
				JsonObject party = new JsonObject();
				party.addProperty("id", "skycraft-" + Integer.toHexString(joinable.hashCode()));
				JsonArray size = new JsonArray();
				size.add(Math.max(players, 1));
				// A friend's world: SkyCraft hosts take 100 (IntegratedServerMixin); a guest can't ask.
				size.add(server != null ? Math.max(server.getMaxPlayers(), players + 1) : Math.max(100, players + 1));
				party.add("size", size);
				activity.add("party", party);
				JsonObject secrets = new JsonObject();
				secrets.addProperty("join", joinable);
				activity.add("secrets", secrets);
			}
		}
		JsonObject args = new JsonObject();
		args.addProperty("pid", ProcessHandle.current().pid());
		args.add("activity", activity);
		wantedActivity = args.toString();
	}

	// ---- the IPC thread --------------------------------------------------------------------------

	private static @Nullable RandomAccessFile pipe;
	private static @Nullable FileInputStream in;
	private static @Nullable String sentActivity;
	private static long lastSent;
	private static boolean loggedNoDiscord;
	private static int nonce;

	private static void run() {
		while (true) {
			try {
				if (pipe == null && !connect()) {
					Thread.sleep(15_000);
					continue;
				}
				String wanted = wantedActivity;
				long now = System.currentTimeMillis();
				// Discord accepts 5 updates per 20 s; stay well under.
				if (wanted != null && !wanted.equals(sentActivity) && now - lastSent > 5_000) {
					command("SET_ACTIVITY", JsonParser.parseString(wanted).getAsJsonObject(), null);
					sentActivity = wanted;
					lastSent = now;
				}
				readAvailable();
				Thread.sleep(250);
			} catch (IOException e) {
				SkyCraft.LOG.info("SkyCraft: Discord connection closed ({}); retrying later", e.getMessage());
				close();
			} catch (InterruptedException e) {
				return;
			} catch (RuntimeException e) {
				SkyCraft.LOG.warn("SkyCraft: Discord Rich Presence error", e);
				close();
			}
		}
	}

	private static boolean connect() throws IOException, InterruptedException {
		for (int i = 0; i < 10; i++) {
			try {
				pipe = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw");
			} catch (IOException e) {
				continue;
			}
			in = new FileInputStream(pipe.getFD());
			JsonObject hello = new JsonObject();
			hello.addProperty("v", 1);
			hello.addProperty("client_id", APP_ID);
			write(OP_HANDSHAKE, hello);
			// READY, then subscribe to friends clicking Join.
			long deadline = System.currentTimeMillis() + 5_000;
			while (System.currentTimeMillis() < deadline) {
				JsonObject msg = readFrame();
				if (msg != null && "READY".equals(str(msg, "evt"))) {
					command("SUBSCRIBE", null, "ACTIVITY_JOIN");
					SkyCraft.LOG.info("SkyCraft: connected to Discord (Rich Presence)");
					loggedNoDiscord = false;
					sentActivity = null;
					return true;
				}
				if (msg == null) {
					Thread.sleep(100);
				}
			}
			close();
			return false;
		}
		if (!loggedNoDiscord) {
			loggedNoDiscord = true;
			SkyCraft.LOG.info("SkyCraft: Discord isn't running; Rich Presence will connect when it is");
		}
		return false;
	}

	private static void command(String cmd, @Nullable JsonObject args, @Nullable String evt) throws IOException {
		JsonObject frame = new JsonObject();
		frame.addProperty("cmd", cmd);
		frame.addProperty("nonce", Integer.toString(++nonce));
		if (args != null) {
			frame.add("args", args);
		}
		if (evt != null) {
			frame.addProperty("evt", evt);
		}
		write(OP_FRAME, frame);
	}

	private static void readAvailable() throws IOException {
		JsonObject msg;
		while ((msg = readFrame()) != null) {
			if ("DISPATCH".equals(str(msg, "cmd")) && "ACTIVITY_JOIN".equals(str(msg, "evt")) && msg.has("data")) {
				String secret = str(msg.getAsJsonObject("data"), "secret");
				if (secret != null && E4MC_LINK.matcher(secret).matches()) {
					SkyCraft.LOG.info("SkyCraft: joining {} from Discord", secret);
					Minecraft.getInstance().execute(() -> MirrorWorld.joinFriend(Minecraft.getInstance(), secret));
				}
			} else if ("ERROR".equals(str(msg, "evt"))) {
				SkyCraft.LOG.warn("SkyCraft: Discord says {}", msg.get("data"));
			}
		}
	}

	private static void write(int op, JsonObject payload) throws IOException {
		byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
		ByteBuffer frame = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
		frame.putInt(op).putInt(body.length).put(body);
		pipe.write(frame.array());
	}

	/** The next frame if one has fully arrived (never blocks for one that hasn't started). */
	private static @Nullable JsonObject readFrame() throws IOException {
		while (in.available() >= 8) {
			byte[] header = new byte[8];
			pipe.readFully(header);
			ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
			int op = h.getInt();
			int length = h.getInt();
			byte[] body = new byte[length];
			pipe.readFully(body);
			String text = new String(body, StandardCharsets.UTF_8);
			switch (op) {
				case OP_PING -> {
					ByteBuffer pong = ByteBuffer.allocate(8 + length).order(ByteOrder.LITTLE_ENDIAN);
					pong.putInt(OP_PONG).putInt(length).put(body);
					pipe.write(pong.array());
				}
				case OP_CLOSE -> throw new IOException("Discord closed the connection: " + text);
				case OP_FRAME -> {
					return JsonParser.parseString(text).getAsJsonObject();
				}
				default -> {
				}
			}
		}
		return null;
	}

	private static @Nullable String str(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
	}

	private static void close() {
		try {
			if (pipe != null) {
				pipe.close();
			}
		} catch (IOException ignored) {
		}
		pipe = null;
		in = null;
		sentActivity = null;
	}
}
