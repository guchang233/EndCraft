import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;

/** Scoped checks in EndCraft's own integrated MC instance. Test vehicles only are removed. */
public class EndCraftVehicleCheckAgentV6 {
 static ClassLoader loader; static UUID testVehicle;
 static Class<?> type(String n)throws Exception{return Class.forName(n,true,loader);}
 static Object call(Object o,String n,Object...args)throws Exception {
  for(var m:o.getClass().getMethods()) if(m.getName().equals(n)&&m.getParameterCount()==args.length){
   boolean fit=true;var ps=m.getParameterTypes();for(int i=0;i<ps.length;i++)if(args[i]!=null&&!ps[i].isInstance(args[i])&&!ps[i].isPrimitive())fit=false;
   if(fit)try{return m.invoke(o,args);}catch(IllegalArgumentException ex){}
  }throw new NoSuchMethodException(o.getClass()+"."+n);
 }
 static void log(String s){try{Files.writeString(Path.of("endcraft-vehicle-check.txt"),s+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception ignored){}}
 static List<Object> all(Object level)throws Exception{var a=new ArrayList<Object>();for(Object e:(Iterable<?>)call(level,"getAllEntities"))a.add(e);return a;}
 static double num(Object o,String n)throws Exception{return ((Number)call(o,n)).doubleValue();}
 static void later(Object server,Runnable action,int ms){var timer=new Timer("EndCraft vehicle verification",true);timer.schedule(new TimerTask(){public void run(){try{call(server,"execute",action);}catch(Exception ex){log("schedule_error="+ex);}finally{timer.cancel();}}},ms);}
 public static void main(String[] a)throws Exception{var v=VirtualMachine.attach(a[0]);try{v.loadAgent(a[1],a[2]);}finally{v.detach();}}
 public static void agentmain(String op,Instrumentation inst)throws Exception{
  if(!Set.of("status","boat","minecart","cleanup","terrain","tnt","player_exit").contains(op))throw new IllegalArgumentException("operation");
  Class<?> mc=null;for(Class<?> c:inst.getAllLoadedClasses())if(c.getName().equals("net.minecraft.client.Minecraft")){mc=c;break;}
  if(mc==null)throw new IllegalStateException("MC required");loader=mc.getClassLoader();Object client=mc.getMethod("getInstance").invoke(null);
  call(client,"execute",(Runnable)()->{try{
   Object player=client.getClass().getField("player").get(client),server=call(client,"getSingleplayerServer");if(player==null||server==null)throw new IllegalStateException("owned integrated server required");UUID uid=(UUID)call(player,"getUUID");
   call(server,"execute",(Runnable)()->{try{
    Object sp=call(call(server,"getPlayerList"),"getPlayer",uid),level=call(sp,"level");var es=all(level);
    Object own=null;for(Object e:es)if(Objects.equals(testVehicle,call(e,"getUUID")))own=e;
    if(op.equals("cleanup")){if(own!=null)call(own,"discard");testVehicle=null;log("test_vehicle_cleaned");return;}
    if(op.equals("terrain")||op.equals("tnt")){terrainCheck(op,sp,level,server);return;}
    if(op.equals("player_exit")){
     if(own!=null||call(sp,"getVehicle")!=null)throw new IllegalStateException("Preserve current riding state");
     Object gui=client.getClass().getField("gui").get(client);if(call(gui,"screen")!=null)throw new IllegalStateException("Preserve the user screen");
     Object registry=type("net.minecraft.core.registries.BuiltInRegistries").getField("ENTITY_TYPE").get(null);
     Object key=type("net.minecraft.resources.Identifier").getMethod("fromNamespaceAndPath",String.class,String.class).invoke(null,"minecraft","oak_boat");
     Object et=call(registry,"getValue",key),boat=call(et,"create",level,type("net.minecraft.world.entity.EntitySpawnReason").getField("COMMAND").get(null));
     double x=num(sp,"getX")+1.3,z=num(sp,"getZ"),y=num(sp,"getY");
     double floor=((Number)type("dev.skycraft.client.SkyCollider").getMethod("groundAt",double.class,double.class,double.class,double.class).invoke(null,x,y,z,2.0)).doubleValue();
     if(!Double.isFinite(floor))throw new IllegalStateException("Need native ground at the player for the exit check");
     call(boat,"setPos",x,floor+.02,z);
     if(!(Boolean)call(level,"noCollision",boat,call(boat,"getBoundingBox")))throw new IllegalStateException("Keep occupied block space");
     if(!(Boolean)call(level,"addFreshEntity",boat))throw new IllegalStateException("spawn failed");testVehicle=(UUID)call(boat,"getUUID");
     log("player_ride="+call(sp,"startRiding",boat)+" hull="+call(boat,"position"));
     later(server,()->{try{call(sp,"stopRiding");double h=((Number)type("dev.skycraft.client.SkyCollider").getMethod("groundAt",double.class,double.class,double.class,double.class).invoke(null,num(sp,"getX"),num(sp,"getY"),num(sp,"getZ"),3.0)).doubleValue();log("player_exit position="+call(sp,"position")+" native_floor="+h+" vehicle="+call(sp,"getVehicle"));}catch(Exception e){log("exit_error="+e);}},1500);
     later(server,()->{try{log("player_exit_settled position="+call(sp,"position")+" vehicle="+call(sp,"getVehicle"));call(boat,"discard");testVehicle=null;}catch(Exception e){log("exit_error="+e);}},3500);
     return;
    }
    for(Object e:es)if(type("dev.skycraft.combat.SkyrimActorEntity").isInstance(e))log("actor="+call(e,"formId")+" position="+call(e,"position")+" riding="+call(e,"getVehicle"));
    if(op.equals("status")){log("test_vehicle="+own+" passengers="+(own==null?"none":call(own,"getPassengers")));
    var list=new ArrayList<Object>();type("dev.skycraft.link.SkyLink").getMethod("readActors",List.class).invoke(null,list);for(Object a:list)log("record="+a);return;}
    if(own!=null)throw new IllegalStateException("Clean up existing test vehicle first");
    var actors=new ArrayList<Object>();type("dev.skycraft.link.SkyLink").getMethod("readActors",List.class).invoke(null,actors);
    Object proxy=null;double nearest=48*48;
    for(Object a:actors){if((((Number)call(a,"flags")).intValue()&16)==0)continue;
     Object e=type("dev.skycraft.combat.SkyCombat").getMethod("proxy",int.class).invoke(null,((Number)call(a,"formId")).intValue());
     if(e==null||call(e,"getVehicle")!=null)continue;double d=((Number)call(e,"distanceToSqr",sp)).doubleValue();if(d<nearest){nearest=d;proxy=e;}}
    if(proxy==null)throw new IllegalStateException("No unmounted native creature within 48 blocks");
    String kind=op.equals("boat")?"OAK_BOAT":"MINECART";
    Object registry=type("net.minecraft.core.registries.BuiltInRegistries").getField("ENTITY_TYPE").get(null);
    Object key=type("net.minecraft.resources.Identifier").getMethod("fromNamespaceAndPath",String.class,String.class).invoke(null,"minecraft",kind.toLowerCase());
    Object entityType=call(registry,"getValue",key);
    Object vehicle=call(entityType,"create",level,type("net.minecraft.world.entity.EntitySpawnReason").getField("COMMAND").get(null));
    if(vehicle==null)throw new IllegalStateException("Entity registry create failed");
    call(vehicle,"setPos",num(proxy,"getX"),num(proxy,"getY"),num(proxy,"getZ"));
    // Keep the test hull at the real actor's surface outside the streamed player terrain.
    call(vehicle,"setNoGravity",true);
    if(!(Boolean)call(level,"noCollision",vehicle,call(vehicle,"getBoundingBox")))throw new IllegalStateException("Preserve occupied block space");
    if(!(Boolean)call(level,"addFreshEntity",vehicle))throw new IllegalStateException("spawn failed");testVehicle=(UUID)call(vehicle,"getUUID");Object selected=proxy;
    log("spawned="+kind+" vehicle="+call(vehicle,"getId")+" host_actor="+call(selected,"formId")+" position="+call(vehicle,"position"));
    later(server,()->{try{log("auto_mount vehicle="+call(selected,"getVehicle")+" passengers="+call(vehicle,"getPassengers")+" position="+call(selected,"position"));
     call(vehicle,"setDeltaMovement",type("net.minecraft.world.phys.Vec3").getConstructor(double.class,double.class,double.class).newInstance(.2,0.0,0.0));
    }catch(Exception ex){log("mount_error="+ex);}},1500);
    later(server,()->{try{log("follow vehicle="+call(vehicle,"position")+" actor="+call(selected,"position")+" riding="+call(selected,"getVehicle"));}catch(Exception ex){log("follow_error="+ex);}},3000);
   }catch(Exception ex){log("server_error="+ex);}});
  }catch(Exception ex){log("client_error="+ex);}});
 }
 static Object pos(int x,int y,int z)throws Exception{return type("net.minecraft.core.BlockPos").getConstructor(int.class,int.class,int.class).newInstance(x,y,z);}
 static Map<String,Integer> blocks(Object level,int cx,int cy,int cz,int radius)throws Exception{
  Map<String,Integer> result=new TreeMap<>();for(int y=cy-radius;y<=cy+radius;y++)for(int z=cz-radius;z<=cz+radius;z++)for(int x=cx-radius;x<=cx+radius;x++){
   Object state=call(level,"getBlockState",pos(x,y,z));if(!(Boolean)call(state,"isAir"))result.merge(state.toString(),1,Integer::sum);
  }return result;
 }
 static int dug(Object level,int cx,int cz,int radius)throws Exception{
  int result=0;var seen=new HashSet<String>();for(int z=cz-radius;z<=cz+radius;z+=16)for(int x=cx-radius;x<=cx+radius;x+=16){
   Object chunk=call(level,"getChunkAt",pos(x,64,z));String key=call(chunk,"getPos").toString();if(!seen.add(key))continue;
   Object col=type("dev.skycraft.world.SkyDig").getMethod("column",type("net.minecraft.world.level.chunk.LevelChunk")).invoke(null,chunk);
   for(Object section:(List<?>)call(col,"sections"))for(long bits:(long[])call(section,"bits"))result+=Long.bitCount(bits);
  }return result;
 }
 static void terrainCheck(String op,Object player,Object level,Object server)throws Exception{
  int px=(int)Math.floor(num(player,"getX")),py=(int)Math.floor(num(player,"getY")),pz=(int)Math.floor(num(player,"getZ"));
  if(op.equals("terrain")){log("terrain blocks="+blocks(level,px,py,pz,10)+" dug="+dug(level,px,pz,24));return;}
  int bx=0,bz=0;boolean clear=false;
  for(int[] offset:new int[][]{{12,0},{-12,0},{0,12},{0,-12},{12,12},{-12,12},{12,-12},{-12,-12},{18,0},{-18,0},{0,18},{0,-18},{24,0},{-24,0},{0,24},{0,-24},{24,24},{-24,24},{24,-24},{-24,-24},{32,0},{-32,0},{0,32},{0,-32}}){
   bx=px+offset[0];bz=pz+offset[1];if(!blocks(level,bx,py,bz,7).isEmpty())continue;
   boolean nearby=false;for(Object e:all(level))if(type("dev.skycraft.combat.SkyrimActorEntity").isInstance(e)){double dx=num(e,"getX")-bx,dz=num(e,"getZ")-bz;if(dx*dx+dz*dz<10*10)nearby=true;}
   if(!nearby){clear=true;break;}}
  if(!clear)throw new IllegalStateException("No isolated air area for reversible TNT test");
  int x=bx,z=bz,y=py;Object at=pos(x+1,y,z),old=call(level,"getBlockState",at);
  Object wool=call(type("net.minecraft.world.level.block.Blocks").getField("OAK_PLANKS").get(null),"defaultBlockState");
  call(level,"setBlockAndUpdate",at,wool);int before=dug(level,x,z,24);log("tnt_before dug="+before+" blocks="+blocks(level,x,y,z,7));
  Object tnt=type("net.minecraft.world.entity.item.PrimedTnt").getConstructor(type("net.minecraft.world.level.Level"),double.class,double.class,double.class,type("net.minecraft.world.entity.LivingEntity")).newInstance(level,x+.5,y+.1,z+.5,player);
  call(tnt,"setFuse",10);call(level,"addFreshEntity",tnt);
  later(server,()->{try{log("tnt_after dug="+dug(level,x,z,24)+" blocks="+blocks(level,x,y,z,7)+" test_block_destroyed="+call(call(level,"getBlockState",at),"isAir"));call(level,"setBlockAndUpdate",at,old);}catch(Exception ex){log("tnt_error="+ex);}},3000);
 }
}
