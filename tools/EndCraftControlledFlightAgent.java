import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import com.sun.tools.attach.VirtualMachine;

/** A short normal elytra/rocket trial in the project's integrated server, with restoration. */
public class EndCraftControlledFlightAgent {
    public static void main(String[] args) throws Exception {
        var vm=VirtualMachine.attach(args[0]);try {vm.loadAgent(args[1]);} finally {vm.detach();}
    }
    static Object call(Object target,String name,Class<?>[] types,Object...args) throws Exception {
        return target.getClass().getMethod(name,types).invoke(target,args);
    }
    static Object call(Object target,String name) throws Exception {return call(target,name,new Class<?>[0]);}
    static void log(String text) {try {Files.writeString(Path.of("endcraft-flight-trial.txt"),text+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);} catch(Exception ignored) {}}
    public static void agentmain(String args,Instrumentation instrumentation) throws Exception {
        Class<?> type=null;for(var c:instrumentation.getAllLoadedClasses()) if(c.getName().equals("net.minecraft.client.Minecraft")) {type=c;break;}
        Object client=type.getMethod("getInstance").invoke(null);ClassLoader loader=type.getClassLoader();
        Files.writeString(Path.of("endcraft-flight-trial.txt"),"");
        call(client,"execute",new Class<?>[]{Runnable.class},(Runnable)()-> {
            try {
                Object player=client.getClass().getField("player").get(client);
                Class<?> sky=Class.forName("dev.skycraft.client.SkyClient",true,loader);
                if(!(boolean)sky.getMethod("linked").invoke(null)) throw new IllegalStateException("Bridge not linked");
                var hold=sky.getDeclaredField("holdPos");hold.setAccessible(true);
                if(hold.get(null)!=null) throw new IllegalStateException("Collision hold is active; do not run a flight trial");
                Object skyState=sky.getMethod("sky").invoke(null);
                int scene=(int)skyState.getClass().getField("collisionEpoch").get(skyState);
                double x=(double)call(player,"getX"),y=(double)call(player,"getY"),z=(double)call(player,"getZ");
                Object inventory=call(player,"getInventory");int selected=(int)call(inventory,"getSelectedSlot");
                Class<?> vec=Class.forName("net.minecraft.world.phys.Vec3",true,loader);
                Object falling=vec.getConstructor(double.class,double.class,double.class).newInstance(0,-.15,0);
                Object zero=vec.getField("ZERO").get(null);
                Object server=call(client,"getSingleplayerServer");Object uuid=call(player,"getUUID");
                log("baseline="+call(player,"position")+" selected="+selected);
                call(server,"execute",new Class<?>[]{Runnable.class},(Runnable)()-> {
                    try {
                        Object sp=call(call(server,"getPlayerList"),"getPlayer",new Class<?>[]{UUID.class},uuid);
                        call(sp,"teleportTo",new Class<?>[]{double.class,double.class,double.class},x,y+16,z);
                        call(sp,"setOnGround",new Class<?>[]{boolean.class},false);
                        call(sp,"setDeltaMovement",new Class<?>[]{vec},falling);
                        log("server_deploy="+call(sp,"tryToStartFallFlying"));
                        call(client,"execute",new Class<?>[]{Runnable.class},(Runnable)()-> {
                            try {
                                call(player,"setPos",new Class<?>[]{double.class,double.class,double.class},x,y+16,z);
                                call(player,"setOnGround",new Class<?>[]{boolean.class},false);
                                call(player,"setDeltaMovement",new Class<?>[]{vec},falling);
                                log("client_deploy="+call(player,"tryToStartFallFlying"));
                            }catch(Exception e) {log("launch_error="+e);}
                        });
                    }catch(Exception e) {log("server_launch_error="+e);}
                });
                Timer timer=new Timer("EndCraft flight trial",true);
                for(int i=1;i<=15;++i) {
                    final int sample=i;
                    timer.schedule(new TimerTask(){public void run(){
                        try {call(client,"execute",new Class<?>[]{Runnable.class},(Runnable)()-> {
                            try {
                                log("sample="+sample+" flying="+call(player,"isFallFlying")+" position="+call(player,"position")+" velocity="+call(player,"getDeltaMovement"));
                                if(sample==1&&(boolean)call(player,"isFallFlying")) {
                                    int size=(int)call(inventory,"getContainerSize");int rocket=-1;
                                    for(int slot=0;slot<Math.min(9,size);++slot) if(call(inventory,"getItem",new Class<?>[]{int.class},slot).toString().contains("minecraft:firework_rocket")) {rocket=slot;break;}
                                    if(rocket<0) {log("rocket=missing_from_hotbar");return;}
                                    call(inventory,"setSelectedSlot",new Class<?>[]{int.class},rocket);
                                    Class<?> basePlayer=Class.forName("net.minecraft.world.entity.player.Player",true,loader);
                                    Class<?> hand=Class.forName("net.minecraft.world.InteractionHand",true,loader);
                                    Object gameMode=client.getClass().getField("gameMode").get(client);
                                    log("rocket_before="+call(player,"getMainHandItem"));
                                    log("rocket_use="+call(gameMode,"useItem",new Class<?>[]{basePlayer,hand},player,hand.getField("MAIN_HAND").get(null)));
                                }
                            }catch(Exception e){log("sample_error="+e);}
                        });}catch(Exception e){log("timer_error="+e);}
                    }},i*200L);
                }
                timer.schedule(new TimerTask(){public void run(){
                    try {call(client,"execute",new Class<?>[]{Runnable.class},(Runnable)()-> {
                        try {
                            if((int)skyState.getClass().getField("collisionEpoch").get(skyState)!=scene) {log("restore=skipped_after_scene_change");return;}
                            log("rocket_after="+call(player,"getMainHandItem"));
                            call(player,"stopFallFlying");call(player,"setPos",new Class<?>[]{double.class,double.class,double.class},x,y,z);
                            call(player,"setDeltaMovement",new Class<?>[]{vec},zero);
                            call(inventory,"setSelectedSlot",new Class<?>[]{int.class},selected);
                            call(server,"execute",new Class<?>[]{Runnable.class},(Runnable)()-> {
                                try {
                                    Object sp=call(call(server,"getPlayerList"),"getPlayer",new Class<?>[]{UUID.class},uuid);
                                    call(sp,"stopFallFlying");call(sp,"teleportTo",new Class<?>[]{double.class,double.class,double.class},x,y,z);
                                    call(sp,"setDeltaMovement",new Class<?>[]{vec},zero);log("restore=complete");
                                }catch(Exception e){log("restore_server_error="+e);}
                            });
                        }catch(Exception e){log("restore_error="+e);}
                    });}catch(Exception e){log("restore_schedule_error="+e);} finally {timer.cancel();}
                }},3500L);
            }catch(Exception e){log("error="+e);}
        });
    }
}
