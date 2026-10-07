import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import com.sun.tools.attach.VirtualMachine;

/** Read-only diagnostics of the project-owned guest's actual render state. */
public class EndCraftCollisionAgent {
    public static void main(String[] args) throws Exception {
        var vm=VirtualMachine.attach(args[0]);
        try {vm.loadAgent(args[1]);} finally {vm.detach();}
    }
    static Object field(Object object,String name) throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    public static void agentmain(String args,Instrumentation instrumentation) throws Exception {
        Class<?> type=null;
        for(var c:instrumentation.getAllLoadedClasses()) if(c.getName().equals("net.minecraft.client.Minecraft")) {type=c;break;}
        if(type==null) throw new IllegalStateException("Target is not Minecraft");
        Object client=type.getMethod("getInstance").invoke(null);
        ClassLoader loader=type.getClassLoader();
        type.getMethod("execute",Runnable.class).invoke(client,(Runnable)()-> {
            StringBuilder result=new StringBuilder();
            try {
                Object player=client.getClass().getField("player").get(client);
                Object options=client.getClass().getField("options").get(client);
                Object renderer=client.getClass().getField("gameRenderer").get(client);
                Object camera=renderer.getClass().getMethod("mainCamera").invoke(renderer);
                result.append("cameraType=").append(options.getClass().getMethod("getCameraType").invoke(options));
                result.append("\ndetached=").append(camera.getClass().getMethod("isDetached").invoke(camera));
                result.append("\nplayer=").append(player);
                if(player!=null) result.append("\nposition=").append(player.getClass().getMethod("position").invoke(player));
                Class<?> collision=Class.forName("dev.skycraft.world.SkyCollision",false,loader);
                for(String metric:new String[]{"blockCount","regionCount","triangleCount"}) result.append("\n").append(metric).append("=").append(collision.getMethod(metric).invoke(null));
                Class<?> sky=Class.forName("dev.skycraft.client.SkyClient",false,loader);
                for(String metric:new String[]{"linked","holdPos","teleportAck"}) {Field f=sky.getDeclaredField(metric);f.setAccessible(true);result.append("\n").append(metric).append("=").append(f.get(null));}
                Class<?> exporter=Class.forName("dev.skycraft.client.render.AvatarExporter",false,loader);
                Field avatar=exporter.getDeclaredField("AVATAR");avatar.setAccessible(true);
                Object instance=avatar.get(null);
                result.append("\nshown=").append(field(instance,"shown"));
                var batches=(java.util.Map<?,?>)field(instance,"batches");
                for(var batch:batches.values()) result.append("\nbatch texture=").append(field(batch,"texture")).append(" vertices=").append(field(batch,"count"));
            } catch(Exception e) {result.append("\nerror=").append(e);}
            try {Files.writeString(Path.of("endcraft-collision-state.txt"),result);} catch(Exception ignored) {}
        });
    }
}
