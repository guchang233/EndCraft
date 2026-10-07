import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import com.sun.tools.attach.VirtualMachine;

/** Supported JVM attach, restricted to this project's running Minecraft development client. */
public class EndCraftDebugAgent {
    public static void main(String[] args) throws Exception {
        if(args.length!=3) throw new IllegalArgumentException("MC PID, agent JAR and operation required");
        var vm=VirtualMachine.attach(args[0]);
        try {vm.loadAgent(args[1],args[2]);} finally {vm.detach();}
    }
    public static void agentmain(String operation,Instrumentation instrumentation) throws Exception {
        if(!java.util.Set.of("status","third_person","test_block","stop").contains(operation)) throw new IllegalArgumentException("Unknown operation");
        Class<?> minecraft=null;
        for(var type:instrumentation.getAllLoadedClasses()) if(type.getName().equals("net.minecraft.client.Minecraft")) {minecraft=type;break;}
        if(minecraft==null) throw new IllegalStateException("Target is not Minecraft");
        Object client=minecraft.getMethod("getInstance").invoke(null);
        ClassLoader loader=minecraft.getClassLoader();
        minecraft.getMethod("execute",Runnable.class).invoke(client,(Runnable)()-> {
            try {
                Object player=client.getClass().getField("player").get(client);
                Object options=client.getClass().getField("options").get(client);
                if(operation.equals("stop")) {client.getClass().getMethod("stop").invoke(client);return;}
                if(player==null) throw new IllegalStateException("MC player not ready");
                if(operation.equals("third_person")) {
                    Class<?> camera=Class.forName("net.minecraft.client.CameraType",true,loader);
                    options.getClass().getMethod("setCameraType",camera).invoke(options,camera.getField("THIRD_PERSON_BACK").get(null));
                }
                if(operation.equals("test_block")) {
                    Object server=client.getClass().getMethod("getSingleplayerServer").invoke(client);
                    Object uuid=player.getClass().getMethod("getUUID").invoke(player);
                    server.getClass().getMethod("execute",Runnable.class).invoke(server,(Runnable)()-> {
                        try {
                            Object list=server.getClass().getMethod("getPlayerList").invoke(server);
                            Object sp=list.getClass().getMethod("getPlayer",java.util.UUID.class).invoke(list,uuid);
                            Object level=sp.getClass().getMethod("level").invoke(sp);
                            Class<?> pos=Class.forName("net.minecraft.core.BlockPos",true,loader);
                            Object blockPos=pos.getConstructor(int.class,int.class,int.class).newInstance(0,64,-2);
                            Object block=Class.forName("net.minecraft.world.level.block.Blocks",true,loader).getField("OAK_PLANKS").get(null);
                            Object state=block.getClass().getMethod("defaultBlockState").invoke(block);
                            level.getClass().getMethod("setBlockAndUpdate",pos,state.getClass()).invoke(level,blockPos,state);
                        } catch(Exception error) {write("test_block_error: "+error);}
                    });
                }
                Object stack=player.getClass().getMethod("getMainHandItem").invoke(player);
                Object inventory=player.getClass().getMethod("getInventory").invoke(player);
                StringBuilder state=new StringBuilder("operation="+operation+"\nhand="+stack+"\ncamera="+
                    options.getClass().getMethod("getCameraType").invoke(options)+"\ninventory_methods=");
                for(var method:inventory.getClass().getMethods()) if(method.getName().toLowerCase().contains("select")) state.append(method).append('\n');
                write(state.toString());
            } catch(Exception error) {write("error: "+error);}
        });
    }
    private static void write(String value) {
        try {Files.writeString(Path.of("endcraft-agent-status.txt"),value);} catch(Exception ignored) {}
    }
}
