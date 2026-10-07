import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import com.sun.tools.attach.VirtualMachine;

/** Sets up a repeatable view in the project-owned development client. */
public class EndCraftSceneAgent {
    public static void main(String[] args) throws Exception {
        var vm=VirtualMachine.attach(args[0]);
        try {vm.loadAgent(args[1]);} finally {vm.detach();}
    }
    public static void agentmain(String args,Instrumentation instrumentation) throws Exception {
        Class<?> type=null;
        for(var c:instrumentation.getAllLoadedClasses()) if(c.getName().equals("net.minecraft.client.Minecraft")) {type=c;break;}
        if(type==null) throw new IllegalStateException("Target is not Minecraft");
        Object client=type.getMethod("getInstance").invoke(null);
        ClassLoader loader=type.getClassLoader();
        type.getMethod("execute",Runnable.class).invoke(client,(Runnable)()-> {
            String result;
            try {
                Object gui=client.getClass().getField("gui").get(client);
                Class<?> screen=Class.forName("net.minecraft.client.gui.screens.Screen",false,loader);
                gui.getClass().getMethod("setScreen",screen).invoke(gui,new Object[]{null});
                Object options=client.getClass().getField("options").get(client);
                Class<?> camera=Class.forName("net.minecraft.client.CameraType",true,loader);
                options.getClass().getMethod("setCameraType",camera).invoke(options,camera.getField("THIRD_PERSON_BACK").get(null));
                result="screen closed; camera="+options.getClass().getMethod("getCameraType").invoke(options);
            } catch(Exception e) {result="error="+e;}
            try {Files.writeString(Path.of("endcraft-scene-state.txt"),result);} catch(Exception ignored) {}
        });
    }
}
