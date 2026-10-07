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
        if (!source.endsWith(Path.of("mc/build/classes/java/main/dev/skycraft/world/SmoothTerrainCollision.class")))
            throw new IllegalArgumentException("Only the project terrain solver is permitted");
        Class<?> target = null;
        for (var type : inst.getAllLoadedClasses())
            if (type.getName().equals("dev.skycraft.world.SmoothTerrainCollision")) target = type;
        if (target == null) throw new IllegalStateException("Project terrain solver not loaded");
        inst.redefineClasses(new ClassDefinition(target, Files.readAllBytes(source)));
    }
}
