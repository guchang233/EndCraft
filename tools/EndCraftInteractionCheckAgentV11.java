import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import com.sun.tools.attach.VirtualMachine;

/** Uses normal MC item, riding and attack methods in the project-owned integrated server. */
public class EndCraftInteractionCheckAgentV11 {
 static UUID testBoat;
 static ClassLoader loader;
 static Class<?> type(String name)throws Exception{return Class.forName(name,true,loader);}
 static Object invoke(Object object,String name,Object...args)throws Exception {
  for(var m:object.getClass().getMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){
   boolean fits=true;var ts=m.getParameterTypes();
   for(int i=0;i<ts.length;i++)if(args[i]!=null&&!ts[i].isInstance(args[i])&&!(ts[i].isPrimitive()&&args[i] instanceof Number)&&!(ts[i]==boolean.class&&args[i] instanceof Boolean))fits=false;
   if(fits)return m.invoke(object,args);
  }
  throw new NoSuchMethodException(object.getClass().getName()+"."+name);
 }
 static Object field(Object value,String name)throws Exception{for(Class<?> t=value.getClass();t!=null;t=t.getSuperclass())try{Field f=t.getDeclaredField(name);f.setAccessible(true);return f.get(value);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
 static void log(String text){try{Files.writeString(Path.of("endcraft-interaction-check.txt"),text+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception ignored){}}
 static List<Object> entities(Object level,String method)throws Exception{var result=new ArrayList<Object>();for(Object e:(Iterable<?>)invoke(level,method))result.add(e);return result;}
 static boolean boat(Object entity)throws Exception{return type("net.minecraft.world.entity.vehicle.boat.AbstractBoat").isInstance(entity);}
 static Object stack(String item)throws Exception{return type("net.minecraft.world.item.ItemStack").getConstructor(type("net.minecraft.world.level.ItemLike")).newInstance(type("net.minecraft.world.item.Items").getField(item).get(null));}
 static double number(Object object,String name)throws Exception{return ((Number)invoke(object,name)).doubleValue();}
 static void later(Object server,Runnable task,int delay){Timer t=new Timer("EndCraft interaction check",true);t.schedule(new TimerTask(){public void run(){try{invoke(server,"execute",task);}catch(Exception e){log("schedule_error="+e);}finally{t.cancel();}}},delay);}
 static void driveProbe(Object sp,Object e,Object server,Object client,Object player)throws Exception{
       if(!(Boolean)invoke(sp,"isWithinEntityInteractionRange",e,0.0))throw new IllegalStateException("New boat outside interaction reach");
       UUID ridingId=(UUID)invoke(e,"getUUID");log("auto_ride="+invoke(sp,"startRiding",e));Object selected=e;Object start=invoke(e,"position");
       later(client,()->{try{
         Object vehicle=invoke(player,"getVehicle");if(vehicle==null||!Objects.equals(ridingId,invoke(vehicle,"getUUID")))throw new IllegalStateException("Riding not synchronized");
         var dispatch=type("dev.skycraft.client.InputBridge").getDeclaredMethod("dispatch",client.getClass(),int.class,int.class,int.class,int.class,int.class);dispatch.setAccessible(true);dispatch.invoke(null,client,1,26,1,0,0);
         later(client,()->{try{log("drive_input up="+invoke(field(client.getClass().getField("options").get(client),"keyUp"),"isDown")+" boatUp="+field(vehicle,"inputUp")+" status="+field(vehicle,"status")+" friction="+field(vehicle,"landFriction")+" velocity="+invoke(vehicle,"getDeltaMovement"));dispatch.invoke(null,client,1,26,0,0,0);}catch(Exception ex){log("drive_release_error="+ex);}},800);
       }catch(Exception ex){log("auto_drive_error="+ex);}},450);
       later(server,()->{try{log("auto_drive_start="+start+" end="+invoke(selected,"position")+" vehicle="+invoke(sp,"getVehicle"));}catch(Exception ex){log("drive_sample_error="+ex);}},1500);
       later(client,()->{try{var dispatch=type("dev.skycraft.client.InputBridge").getDeclaredMethod("dispatch",client.getClass(),int.class,int.class,int.class,int.class,int.class);dispatch.setAccessible(true);dispatch.invoke(null,client,1,225,1,0,0);later(client,()->{try{dispatch.invoke(null,client,1,225,0,0,0);}catch(Exception ex){log("unride_release_error="+ex);}},150);}catch(Exception ex){log("unride_error="+ex);}},1800);
 }
 public static void main(String[] a)throws Exception{var vm=VirtualMachine.attach(a[0]);try{vm.loadAgent(a[1],a[2]);}finally{vm.detach();}}
 public static void agentmain(String op,Instrumentation inst)throws Exception{
  if(!Set.of("status","boat","boat_drive","ride_drive","ride","drive","unride","melee","bow","cleanup").contains(op))throw new IllegalArgumentException("Unsupported operation");
  Class<?> mc=null;for(var c:inst.getAllLoadedClasses())if(c.getName().equals("net.minecraft.client.Minecraft")){mc=c;break;}
  if(mc==null)throw new IllegalStateException("Not Minecraft");loader=mc.getClassLoader();Object client=mc.getMethod("getInstance").invoke(null);
  invoke(client,"execute",(Runnable)()->{try{
   Object player=client.getClass().getField("player").get(client),server=invoke(client,"getSingleplayerServer");
   if(player==null||server==null)throw new IllegalStateException("Own integrated player required");
   Object level=client.getClass().getField("level").get(client);
   log("operation="+op+" client_position="+invoke(player,"position")+" vehicle="+invoke(player,"getVehicle")+" entities="+entities(level,"entitiesForRendering").stream().map(Object::toString).toList());
   Object gui=client.getClass().getField("gui").get(client);
   if(!op.equals("status")&&invoke(gui,"screen")!=null)throw new IllegalStateException("Preserve the user's open screen");
   if(Set.of("drive","ride_drive","boat_drive").contains(op)){
    Object hostState=type("dev.skycraft.link.SkyLink$SkyState").getConstructor().newInstance();
    type("dev.skycraft.link.SkyLink").getMethod("readSkyState",hostState.getClass()).invoke(null,hostState);
    if((Boolean)invoke(hostState,"menuOpen"))throw new IllegalStateException("Keep the actual game in the foreground for input validation; background input is intentionally released");
   }
   if(op.equals("drive")){
    Object vehicle=invoke(player,"getVehicle");if(vehicle==null||!Objects.equals(testBoat,invoke(vehicle,"getUUID")))throw new IllegalStateException("Drive only while riding this tool test boat");
    Class<?> bridge=type("dev.skycraft.client.InputBridge");var dispatch=bridge.getDeclaredMethod("dispatch",client.getClass(),int.class,int.class,int.class,int.class,int.class);dispatch.setAccessible(true);
    dispatch.invoke(null,client,1,26,1,0,0);
    later(client,()->{try{log("drive_input up="+invoke(field(client.getClass().getField("options").get(client),"keyUp"),"isDown")+" boatUp="+field(vehicle,"inputUp")+" status="+field(vehicle,"status")+" friction="+field(vehicle,"landFriction")+" velocity="+invoke(vehicle,"getDeltaMovement"));dispatch.invoke(null,client,1,26,0,0,0);}catch(Exception e){log("drive_error="+e);}},600);return;
   }
   UUID uuid=(UUID)invoke(player,"getUUID");
   invoke(server,"execute",(Runnable)()->{try{
    Object sp=invoke(invoke(server,"getPlayerList"),"getPlayer",uuid),sl=invoke(sp,"level");
    List<Object> es=entities(sl,"getAllEntities");Object ownBoat=null;
    for(Object e:es)if(boat(e)&&Objects.equals(testBoat,invoke(e,"getUUID")))ownBoat=e;
    log("server_health="+invoke(sp,"getHealth")+" position="+invoke(sp,"position")+" vehicle="+invoke(sp,"getVehicle")+" test_boat="+ownBoat+" build_limits="+invoke(sl,"getMinY")+".."+invoke(sl,"getMaxY"));
    if(op.equals("status")){for(Object e:es)if(boat(e))log("server_boat="+e+" ground="+type("dev.skycraft.client.SkyCollider").getMethod("groundAt",double.class,double.class,double.class,double.class).invoke(null,number(e,"getX"),number(e,"getY"),number(e,"getZ"),2.0)+" down="+type("dev.skycraft.world.SmoothTerrainCollision").getMethod("collide",type("net.minecraft.world.entity.Entity"),type("net.minecraft.world.phys.Vec3")).invoke(null,e,type("net.minecraft.world.phys.Vec3").getConstructor(double.class,double.class,double.class).newInstance(0.0,-0.1,0.0))+" onGround="+invoke(e,"onGround")+" velocity="+invoke(e,"getDeltaMovement")+" smooth="+type("dev.skycraft.world.SkyCollision").getMethod("usesSmoothCollider",type("net.minecraft.world.entity.Entity")).invoke(null,e));return;}
    if(op.equals("cleanup")){if(ownBoat!=null)invoke(ownBoat,"discard");testBoat=null;return;}
    if(op.equals("ride_drive")){
     Object nearest=null;double range=25;for(Object e:es)if(boat(e)){double distance=((Number)invoke(sp,"distanceToSqr",e)).doubleValue();if(distance<range){nearest=e;range=distance;}}
     if(nearest==null)throw new IllegalStateException("No boat within creative interaction reach");
     driveProbe(sp,nearest,server,client,player);return;
    }
    if(op.equals("unride")){invoke(sp,"stopRiding");return;}
    if(op.equals("ride")){if(ownBoat==null)throw new IllegalStateException("No test boat");if(!(Boolean)invoke(sp,"isWithinEntityInteractionRange",ownBoat,0.0))throw new IllegalStateException("Test boat outside normal interaction range");log("ride_result="+invoke(sp,"startRiding",ownBoat));return;}
    Object hand=type("net.minecraft.world.InteractionHand").getField("MAIN_HAND").get(null),old=invoke(invoke(sp,"getMainHandItem"),"copy");
    if(op.equals("boat")||op.equals("boat_drive")){
     Set<UUID> before=new HashSet<>();for(Object e:es)if(boat(e))before.add((UUID)invoke(e,"getUUID"));
     float pitch=((Number)invoke(sp,"getXRot")).floatValue(),yaw=((Number)invoke(sp,"getYRot")).floatValue();Object item=stack("OAK_BOAT");
     try{invoke(sp,"setItemInHand",hand,item);invoke(sp,"setXRot",35f);
     var pov=type("net.minecraft.world.item.Item").getDeclaredMethod("getPlayerPOVHitResult",type("net.minecraft.world.level.Level"),type("net.minecraft.world.entity.player.Player"),type("net.minecraft.world.level.ClipContext$Fluid"));pov.setAccessible(true);
     for(int angle=0;angle<360;angle+=45){invoke(sp,"setYRot",yaw+angle);
     Object hit=pov.invoke(null,sl,sp,type("net.minecraft.world.level.ClipContext$Fluid").getField("ANY").get(null));
     var create=type("net.minecraft.world.item.BoatItem").getDeclaredMethod("getBoat",type("net.minecraft.world.level.Level"),type("net.minecraft.world.phys.HitResult"),type("net.minecraft.world.item.ItemStack"),type("net.minecraft.world.entity.player.Player"));create.setAccessible(true);
     Object candidate=create.invoke(invoke(item,"getItem"),sl,hit,item,sp),box=invoke(candidate,"getBoundingBox");
     log("boat_hit="+hit+" box="+box+" clear="+invoke(sl,"noCollision",candidate,box)+" smooth="+type("dev.skycraft.world.SkyCollision").getMethod("usesSmoothCollider",type("net.minecraft.world.entity.Entity")).invoke(null,candidate));
     for(Object shape:(Iterable<?>)invoke(sl,"getBlockCollisions",candidate,box))log("blocking_shape="+shape);
     if((Boolean)invoke(sl,"noCollision",candidate,box)){log("boat_use_result="+invoke(invoke(item,"getItem"),"use",sl,sp,hand));break;}
     }}
     finally{invoke(sp,"setItemInHand",hand,old);invoke(sp,"setXRot",pitch);invoke(sp,"setYRot",yaw);}
     for(Object e:entities(sl,"getAllEntities"))if(boat(e)&&!before.contains(invoke(e,"getUUID"))){testBoat=(UUID)invoke(e,"getUUID");log("placed_boat="+e+" position="+invoke(e,"position"));if(op.equals("boat_drive")){
       driveProbe(sp,e,server,client,player);
     }}
     return;
    }
    Object target=null;double limit=op.equals("melee")?3*3:32*32;
    for(Object e:es)if(type("dev.skycraft.combat.SkyrimActorEntity").isInstance(e)){
     double d=((Number)invoke(sp,"distanceToSqr",e)).doubleValue();if(d<limit){limit=d;target=e;}
    }
    if(target==null)throw new IllegalStateException("No hostile MC proxy within normal test range");
    log("attack_target="+target+" distance="+Math.sqrt(limit)+" host_id="+invoke(target,"formId"));
    if(op.equals("melee")){
     try{invoke(sp,"setItemInHand",hand,stack("DIAMOND_SWORD"));invoke(sp,"attack",target);log("normal_player_attack_finished");}
     finally{invoke(sp,"setItemInHand",hand,old);}return;
    }
    double dx=number(target,"getX")-number(sp,"getX"),dy=number(target,"getY")+number(target,"getBbHeight")*.5-number(sp,"getEyeY"),dz=number(target,"getZ")-number(sp,"getZ");
    float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz)),pitch=(float)-Math.toDegrees(Math.atan2(dy+(dx*dx+dz*dz)*.05/(2*3*3),Math.hypot(dx,dz)));
    invoke(sp,"setItemInHand",hand,stack("BOW"));
    later(client,()->{try{
      var d=type("dev.skycraft.client.InputBridge").getDeclaredMethod("dispatch",client.getClass(),int.class,int.class,int.class,int.class,int.class);d.setAccessible(true);d.invoke(null,client,2,3,1,0,0);
    }catch(Exception e){log("bow_press_error="+e);}},300);

    later(server,()->{try{invoke(sp,"setYRot",yaw);invoke(sp,"setXRot",pitch);invoke(sp,"releaseUsingItem");later(client,()->{try{var d=type("dev.skycraft.client.InputBridge").getDeclaredMethod("dispatch",client.getClass(),int.class,int.class,int.class,int.class,int.class);d.setAccessible(true);d.invoke(null,client,2,3,0,0,0);}catch(Exception e){log("bow_release_error="+e);}},1);log("normal_bow_release_finished");}catch(Exception e){log("bow_error="+e);}finally{try{invoke(sp,"setItemInHand",hand,old);}catch(Exception e){log("restore_error="+e);}}},1300);
   }catch(Exception e){log("server_error="+e);}});
  }catch(Exception e){log("client_error="+e);}});
 }
}
