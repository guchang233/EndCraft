import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import com.sun.tools.attach.VirtualMachine;

/** Diagnostics through the supported JVM attach API, restricted by the wrapper to our NeoForge guest. */
public class EndCraftNeoTestAgent {
    static ClassLoader loader;
    static Object call(Object o, String method, Object... args) throws Exception {
        Class<?> cls = o instanceof Class<?> c ? c : o.getClass();
        for (Method m : cls.getMethods()) {
            if (!m.getName().equals(method) || m.getParameterCount() != args.length) continue;
            boolean match = true;
            for (int i = 0; i < args.length; i++) {
                Class<?> type = m.getParameterTypes()[i];
                if (type.isPrimitive()) type = Map.<Class<?>,Class<?>>of(int.class,Integer.class,float.class,Float.class,double.class,Double.class,boolean.class,Boolean.class,long.class,Long.class).getOrDefault(type,type);
                if (args[i] != null && !type.isInstance(args[i])) match = false;
            }
            if (match) return m.invoke(o instanceof Class<?> ? null : o, args);
        }
        throw new NoSuchMethodException(cls.getName()+"."+method);
    }
    static Class<?> cls(String name) throws Exception {return Class.forName(name, true, loader);}
    static void log(String text) {
        try {Files.writeString(Path.of("endcraft-neo-test.txt"), text+"\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);}
        catch(Exception e) {e.printStackTrace();}
    }
    public static void main(String[] args) throws Exception {
        if(args.length != 3) throw new IllegalArgumentException("PID, JAR, operation");
        var vm = VirtualMachine.attach(args[0]); try {vm.loadAgent(args[1],args[2]);} finally {vm.detach();}
    }
    /** Samples, off the render thread, what Windows reports, what Minecraft holds and what changed. */
    static void keyWatch(Object mc) throws Exception {
        Class<?> ib=cls("dev.skycraft.client.InputBridge");
        var debug=cls("dev.skycraft.client.render.TerrainDebug").getDeclaredField("enabled");debug.setAccessible(true);
        Object options=mc.getClass().getField("options").get(mc);var hideGui=options.getClass().getField("hideGui");
        Object sky=call(cls("dev.skycraft.client.SkyClient"),"sky");
        String last="";long end=System.currentTimeMillis()+120000;
        log("key_watch started: press F1, F3, F3+B and the quote key now");
        while(System.currentTimeMillis()<end){
            Object screen=mc.getClass().getField("screen").get(mc);
            String now="mc[F1="+call(ib,"isKeyDown",290)+" F3="+call(ib,"isKeyDown",292)+" quote="+call(ib,"isKeyDown",39)+" B="+call(ib,"isKeyDown",66)+"]"
                +" hideGui="+hideGui.get(options)+" terrain="+debug.get(null)+" hitboxes="+call(call(mc,"getEntityRenderDispatcher"),"shouldRenderHitBoxes")
                +" screen="+(screen==null?"none":screen.getClass().getSimpleName())+" inGame="+call(sky,"inGame")+" menuOpen="+call(sky,"menuOpen")+" loading="+call(sky,"loading");
            if(!now.equals(last)){log(System.currentTimeMillis()%100000+" "+now);last=now;}
            Thread.sleep(50);
        }
        log("key_watch ended");
    }
    public static void agentmain(String op, Instrumentation inst) throws Exception {
        if(!Set.of("status","open_world","fixture","ship","stop","third_person","render_audit","overlay_audit","stage_probe","pick_probe","rendertype_probe","keys_probe","inject_quote","inject_f1","close_screen","key_watch").contains(op)) throw new IllegalArgumentException(op);
        Class<?> minecraft = Arrays.stream(inst.getAllLoadedClasses()).filter(c -> c.getName().equals("net.minecraft.client.Minecraft")).findFirst().orElseThrow();
        loader = minecraft.getClassLoader(); Object mc = call(minecraft,"getInstance");
        if(op.equals("key_watch")) { var t=new Thread(()->{try{keyWatch(mc);}catch(Throwable e){log("key_watch error="+e);}},"endcraft-key-watch");t.setDaemon(true);t.start();return; }
        call(mc,"execute",(Runnable)() -> {
            try {
                if(op.equals("stop")) {call(mc,"stop");return;}
                if(op.equals("open_world")) {
                    if(mc.getClass().getField("level").get(mc) != null) throw new IllegalStateException("Preserve the open world");
                    call(mc,"setScreen",cls("net.minecraft.client.gui.screens.TitleScreen").getConstructor().newInstance());
                    call(cls("dev.skycraft.client.MirrorWorld"),"openWhenReady",mc);log("open_world requested");return;
                }
                Object player = mc.getClass().getField("player").get(mc), level = mc.getClass().getField("level").get(mc);
                Object screen = mc.getClass().getField("screen").get(mc);
                Object overlay=call(mc,"getOverlay");
                log("operation="+op+" overlay="+(overlay==null?"none":overlay.getClass().getName())+" screen="+(screen==null?"none":screen.getClass().getName())+" player="+(player==null?"none":call(player,"position")));
                if(level != null) {
                    Object container = call(cls("dev.ryanhcode.sable.api.sublevel.SubLevelContainer"),"getContainer",level);
                    var ships=(List<?>)call(container,"getAllSubLevels");log("client_ships="+ships.size());
                    for(Object ship:ships) log("ship="+call(ship,"getName")+" pose="+call(ship,"logicalPose"));
                }
                if(op.equals("third_person")) {
                    call(mc.getClass().getField("options").get(mc),"setCameraType",cls("net.minecraft.client.CameraType").getField("THIRD_PERSON_BACK").get(null));return;
                }
                if(op.equals("close_screen")) {
                    Object current=mc.getClass().getField("screen").get(mc);
                    if(current!=null&&current.getClass().getName().equals("net.minecraft.client.gui.screens.PauseScreen")) {call(mc,"setScreen",(Object)null);log("closed pause screen");}
                    else log("close_screen: left "+(current==null?"none":current.getClass().getName()));
                    return;
                }
                if(op.equals("inject_quote")||op.equals("inject_f1")) {
                    int key=op.equals("inject_quote")?39:290;
                    var inject=cls("dev.skycraft.client.InputBridge").getDeclaredMethod("injectKey",minecraft,int.class,boolean.class);inject.setAccessible(true);
                    inject.invoke(null,mc,key,true);inject.invoke(null,mc,key,false);log("injected key "+key);return;
                }
                if(op.equals("keys_probe")) {
                    Object sky=call(cls("dev.skycraft.client.SkyClient"),"sky");
                    Class<?> ib=cls("dev.skycraft.client.InputBridge");
                    var f=cls("dev.skycraft.client.render.TerrainDebug").getDeclaredField("enabled");f.setAccessible(true);
                    Object options=mc.getClass().getField("options").get(mc);
                    log("keys_probe inGame="+call(sky,"inGame")+" menuOpen="+call(sky,"menuOpen")+" loading="+call(sky,"loading")+" linked="+call(cls("dev.skycraft.client.SkyClient"),"linked")
                        +" F1down="+call(ib,"isKeyDown",290)+" F3down="+call(ib,"isKeyDown",292)+" quoteDown="+call(ib,"isKeyDown",39)
                        +" hideGui="+options.getClass().getField("hideGui").get(options)+" terrainDebug="+f.get(null)
                        +" hitboxes="+call(call(mc,"getEntityRenderDispatcher"),"shouldRenderHitBoxes"));return;
                }
                if(op.equals("rendertype_probe")) {
                    Class<?> rt=cls("net.minecraft.client.renderer.RenderType");
                    for(String name:List.of("solid","cutout","translucent")){String t=rt.getMethod(name).invoke(null).toString();log("rendertype "+name+" text="+t.contains("text")+" alpha="+t.contains("alpha")+" translucent="+t.contains("translucent")+" :: "+t);}
                    Object atlas=cls("net.minecraft.client.renderer.texture.TextureAtlas").getField("LOCATION_BLOCKS").get(null);
                    var blended=cls("dev.skycraft.client.render.CaptureBuffers").getDeclaredMethod("blended",rt);blended.setAccessible(true);
                    var rl=cls("net.minecraft.resources.ResourceLocation");
                    for(Object[] t:new Object[][]{{"solid",rt.getMethod("solid").invoke(null)},{"cutout",rt.getMethod("cutout").invoke(null)},{"translucent",rt.getMethod("translucent").invoke(null)},
                        {"entitySolid",rt.getMethod("entitySolid",rl).invoke(null,atlas)},{"entityCutout",rt.getMethod("entityCutout",rl).invoke(null,atlas)},{"entityTranslucent",rt.getMethod("entityTranslucent",rl).invoke(null,atlas)},
                        {"entityTranslucent(skin)",rt.getMethod("entityTranslucent",rl).invoke(null,rl.getMethod("withDefaultNamespace",String.class).invoke(null,"textures/entity/player/wide/steve.png"))},
                        {"text",rt.getMethod("text",rl).invoke(null,rl.getMethod("withDefaultNamespace",String.class).invoke(null,"default/0"))}})
                        {var nameOf=cls("dev.skycraft.client.render.CaptureBuffers").getDeclaredMethod("name",rt);nameOf.setAccessible(true);
                        log("blended "+t[0]+"="+blended.invoke(null,t[1])+" name="+nameOf.invoke(null,t[1]));}
                    String t=rt.getMethod("entitySolid",cls("net.minecraft.resources.ResourceLocation")).invoke(null,atlas).toString();
                    log("rendertype entitySolid text="+t.contains("text")+" :: "+t);return;
                }
                if(op.equals("pick_probe")) {
                    // Aim from the eye through the first ship's centre and on past it, as the crosshair would.
                    if(player==null||level==null)throw new IllegalStateException("Player not ready");
                    Object container=call(cls("dev.ryanhcode.sable.api.sublevel.SubLevelContainer"),"getContainer",level);
                    var ships=(List<?>)call(container,"getAllSubLevels");if(ships.isEmpty()){log("pick_probe no ships");return;}
                    Object ship=ships.get(0),pose=call(ship,"renderPose",0f),plot=call(ship,"getPlot");
                    Class<?> vec=cls("net.minecraft.world.phys.Vec3");
                    Object center=call(pose,"transformPosition",vec.getMethod("atCenterOf",cls("net.minecraft.core.Vec3i")).invoke(null,call(plot,"getCenterBlock")));
                    Object eye=call(player,"getEyePosition",1f);
                    Object dir=call(call(center,"subtract",eye),"normalize");
                    Object to=call(center,"add",call(dir,"scale",8.0));
                    Class<?> ctx=cls("net.minecraft.world.level.ClipContext");
                    Object block=cls("net.minecraft.world.level.ClipContext$Block").getField("OUTLINE").get(null),fluid=cls("net.minecraft.world.level.ClipContext$Fluid").getField("NONE").get(null);
                    Object context=ctx.getConstructor(vec,vec,block.getClass(),fluid.getClass(),cls("net.minecraft.world.entity.Entity")).newInstance(eye,to,block,fluid,player);
                    Object vanilla=call(level,"clip",context);
                    Class<?> clip=cls("dev.skycraft.world.SkyClip");
                    var project=clip.getDeclaredMethod("worldPosition",cls("net.minecraft.world.level.BlockGetter"),vec);project.setAccessible(true);
                    Object at=call(vanilla,"getLocation"),projected=project.invoke(null,level,at);
                    Object use=cls("dev.skycraft.world.SkyClip$Use").getField("PICK").get(null);
                    Object refined=clip.getMethod("refine",cls("net.minecraft.world.level.BlockGetter"),vec,vec,cls("net.minecraft.world.phys.BlockHitResult"),use.getClass()).invoke(null,level,eye,to,vanilla,use);
                    log("pick_probe eye="+eye+" ship_center="+center+" vanilla="+call(vanilla,"getType")+" at="+at+" world_at="+projected+" eye_dist="+Math.sqrt((double)call(eye,"distanceToSqr",projected))
                        +" terrain_hit="+clip.getMethod("cast",vec,vec).invoke(null,eye,to)+" raw_dist="+Math.sqrt((double)call(eye,"distanceToSqr",at))+" refined="+refined.getClass().getSimpleName()+" "+call(refined,"getType")+" pos="+call(refined,"getBlockPos"));return;
                }
                if(op.equals("stage_probe")) {
                    // One-shot listeners: centre-pixel RGBA of the main target at each stage of the next frame.
                    Object bus=cls("net.neoforged.neoforge.common.NeoForge").getField("EVENT_BUS").get(null);
                    Class<?> priority=cls("net.neoforged.bus.api.EventPriority");
                    for(String[] stage:new String[][]{{"net.neoforged.neoforge.client.event.RenderFrameEvent$Pre","frame_pre"},{"net.neoforged.neoforge.client.event.RenderGuiEvent$Pre","gui_pre"},{"net.neoforged.neoforge.client.event.RenderGuiEvent$Post","gui_post"},{"net.neoforged.neoforge.client.event.RenderGuiLayerEvent$Post","layer"},{"net.neoforged.neoforge.client.event.RenderFrameEvent$Post","frame_post"}}){
                        int[] left={stage[1].equals("layer")?40:1};
                        java.util.function.Consumer<Object> probe=e->{if(left[0]<=0)return;left[0]--;try{
                            Object t=call(mc,"getMainRenderTarget");int w=(int)t.getClass().getField("width").get(t),h=(int)t.getClass().getField("height").get(t);
                            Class<?> gl11=cls("org.lwjgl.opengl.GL11"),gl30=cls("org.lwjgl.opengl.GL30");
                            int prev=(int)gl11.getMethod("glGetInteger",int.class).invoke(null,0x8CAA);
                            gl30.getMethod("glBindFramebuffer",int.class,int.class).invoke(null,0x8CA8,(int)t.getClass().getField("frameBufferId").get(t));
                            var px=java.nio.ByteBuffer.allocateDirect(4);gl11.getMethod("glReadPixels",int.class,int.class,int.class,int.class,int.class,int.class,java.nio.ByteBuffer.class).invoke(null,w/2,h/2,1,1,0x1908,0x1401,px);
                            gl30.getMethod("glBindFramebuffer",int.class,int.class).invoke(null,0x8CA8,prev);
                            log("probe "+stage[1]+(stage[1].equals("layer")?"="+call(e,"getName"):"")+" rgba="+(px.get(0)&255)+","+(px.get(1)&255)+","+(px.get(2)&255)+","+(px.get(3)&255));
                        }catch(Throwable x){log("probe_error="+x);}};
                        bus.getClass().getMethod("addListener",priority,boolean.class,Class.class,java.util.function.Consumer.class).invoke(bus,priority.getField("LOWEST").get(null),true,cls(stage[0]),probe);
                    }
                    log("stage probes armed");return;
                }
                if(op.equals("overlay_audit")) {
                    Object target=call(mc,"getMainRenderTarget");int w=(int)target.getClass().getField("width").get(target),h=(int)target.getClass().getField("height").get(target);
                    int fbo=(int)target.getClass().getField("frameBufferId").get(target);
                    Class<?> gl11=cls("org.lwjgl.opengl.GL11"),gl30=cls("org.lwjgl.opengl.GL30");
                    gl30.getMethod("glBindFramebuffer",int.class,int.class).invoke(null,0x8CA8,fbo);
                    var buf=java.nio.ByteBuffer.allocateDirect(w*h*4);
                    gl11.getMethod("glReadPixels",int.class,int.class,int.class,int.class,int.class,int.class,java.nio.ByteBuffer.class).invoke(null,0,0,w,h,0x1908,0x1401,buf);
                    long opaque=0,clear=0,partial=0,r=0,g=0,b=0;
                    for(int i=0;i<w*h;i++){int a=buf.get(i*4+3)&255;if(a==255){opaque++;r+=buf.get(i*4)&255;g+=buf.get(i*4+1)&255;b+=buf.get(i*4+2)&255;}else if(a==0)clear++;else partial++;}
                    long pa=0,pr=0,pg=0,pb=0;int[] hist=new int[8];for(int i=0;i<w*h;i++){int a=buf.get(i*4+3)&255;hist[a>>5]++;if(a!=0&&a!=255){pa+=a;pr+=buf.get(i*4)&255;pg+=buf.get(i*4+1)&255;pb+=buf.get(i*4+2)&255;}}
                    int c=((h/2)*w+w/2)*4,e=((h/2)*w+5)*4;
                    if(partial>0)log("partial_avg_rgba="+pr/partial+","+pg/partial+","+pb/partial+","+pa/partial+" alpha_hist/32="+Arrays.toString(hist)+" center="+(buf.get(c)&255)+","+(buf.get(c+1)&255)+","+(buf.get(c+2)&255)+","+(buf.get(c+3)&255)+" edge="+(buf.get(e)&255)+","+(buf.get(e+1)&255)+","+(buf.get(e+2)&255)+","+(buf.get(e+3)&255));
                    log("overlay "+w+"x"+h+" opaque="+opaque+" clear="+clear+" partial="+partial+(opaque>0?" opaque_avg_rgb="+r/opaque+","+g/opaque+","+b/opaque:"")+" linked="+call(cls("dev.skycraft.client.SkyClient"),"linked"));return;
                }
                if(op.equals("render_audit")) {
                    if(player==null)throw new IllegalStateException("Player not ready");
                    Class<?> buffers=cls("dev.skycraft.client.render.CaptureBuffers");var constructor=buffers.getDeclaredConstructor();constructor.setAccessible(true);Object capture=constructor.newInstance();
                    Object origin=call(player,"position");
                    var method=cls("dev.skycraft.client.render.SableExporter").getDeclaredMethod("capture",minecraft,float.class,cls("net.minecraft.world.phys.Vec3"),buffers);method.setAccessible(true);method.invoke(null,mc,0f,origin,capture);
                    var field=buffers.getDeclaredField("batches");field.setAccessible(true);var batches=(Map<?,?>)field.get(capture);int vertices=0;
                    for(Object mesh:batches.values()){var count=mesh.getClass().getDeclaredMethod("count");count.setAccessible(true);vertices+=(int)count.invoke(mesh);}
                    log("sable_capture_batches="+batches.size()+" vertices="+vertices+" keys="+batches.keySet());return;
                }
                if(!Set.of("fixture","ship").contains(op))return;
                Object server=call(mc,"getSingleplayerServer");if(server==null||player==null)throw new IllegalStateException("Own world required");
                String worldName=(String)call(call(server,"getWorldData"),"getLevelName");
                if(!worldName.equals("EndCraft-Neo-Bridge"))throw new IllegalStateException("Fixtures are restricted to EndCraft-Neo-Bridge; preserve other worlds");
                Object uuid=call(player,"getUUID");
                call(server,"execute",(Runnable)()->{
                    try {
                        Object sp=call(call(server,"getPlayerList"),"getPlayer",uuid);
                        Object source=call(sp,"createCommandSourceStack"),commands=call(server,"getCommands");
                        if(op.equals("fixture")) {
                            for(String command:List.of("gamemode creative","fill ~-6 ~-1 ~-6 ~6 ~-1 ~6 minecraft:stone","tp @s ~ ~ ~ 180 15","setblock ~2 ~ ~-3 create:creative_motor[facing=east]","setblock ~3 ~ ~-3 create:shaft[axis=x]","setblock ~4 ~ ~-3 create:cogwheel[axis=x]","setblock ~-2 ~ ~-3 minecraft:glass","setblock ~-3 ~ ~-3 minecraft:blue_stained_glass","setblock ~-4 ~ ~-3 minecraft:water")) {
                                call(commands,"performPrefixedCommand",source,command);
                            }
                            log("fixture commands submitted");
                        } else {
                            call(commands,"performPrefixedCommand",source,"execute at @s positioned ~ ~3 ~-3 run sable spawn platform 1 minecraft:oak_planks EndCraft_TestShip");
                            log("ship command submitted");
                        }
                    }catch(Throwable e){log("server_error="+e);e.printStackTrace();}
                });
            } catch(Throwable e) {log("error="+e);e.printStackTrace();}
        });
    }
}
