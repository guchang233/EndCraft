package dev.skycraft.compat;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Inspect class files without loading Minecraft, NeoForge, Flywheel or Sable. */
class MixinTargetTest {
    private final Map<String, ClassNode> cache = new HashMap<>();
    private final Map<String, byte[]> optional = new HashMap<>();
    private static Map<String, Object> values(AnnotationNode annotation) {
        Map<String, Object> result = new HashMap<>();
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2)
            result.put((String) annotation.values.get(i), annotation.values.get(i + 1));
        return result;
    }
    private static List<AnnotationNode> annotations(List<AnnotationNode> a, List<AnnotationNode> b) {
        List<AnnotationNode> result = new ArrayList<>();
        if (a != null) result.addAll(a); if (b != null) result.addAll(b); return result;
    }
    private static List<?> list(Object value) { return value instanceof List<?> l ? l : List.of(value); }
    private static ClassNode parse(byte[] bytes) {
        var node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node;
    }
    private ClassNode target(String name) throws Exception {
        if (cache.containsKey(name)) return cache.get(name);
        if (optional.containsKey(name)) { var n = parse(optional.get(name)); cache.put(name, n); return n; }
        try (var zip = new ZipFile("build/moddev/artifacts/neoforge-21.1.247.jar")) {
            var entry = zip.getEntry(name + ".class");
            assertNotNull(entry, "Missing target class: " + name);
            var n = parse(zip.getInputStream(entry).readAllBytes()); cache.put(name, n); return n;
        }
    }
    private void readOptional(byte[] bytes) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.endsWith(".jar") && name.startsWith("META-INF/jarjar/")) readOptional(zip.readAllBytes());
                else if (name.endsWith(".class") && (name.startsWith("dev/engine_room/") || name.startsWith("dev/ryanhcode/") || name.startsWith("foundry/veil/")))
                    optional.put(name.substring(0, name.length() - 6), zip.readAllBytes());
            }
        }
    }
    @Test void allConfiguredInjectorsMatchTheLockedGameAndFlywheelBytecode() throws Exception {
        try (var files = Files.list(Path.of("libs"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".jar")).toList()) readOptional(Files.readAllBytes(file));
        }
        int checked = 0;
        for (String config : List.of("skycraft.mixins.json", "skycraft.client.mixins.json")) {
            var json = JsonParser.parseString(Files.readString(Path.of("src/main/resources", config))).getAsJsonObject();
            String pkg = json.get("package").getAsString();
            var names = json.has("mixins") ? json.getAsJsonArray("mixins") : json.getAsJsonArray("client");
            for (var name : names) {
                String id = pkg + "." + name.getAsString();
                var mixin = parse(Files.readAllBytes(Path.of("build/classes/java/main", id.replace('.', '/') + ".class")));
                var mixinAnnotation = annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations).stream()
                    .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
                var mv = values(mixinAnnotation); List<String> targets = new ArrayList<>();
                if (mv.containsKey("value")) for (var type : list(mv.get("value"))) targets.add(((Type) type).getInternalName());
                if (mv.containsKey("targets")) for (var type : list(mv.get("targets"))) targets.add(type.toString().replace('.', '/'));
                for (var nameTarget : targets) {
                    var cls = target(nameTarget);
                    if ((cls.access & Opcodes.ACC_INTERFACE) != 0)
                        assertTrue((mixin.access & Opcodes.ACC_INTERFACE) != 0, id + " interface target requires an interface mixin");
                    for (var handler : mixin.methods) for (var annotation : annotations(handler.visibleAnnotations, handler.invisibleAnnotations)) {
                        var v = values(annotation);
                        boolean invoker = annotation.desc.endsWith("/Invoker;");
                        if (!invoker && !annotation.desc.endsWith("/Inject;") && !annotation.desc.endsWith("/WrapOperation;")) continue;
                        Object selectors = v.get(invoker ? "value" : "method");
                        for (var selectorValue : list(selectors)) {
                            String selector = selectorValue.toString(); int paren = selector.indexOf('(');
                            String methodName = paren < 0 ? selector : selector.substring(0, paren);
                            var methods = cls.methods.stream().filter(m -> m.name.equals(methodName) && (paren < 0 || m.desc.equals(selector.substring(paren)))).toList();
                            assertFalse(methods.isEmpty(), id + " missing " + nameTarget + "." + selector);
                            for (var method : methods) {
                                assertEquals((method.access & Opcodes.ACC_STATIC) != 0, (handler.access & Opcodes.ACC_STATIC) != 0, id + " static mismatch " + selector);
                                if (invoker) { assertEquals(method.desc, handler.desc, id + " invoker descriptor"); continue; }
                                if (annotation.desc.endsWith("/Inject;")) {
                                    var args = Type.getArgumentTypes(handler.desc);
                                    var originalArgs = Type.getArgumentTypes(method.desc);
                                    String callback = Type.getReturnType(method.desc).equals(Type.VOID_TYPE)
                                        ? "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;"
                                        : "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
                                    assertTrue(args.length == 1 || args.length == originalArgs.length + 1, id + " handler argument count");
                                    assertEquals(callback, args[args.length - 1].getDescriptor(), id + " callback descriptor");
                                    if (args.length > 1) assertArrayEquals(originalArgs, Arrays.copyOf(args, args.length - 1), id + " handler arguments");
                                }
                                Object ats = v.get("at");
                                for (var atValue : list(ats)) {
                                    var at = values((AnnotationNode) atValue);
                                    if (!"INVOKE".equals(at.get("value"))) continue;
                                    String signature = at.get("target").toString();
                                    boolean found = false;
                                    for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
                                        if (signature.equals("L" + call.owner + ";" + call.name + call.desc)) {
                                            found = true;
                                            if (annotation.desc.endsWith("/WrapOperation;")) {
                                                List<Type> expected = new ArrayList<>();
                                                if (call.getOpcode() != Opcodes.INVOKESTATIC) expected.add(Type.getObjectType(call.owner));
                                                expected.addAll(Arrays.asList(Type.getArgumentTypes(call.desc)));
                                                expected.add(Type.getObjectType("com/llamalad7/mixinextras/injector/wrapoperation/Operation"));
                                                assertArrayEquals(expected.toArray(Type[]::new), Type.getArgumentTypes(handler.desc), id + " wrapper arguments");
                                                assertEquals(Type.getReturnType(call.desc), Type.getReturnType(handler.desc), id + " wrapper return");
                                            }
                                        }
                                    }
                                    assertTrue(found, id + " missing call in " + selector + ": " + signature);
                                }
                                checked++;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(checked >= 20, "Audit unexpectedly skipped configured hooks");
    }
    @Test void sableReflectionNamesExistInTheDownloadedVersion() throws Exception {
        readOptional(Files.readAllBytes(Path.of("libs/sable-neoforge-1.21.1-2.0.6.jar")));
        Map<String, List<String>> required = Map.of(
            "dev/ryanhcode/sable/api/sublevel/SubLevelContainer", List.of("getContainer(Lnet/minecraft/world/level/Level;)Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;", "getAllSubLevels()Ljava/util/List;"),
            "dev/ryanhcode/sable/sublevel/ClientSubLevel", List.of("renderPose(F)Ldev/ryanhcode/sable/companion/math/Pose3dc;", "getPlot()Ldev/ryanhcode/sable/sublevel/plot/ClientLevelPlot;"),
            "dev/ryanhcode/sable/sublevel/SubLevel", List.of("isRemoved()Z"),
            "dev/ryanhcode/sable/sublevel/plot/LevelPlot", List.of("getCenterBlock()Lnet/minecraft/core/BlockPos;", "getLoadedChunks()Ljava/util/Collection;"),
            "dev/ryanhcode/sable/sublevel/plot/PlotChunkHolder", List.of("getChunk()Lnet/minecraft/world/level/chunk/LevelChunk;"),
            "dev/ryanhcode/sable/companion/math/Pose3dc", List.of("transformPosition(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"));
        for (var entry : required.entrySet()) {
            var cls = target(entry.getKey());
            for (String method : entry.getValue()) assertTrue(cls.methods.stream().anyMatch(m -> (m.name + m.desc).equals(method)), entry.getKey() + "." + method);
        }
    }

    /** Content mods loaded from libs/ alongside the bridge, keyed by artifact file. */
    private static final Map<String, List<String>> CONTENT_MOD_MIXINS = Map.of(
        "tacz-neoforge-1.21.1-1.1.8-hotfix-r7.jar", List.of("tacz.mixins.json"),
        "ysm-2.6.3-neoforge+mc1.21.1-release.jar", List.of("yes_steve_model.mixins.json"),
        "touhoulittlemaid-1.5.3-neoforge+mc1.21.1.jar", List.of("touhou_little_maid.mixins.json"),
        "maidaddition1.21.1-1.0.1-beta-all.jar", List.of("maidaddition.mixins.json"));

    private com.google.gson.JsonObject readConfig(ZipFile zip, String config) throws Exception {
        var entry = zip.getEntry(config);
        assertNotNull(entry, "Missing mixin config in jar: " + config);
        String text = new String(zip.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static Set<String> mixinTargetClasses(ClassNode mixin) {
        var annotation = annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations).stream()
            .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElse(null);
        if (annotation == null) return Set.of();
        var v = values(annotation);
        Set<String> targets = new HashSet<>();
        if (v.containsKey("value")) for (var type : list(v.get("value"))) targets.add(((Type) type).getInternalName());
        if (v.containsKey("targets")) for (var name : list(v.get("targets"))) targets.add(name.toString().replace('.', '/'));
        return targets;
    }

    private Set<String> ownMixinTargetClasses() throws Exception {
        Set<String> targets = new HashSet<>();
        for (String config : List.of("skycraft.mixins.json", "skycraft.client.mixins.json")) {
            var json = JsonParser.parseString(Files.readString(Path.of("src/main/resources", config))).getAsJsonObject();
            String pkg = json.get("package").getAsString();
            var names = json.has("mixins") ? json.getAsJsonArray("mixins") : json.getAsJsonArray("client");
            for (var name : names) {
                String id = pkg + "." + name.getAsString();
                var mixin = parse(Files.readAllBytes(Path.of("build/classes/java/main", id.replace('.', '/') + ".class")));
                targets.addAll(mixinTargetClasses(mixin));
            }
        }
        return targets;
    }

    private Map<String, Set<String>> contentModMixinTargetClasses() throws Exception {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (var jarConfig : CONTENT_MOD_MIXINS.entrySet()) {
            try (var zip = new ZipFile(Path.of("libs", jarConfig.getKey()).toString())) {
                Set<String> targets = new HashSet<>();
                for (String config : jarConfig.getValue()) {
                    var json = readConfig(zip, config);
                    String pkg = json.get("package").getAsString();
                    var names = new ArrayList<com.google.gson.JsonElement>();
                    if (json.has("mixins")) json.getAsJsonArray("mixins").forEach(names::add);
                    if (json.has("client")) json.getAsJsonArray("client").forEach(names::add);
                    for (var name : names) {
                        String id = pkg + "." + name.getAsString();
                        var entry = zip.getEntry(id.replace('.', '/') + ".class");
                        if (entry == null) continue;
                        targets.addAll(mixinTargetClasses(parse(zip.getInputStream(entry).readAllBytes())));
                    }
                }
                result.put(jarConfig.getKey(), targets);
            }
        }
        return result;
    }

    /**
     * Classes mixed into by both this project and a loaded content mod. Each entry was cleared by
     * comparing injection points in the publisher bytecode: no method is injected at overlapping
     * @At sites by both sides. Anything outside this list failing the test needs the same manual
     * review before it is allowed in.
     * <ul>
     * <li>AbstractArrow: ours wraps Level.clip inside tick and injects onHitEntity; TLM injects
     * tick at HEAD (maid-owned arrows), YSM hooks setOwner. No shared call site.</li>
     * <li>Projectile: ours injects hitTargetOrDeflectSelf; YSM hooks setOwner.</li>
     * <li>Entity: ours injects collide at RETURN; TLM wraps the collide INVOKE inside move
     * (EntityBroom physical check). Different methods.</li>
     * <li>Player: ours injects maybeBackOffFromEdge; TLM targets rider/movement paths.</li>
     * <li>ServerPlayer: ours injects crit and die; YSM hooks positionRider/startRiding, TaCZ
     * weapon handling.</li>
     * <li>LevelRenderer: ours injects setSectionDirty; YSM injects renderLevel and fog color.</li>
     * <li>MouseHandler: ours is an accessor only; TaCZ injects scrolling/zoom paths.</li>
     * </ul>
     */
    private static final Set<String> ALLOWED_SHARED_MIXIN_TARGETS = Set.of(
        "net/minecraft/world/entity/projectile/AbstractArrow",
        "net/minecraft/world/entity/projectile/Projectile",
        "net/minecraft/world/entity/Entity",
        "net/minecraft/world/entity/player/Player",
        "net/minecraft/server/level/ServerPlayer",
        "net/minecraft/client/renderer/LevelRenderer",
        "net/minecraft/client/MouseHandler");

    @Test void contentModMixinConfigurationsResolveEveryDeclaredMixinClass() throws Exception {
        for (var jarConfig : CONTENT_MOD_MIXINS.entrySet()) {
            try (var zip = new ZipFile(Path.of("libs", jarConfig.getKey()).toString())) {
                for (String config : jarConfig.getValue()) {
                    var json = readConfig(zip, config);
                    String pkg = json.get("package").getAsString();
                    var names = new ArrayList<com.google.gson.JsonElement>();
                    if (json.has("mixins")) json.getAsJsonArray("mixins").forEach(names::add);
                    if (json.has("client")) json.getAsJsonArray("client").forEach(names::add);
                    int resolved = 0;
                    for (var name : names) {
                        String id = pkg + "." + name.getAsString();
                        var entry = zip.getEntry(id.replace('.', '/') + ".class");
                        assertNotNull(entry, config + " declares a missing mixin class: " + id);
                        var mixin = parse(zip.getInputStream(entry).readAllBytes());
                        assertTrue(mixinTargetClasses(mixin).size() > 0 || id.endsWith("Accessor"),
                            config + " mixin without any @Mixin target: " + id);
                        resolved++;
                    }
                    assertTrue(resolved > 0 || config.equals("maidaddition.mixins.json"),
                        config + " resolved no mixin classes");
                }
            }
        }
    }

    @Test void endcraftMixinsOnlyShareReviewedTargetClassesWithLoadedContentMods() throws Exception {
        Set<String> own = ownMixinTargetClasses();
        assertFalse(own.isEmpty(), "Own mixin audit found no target classes");
        List<String> unreviewed = new ArrayList<>();
        for (var entry : contentModMixinTargetClasses().entrySet())
            for (String target : entry.getValue())
                if (own.contains(target) && !ALLOWED_SHARED_MIXIN_TARGETS.contains(target))
                    unreviewed.add(target + " shared with " + entry.getKey());
        assertTrue(unreviewed.isEmpty(),
            "New shared mixin target classes need manual review before running: " + unreviewed);
    }

    @Test void everyLibsJarIsHashLockedAndEveryLockedArtifactIsPresent() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        for (var item : JsonParser.parseString(Files.readString(Path.of("modpack.lock.json"))).getAsJsonArray()) {
            var obj = item.getAsJsonObject();
            expected.put(obj.get("filename").getAsString(), obj.get("sha512").getAsString());
        }
        for (var entry : expected.entrySet()) {
            var file = Path.of("libs", entry.getKey());
            assertTrue(Files.isRegularFile(file), "Locked artifact missing from libs: " + entry.getKey());
            var digest = java.security.MessageDigest.getInstance("SHA-512");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int read; (read = in.read(buffer)) != -1; ) digest.update(buffer, 0, read);
            }
            assertEquals(entry.getValue(), java.util.HexFormat.of().formatHex(digest.digest()),
                "Locked artifact hash mismatch: " + entry.getKey());
        }
        try (var files = Files.list(Path.of("libs"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".jar")).toList()) {
                assertTrue(expected.containsKey(file.getFileName().toString()), "Unlocked jar in libs: " + file);
            }
        }
    }

    @Test void yesSteveModelStaysWithoutBundledGeckoLibWhileTheDependencyStaysLocked() throws Exception {
        boolean bundled = false;
        try (var zip = new ZipFile(Path.of("libs", "ysm-2.6.3-neoforge+mc1.21.1-release.jar").toString())) {
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                String name = entries.nextElement().getName();
                if (name.startsWith("software/bernie/") && name.endsWith(".class")) { bundled = true; break; }
            }
        }
        assertFalse(bundled, "YSM now bundles GeckoLib; the externally locked geckolib artifact can be dropped");
        boolean locked = false;
        for (var item : JsonParser.parseString(Files.readString(Path.of("modpack.lock.json"))).getAsJsonArray()) {
            if (item.getAsJsonObject().get("project").getAsString().equals("geckolib")) locked = true;
        }
        assertTrue(locked, "geckolib must stay in the lockfile while YSM loads it as an external dependency");
    }
}
