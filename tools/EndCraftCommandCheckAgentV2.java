import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import com.sun.tools.attach.VirtualMachine;
/** Exercises project MC chat through the same key/text dispatch used by the host bridge. */
public class EndCraftCommandCheckAgentV2 {
 static String originalMode;
 public static void main(String[] a)throws Exception{var vm=VirtualMachine.attach(a[0]);try{vm.loadAgent(a[1],a[2]);}finally{vm.detach();}}
 static Object call(Object o,String name,Class<?>[] t,Object...a)throws Exception{return o.getClass().getMethod(name,t).invoke(o,a);}
 static Object call(Object o,String name)throws Exception{return call(o,name,new Class<?>[0]);}
 static void log(String s){try{Files.writeString(Path.of("endcraft-command-check.txt"),s+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception ignored){}}
 public static void agentmain(String op,Instrumentation inst)throws Exception{
  if(!Set.of("status","begin","creative","survival","adventure","recover","kill","restore").contains(op))throw new IllegalArgumentException("Unsupported test operation");
  Class<?> type=null;for(var c:inst.getAllLoadedClasses())if(c.getName().equals("net.minecraft.client.Minecraft")){type=c;break;}
  Object client=type.getMethod("getInstance").invoke(null);ClassLoader loader=type.getClassLoader();
  call(client,"execute",new Class<?>[]{Runnable.class},(Runnable)()->{try{
   Object player=client.getClass().getField("player").get(client);if(player==null)throw new IllegalStateException("Player not ready");
   Object server=call(client,"getSingleplayerServer");if(server==null)throw new IllegalStateException("Own integrated server required");
   Object uuid=call(player,"getUUID");
   call(server,"execute",new Class<?>[]{Runnable.class},(Runnable)()->{try{
    Object sp=call(call(server,"getPlayerList"),"getPlayer",new Class<?>[]{UUID.class},uuid);
    Object mode=sp.getClass().getField("gameMode").get(sp);String name=String.valueOf(call(call(mode,"getGameModeForPlayer"),"getName"));
    if(originalMode==null||op.equals("begin"))originalMode=name;
    log("operation="+op+" server_mode="+name+" health="+call(sp,"getHealth")+" dead="+call(sp,"isDeadOrDying")+" position="+call(sp,"position")+" entity_id="+call(sp,"getId"));
   }catch(Exception e){log("server_error="+e);}});
   if(op.equals("status")||op.equals("begin"))return;
   Object gui=client.getClass().getField("gui").get(client);if(call(gui,"screen")!=null)throw new IllegalStateException("Preserve the existing screen; close it before command testing");
   String text=switch(op){case "recover"->"endcraft recover";case "kill"->"kill @s";case "restore"->{if(originalMode==null)throw new IllegalStateException("Original mode not sampled");yield "gamemode "+originalMode;}default->"gamemode "+op;};
   Class<?> bridge=Class.forName("dev.skycraft.client.InputBridge",true,loader);var dispatch=bridge.getDeclaredMethod("dispatch",client.getClass(),int.class,int.class,int.class,int.class,int.class);dispatch.setAccessible(true);
   dispatch.invoke(null,client,1,56,1,0,0);dispatch.invoke(null,client,1,56,0,0,0);
   Timer timer=new Timer("EndCraft chat check",true);
   timer.schedule(new TimerTask(){public void run(){try{call(client,"execute",new Class<?>[]{Runnable.class},(Runnable)()->{try{
    Object screen=call(gui,"screen");if(screen==null||!screen.getClass().getName().contains("ChatScreen"))throw new IllegalStateException("Slash did not open chat: "+screen);
    for(int c:text.codePoints().toArray())dispatch.invoke(null,client,5,0,c,0,0);
    log("submitted=/"+text+" screen="+screen);
    dispatch.invoke(null,client,1,40,1,0,0);dispatch.invoke(null,client,1,40,0,0,0);
   }catch(Exception e){log("chat_error="+e);}});}catch(Exception e){log("schedule_error="+e);}finally{timer.cancel();}}},400);
  }catch(Exception e){log("client_error="+e);}});
 }
}
