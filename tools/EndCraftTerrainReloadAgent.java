import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reload only the project's terrain solver in its own development JVM. */
public class EndCraftTerrainReloadAgent {
    public static void main(String[] args) throws Exception {
        var vm = VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1], args[2]); } finally { vm.detach(); }
    }
    public static void agentmain(String path, Instrumentation inst) throws Exception {
        if (!inst.isRedefineClassesSupported()) throw new IllegalStateException("Class redefinition unavailable");
        Path source = Path.of(path).toRealPath();
        String targetName;
        if (source.endsWith(Path.of("mc/build/classes/java/main/dev/skycraft/world/SmoothTerrainCollision.class"))) targetName="dev.skycraft.world.SmoothTerrainCollision";
        else if (source.endsWith(Path.of("mc/build/classes/java/client/dev/skycraft/client/SkyDigClient.class"))) targetName="dev.skycraft.client.SkyDigClient";
        else throw new IllegalArgumentException("Only the two project terrain adapters are permitted");
        Class<?> target = null;
        for (var type : inst.getAllLoadedClasses())
            if (type.getName().equals(targetName)) target = type;
        if (target == null) throw new IllegalStateException("Project terrain solver not loaded");
        inst.redefineClasses(new ClassDefinition(target, Files.readAllBytes(source)));
    }
}
