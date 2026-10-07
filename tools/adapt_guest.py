"""Reproduce the small, auditable EndCraft changes to the pinned SkyCraft guest."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]

def replace(relative, old, new):
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"Upstream context changed: {relative}: {old[:60]}")
    path.write_text(text.replace(old, new), encoding="utf-8")

replace("protocol/endcraft_protocol.h", "0x43594B53", "0x54464345")
replace("protocol/endcraft_protocol.h", "kVersion = 11", "kVersion = 12")
replace("protocol/endcraft_protocol.h", 'L"Local\\\\SkyCraft_v1"', 'L"Local\\\\EndCraft_v1"')
replace("mc/src/main/java/dev/skycraft/link/Proto.java", "0x43594B53", "0x54464345")
replace("mc/src/main/java/dev/skycraft/link/Proto.java", "VERSION = 11", "VERSION = 12")
replace("mc/src/main/java/dev/skycraft/link/Proto.java", '"Local\\\\SkyCraft_v1"', '"Local\\\\EndCraft_v1"')
# A live diagnostic process is not permission to take over the MC window or game.
replace("mc/src/main/java/dev/skycraft/link/SkyLink.java",
        "return tickCount() - beat < HEARTBEAT_TIMEOUT_MS;",
        "return beat > 0 && tickCount() >= beat && tickCount() - beat < HEARTBEAT_TIMEOUT_MS\n"
        "\t\t\t&& (s.get(JAVA_INT, OFF_SKY_STATE + SS_FLAGS) & SKY_IN_GAME) != 0;")
replace("mc/src/client/java/dev/skycraft/client/SkyClient.java",
        'System.getProperty("skycraft.quitWithSkyrim", "true")',
        'System.getProperty("skycraft.quitWithSkyrim", "false")')
replace("mc/src/main/java/dev/skycraft/SkyCraft.java", 'WORLD_NAME = "SkyCraft"', 'WORLD_NAME = "EndCraft-Bridge"')
replace("mc/settings.gradle", "rootProject.name = 'skycraft'", "rootProject.name = 'endcraft-guest'")
replace("mc/gradle.properties", "version=0.1.2", "version=0.1.0-probe")
replace("mc/gradle.properties", "-Xmx4G", "-Xmx2G")
replace("mc/gradle.properties", "loom_version=1.18-SNAPSHOT", "loom_version=1.17.21")
replace("mc/gradle/wrapper/gradle-wrapper.properties", "gradle-9.7.1-bin.zip", "gradle-9.6.1-bin.zip")
replace("mc/build.gradle", '\truntimeOnly "maven.modrinth:e4mc:AouleFRY"',
        '\t// Internet world sharing is excluded from the diagnostic guest.')
replace("mc/build.gradle", 'vmArgs "--enable-native-access=ALL-UNNAMED", "-Dskycraft.quitWithSkyrim=false"',
        'vmArgs "--enable-native-access=ALL-UNNAMED", "-Dskycraft.quitWithSkyrim=false", "-Xmx2G"\n'
        '\t\t\tprogramArgs "--width", "640", "--height", "360"')
manifest = ROOT / "mc/src/main/resources/fabric.mod.json"
data = json.loads(manifest.read_text(encoding="utf-8"))
data.update(name="EndCraft Guest (接入验证)",
            description="SkyCraft-derived MC guest. Diagnostic transport only; Endfield gameplay remains unverified.",
            authors=["chasmlol (SkyCraft upstream)", "EndCraft adaptation"])
manifest.write_text(json.dumps(data, indent=2, ensure_ascii=False)+"\n",encoding="utf-8")
# Disable unrelated upstream RPC/LAN features. Keep package and registry IDs for compatibility.
(ROOT / "mc/src/client/java/dev/skycraft/client/SkyCraftClient.java").write_text('''package dev.skycraft.client;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.NoopRenderer;
import dev.skycraft.combat.SkyCombat;

public final class SkyCraftClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        dev.skycraft.link.SkyLink.announceRunning();
        ClientTickEvents.END_CLIENT_TICK.register(SkyClient::clientTick);
        ClientTickEvents.END_CLIENT_TICK.register(ProbeTelemetry::tick);
        EntityRendererRegistry.register(SkyCombat.SKYRIM_ACTOR, NoopRenderer::new);
        dev.skycraft.world.SkyCollision.setSmoothCollider(e -> e instanceof net.minecraft.world.entity.player.Player && SkyClient.linked());
    }
}
''',encoding="utf-8")
exec((ROOT/'tools/repair_guest_lifecycle.py').read_text(encoding='utf-8'))
print("EndCraft guest adaptation applied; gameplay remains gated.")
