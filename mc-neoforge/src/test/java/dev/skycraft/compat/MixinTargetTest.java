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
                else if (name.endsWith(".class") && (name.startsWith("dev/engine_room/") || name.startsWith("dev/ryanhcode/")))
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
                                Object ats = v.get("at");
                                for (var atValue : list(ats)) {
                                    var at = values((AnnotationNode) atValue);
                                    if (!"INVOKE".equals(at.get("value"))) continue;
                                    String signature = at.get("target").toString();
                                    boolean found = false;
                                    for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
                                        if (signature.equals("L" + call.owner + ";" + call.name + call.desc)) found = true;
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
}
