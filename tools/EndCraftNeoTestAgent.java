import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import com.sun.tools.attach.VirtualMachine;

/** Diagnostics through the supported JVM attach API, restricted by the wrapper to our NeoForge guest. */
public class EndCraftNeoTestAgent {
    static ClassLoader loader;
    static Object atlasReadback;
    static int atlasProbeTexture;
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
            if (match) {m.setAccessible(true);return m.invoke(o instanceof Class<?> ? null : o, args);}
        }
        throw new NoSuchMethodException(cls.getName()+"."+method);
    }
    static Class<?> cls(String name) throws Exception {return Class.forName(name, true, loader);}
    static void log(String text) {
        try {Files.writeString(Path.of("endcraft-neo-test.txt"), text+"\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);}
        catch(Exception e) {e.printStackTrace();}
    }
    static void maidWalk(Object mc, Object player) throws Exception {
        Object server=call(mc,"getSingleplayerServer");
        if(server==null)throw new IllegalStateException("Own bridge server required");
        Object uuid=call(player,"getUUID");
        new Thread(()->{
            Object[] maid={null};int[] samples={0},missing={0};double[] minGap={Double.POSITIVE_INFINITY},travel={0};
            try {
                var spawn=new java.util.concurrent.CompletableFuture<Void>();
                call(server,"execute",(Runnable)()->{try {
                    Object sp=call(call(server,"getPlayerList"),"getPlayer",uuid),level=call(sp,"serverLevel");
                    Object id=call(cls("net.minecraft.resources.ResourceLocation"),"parse","touhou_little_maid:maid");
                    Object type=call(cls("net.minecraft.core.registries.BuiltInRegistries").getField("ENTITY_TYPE").get(null),"get",id);
                    maid[0]=call(type,"create",level);
                    if(maid[0]==null)throw new IllegalStateException("Maid unavailable");
                    call(maid[0],"setNoAi",true);
                    double x=(double)call(sp,"getX"),y=(double)call(sp,"getY"),z=(double)call(sp,"getZ");
                    call(maid[0],"setPos",x,y+1,z);
                    call(level,"addFreshEntity",maid[0]);
                    log("maid_walk spawned temporary maid smooth="+call(cls("dev.skycraft.world.SkyCollision"),"usesSmoothCollider",maid[0]));
                    spawn.complete(null);
                }catch(Throwable e){spawn.completeExceptionally(e);}});
                spawn.get(10,java.util.concurrent.TimeUnit.SECONDS);
                for(int tick=0;tick<120;tick++) {
                    var step=new java.util.concurrent.CompletableFuture<Void>();
                    call(server,"execute",(Runnable)()->{try {
                        Object e=maid[0];
                        Object mover=cls("net.minecraft.world.entity.MoverType").getField("SELF").get(null);
                        Object move=cls("net.minecraft.world.phys.Vec3").getConstructor(double.class,double.class,double.class).newInstance(.06,-.0784,0d);
                        double before=(double)call(e,"getX");call(e,"move",mover,move);
                        travel[0]+=Math.abs((double)call(e,"getX")-before);
                        Object box=call(call(e,"getBoundingBox"),"inflate",1d,2d,1d);
                        var tris=new ArrayList<>();call(cls("dev.skycraft.world.SkyCollision"),"trianglesNear",box,tris);
                        double ground=(double)call(cls("dev.skycraft.world.TriCollider"),"groundAt",tris,(double)call(e,"getX"),(double)call(e,"getY")+1,(double)call(e,"getZ"),0d);
                        if(Double.isFinite(ground)){samples[0]++;minGap[0]=Math.min(minGap[0],(double)call(e,"getY")-ground);}else missing[0]++;
                        step.complete(null);
                    }catch(Throwable e){step.completeExceptionally(e);}});
                    step.get(5,java.util.concurrent.TimeUnit.SECONDS);Thread.sleep(50);
                }
                log("maid_walk samples="+samples[0]+" missing="+missing[0]+" min_floor_gap="+minGap[0]+" distance="+travel[0]);
            }catch(Throwable e){log("maid_walk error="+e);}
            finally{try{call(server,"execute",(Runnable)()->{try{if(maid[0]!=null)call(maid[0],"discard");log("maid_walk temporary maid removed");}catch(Throwable e){log("maid cleanup="+e);}});}catch(Throwable ignored){}}
        },"endcraft-maid-terrain-check").start();
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
        if(!Set.of("status","open_world","render_test_world","loading_probe","abort_test_load","continue_world","render_test_ship","plot_transform_audit","atlas_request","atlas_poll","mesh_benchmark","shader_mesh","shader_preview","particle_seed","particle_audit","capture_frame","inventory","fixture","ship","stop","third_person","first_person","equip_gun","gun_refit","compat_audit","maid_walk","render_audit","overlay_audit","stage_probe","pick_probe","rendertype_probe","keys_probe","inject_quote","inject_f1","close_screen","key_watch").contains(op)) throw new IllegalArgumentException(op);
        Class<?> minecraft = Arrays.stream(inst.getAllLoadedClasses()).filter(c -> c.getName().equals("net.minecraft.client.Minecraft")).findFirst().orElseThrow();
        loader = minecraft.getClassLoader(); Object mc = call(minecraft,"getInstance");
        if(op.equals("abort_test_load")) {
            Object server=call(mc,"getSingleplayerServer");
            if(server==null || !"EndCraft-Render-Test".equals(call(call(server,"getWorldData"),"getLevelName")))throw new IllegalStateException("Isolated render test world required");
            call(server,"halt",true);log("saved isolated render test world before aborting stuck load");
            new Thread(()->{try{Thread.sleep(1000);}catch(InterruptedException ignored){}System.exit(0);},"endcraft-test-shutdown").start();return;
        }
        if(op.equals("loading_probe")) {
            Object overlay=call(mc,"getOverlay"),server=call(mc,"getSingleplayerServer");
            log("loading_probe overlay="+(overlay==null?"none":overlay.getClass().getName())+" serverReady="+(server==null?"none":call(server,"isReady")));
            if(overlay!=null) {
                var field=cls("net.minecraft.client.gui.screens.LoadingOverlay").getDeclaredField("reload");field.setAccessible(true);
                log("loading_probe reload_done="+call(field.get(overlay),"isDone"));
            }
            return;
        }
        if(op.equals("key_watch")) { var t=new Thread(()->{try{keyWatch(mc);}catch(Throwable e){log("key_watch error="+e);}},"endcraft-key-watch");t.setDaemon(true);t.start();return; }
        call(mc,"execute",(Runnable)() -> {
            try {
                if(op.equals("stop")) {call(mc,"stop");return;}
                if(op.equals("render_test_world")) {
                    if(call(mc,"getOverlay") != null)throw new IllegalStateException("Wait until resource loading overlay has closed");
                    if(mc.getClass().getField("level").get(mc) != null)throw new IllegalStateException("Preserve the open world");
                    Object title=cls("net.minecraft.client.gui.screens.TitleScreen").getConstructor().newInstance();
                    call(mc,"setScreen",title);Object flows=call(mc,"createWorldOpenFlows");String name="EndCraft-Render-Test";
                    if(Files.isDirectory(Path.of("saves",name))) {call(flows,"openWorld",name,(Runnable)()->{});return;}
                    Class<?> gameType=cls("net.minecraft.world.level.GameType"),difficulty=cls("net.minecraft.world.Difficulty"),rules=cls("net.minecraft.world.level.GameRules"),configuration=cls("net.minecraft.world.level.WorldDataConfiguration");
                    Object settings=cls("net.minecraft.world.level.LevelSettings").getConstructor(String.class,gameType,boolean.class,difficulty,boolean.class,rules,configuration).newInstance(name,gameType.getField("CREATIVE").get(null),false,difficulty.getField("PEACEFUL").get(null),true,rules.getConstructor().newInstance(),configuration.getField("DEFAULT").get(null));
                    Object options=cls("net.minecraft.world.level.levelgen.WorldOptions").getConstructor(long.class,boolean.class,boolean.class).newInstance(0L,false,false);
                    java.util.function.Function<Object,Object> dimensions=registries->{try {
                        Object key=cls("net.minecraft.core.registries.Registries").getField("WORLD_PRESET").get(null);
                        Object preset=cls("net.minecraft.world.level.levelgen.presets.WorldPresets").getField("FLAT").get(null);
                        return call(call(call(call(registries,"lookupOrThrow",key),"getOrThrow",preset),"value"),"createWorldDimensions");
                    }catch(Exception e){throw new RuntimeException(e);}};
                    call(flows,"createFreshLevel",name,settings,options,dimensions,title);log("created isolated render test world");return;
                }
                if(op.equals("open_world")) {
                    if(mc.getClass().getField("level").get(mc) != null) throw new IllegalStateException("Preserve the open world");
                    call(mc,"setScreen",cls("net.minecraft.client.gui.screens.TitleScreen").getConstructor().newInstance());
                    call(cls("dev.skycraft.client.MirrorWorld"),"openWhenReady",mc);log("open_world requested");return;
                }
                Object player = mc.getClass().getField("player").get(mc), level = mc.getClass().getField("level").get(mc);
                Object screen = mc.getClass().getField("screen").get(mc);
                Object overlay=call(mc,"getOverlay");
                log("operation="+op+" overlay="+(overlay==null?"none":overlay.getClass().getName())+" screen="+(screen==null?"none":screen.getClass().getName())+" player="+(player==null?"none":call(player,"position")));
                if(op.equals("first_person")) {
                    if(player==null)throw new IllegalStateException("Player required");
                    Object options=mc.getClass().getField("options").get(mc);
                    call(mc,"setScreen",(Object)null);
                    call(options,"setCameraType",cls("net.minecraft.client.CameraType").getField("FIRST_PERSON").get(null));return;
                }
                if(op.equals("compat_audit")) {
                    if(player==null)throw new IllegalStateException("Player required");
                    Object options=mc.getClass().getField("options").get(mc);
                    log("compat camera="+call(options,"getCameraType")+" held="+call(player,"getMainHandItem"));
                    for(Object entity:(Iterable<?>)call(level,"entitiesForRendering"))if(entity.getClass().getName().contains("Maid"))
                        log("compat maid="+call(entity,"position")+" smooth="+call(cls("dev.skycraft.world.SkyCollision"),"usesSmoothCollider",entity));
                    return;
                }
                if(op.equals("maid_walk")) {if(player==null)throw new IllegalStateException("Player required");maidWalk(mc,player);return;}
                if(op.equals("equip_gun")) {
                    if(player==null)throw new IllegalStateException("Player required");
                    Object inventory=call(player,"getInventory");
                    for(int slot=0;slot<9;slot++) {
                        Object item=call(inventory,"getItem",slot);
                        if(call(cls("com.tacz.guns.api.item.IGun"),"getIGunOrNull",item)==null)continue;
                        inventory.getClass().getField("selected").setInt(inventory,slot);
                        Object packet=cls("net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket").getConstructor(int.class).newInstance(slot);
                        call(call(mc,"getConnection"),"send",packet);
                        log("equipped existing TaCZ hotbar slot="+slot);return;
                    }
                    throw new IllegalStateException("No TaCZ gun in hotbar; inventory unchanged");
                }
                if(op.equals("gun_refit")) {
                    if(player==null)throw new IllegalStateException("Player required");
                    Object item=call(player,"getMainHandItem");
                    if(call(cls("com.tacz.guns.api.item.IGun"),"getIGunOrNull",item)==null)throw new IllegalStateException("Equip a TaCZ gun before this check");
                    call(mc,"setScreen",cls("com.tacz.guns.client.gui.GunRefitScreen").getConstructor().newInstance());
                    log("gun_refit camera="+call(mc.getClass().getField("options").get(mc),"getCameraType"));return;
                }
                if(op.equals("continue_world")) {
                    if(screen != null && screen.getClass().getName().endsWith("BackupConfirmScreen")) {
                        Object callback=cls("dev.skycraft.client.mixin.BackupConfirmAccess").getMethod("endcraft$onProceed").invoke(screen);
                        for(Method method:callback.getClass().getMethods()) if(method.getName().equals("proceed")) {
                            method.setAccessible(true);method.invoke(callback,false,false);break;
                        }
                    }
                    return;
                }
                if(op.equals("capture_frame")) {
                    Object target=call(mc,"getMainRenderTarget");
                    int width=(int)target.getClass().getField("width").get(target),height=(int)target.getClass().getField("height").get(target);
                    Class<?> gl=cls("org.lwjgl.opengl.GL11");
                    int previous=(int)gl.getMethod("glGetInteger",int.class).invoke(null,0x8069);
                    Object image=cls("com.mojang.blaze3d.platform.NativeImage").getConstructor(int.class,int.class,boolean.class).newInstance(width,height,false);
                    try {
                        gl.getMethod("glBindTexture",int.class,int.class).invoke(null,0x0DE1,call(target,"getColorTextureId"));
                        call(image,"downloadTexture",0,false);
                        call(image,"flipY");
                        Path output=Path.of("endcraft-render-frame.png");call(image,"writeToFile",output);
                        log("captured_frame="+output.toAbsolutePath()+" backend="+call(cls("dev.skycraft.client.RenderBackend"),"preview"));
                    } finally {
                        call(image,"close");gl.getMethod("glBindTexture",int.class,int.class).invoke(null,0x0DE1,previous);
                    }
                    return;
                }
                if(op.equals("inventory")) {
                    if(player==null)throw new IllegalStateException("World required");
                    call(mc,"setScreen",cls("net.minecraft.client.gui.screens.inventory.InventoryScreen").getConstructor(cls("net.minecraft.world.entity.player.Player")).newInstance(player));return;
                }
                if(op.equals("shader_mesh") || op.equals("shader_preview")) {
                    if(player==null)throw new IllegalStateException("World required");
                    Object source=call(player,"createCommandSourceStack");
                    log("render_backend_switch="+call(cls("dev.skycraft.client.RenderBackend"),"select",source,op.equals("shader_preview")));
                    return;
                }
                if(op.equals("atlas_request")) {
                    if(player==null || call(mc,"getOverlay")!=null)throw new IllegalStateException("Wait until test world and resources have loaded");
                    if(atlasReadback!=null)throw new IllegalStateException("Atlas probe already pending");
                    Class<?> gl=cls("org.lwjgl.opengl.GL11");
                    int old=(int)call(gl,"glGetInteger",0x8069);
                    try {
                        atlasProbeTexture=(int)call(gl,"glGenTextures");call(gl,"glBindTexture",0x0DE1,atlasProbeTexture);
                        var pixels=java.nio.ByteBuffer.allocateDirect(16);
                        for(int i=0;i<16;i++)pixels.put((byte)(i+1));pixels.flip();
                        call(gl,"glTexImage2D",0x0DE1,0,0x8058,2,2,0,0x1908,0x1401,pixels);
                        var constructor=cls("dev.skycraft.client.render.TextureReadback").getDeclaredConstructor();constructor.setAccessible(true);
                        atlasReadback=constructor.newInstance();var method=atlasReadback.getClass().getDeclaredMethod("request",int.class,int.class,int.class);method.setAccessible(true);
                        method.invoke(atlasReadback,atlasProbeTexture,2,2);log("atlas_request queued 16 known RGBA bytes");
                    } finally {call(gl,"glBindTexture",0x0DE1,old);}
                    return;
                }
                if(op.equals("atlas_poll")) {
                    if(atlasReadback==null)throw new IllegalStateException("Request atlas probe first");
                    var pixels=java.nio.ByteBuffer.allocateDirect(16);
                    var method=atlasReadback.getClass().getDeclaredMethod("poll",java.nio.ByteBuffer.class);method.setAccessible(true);
                    if(!(boolean)method.invoke(atlasReadback,pixels)){log("atlas_poll GPU pending");return;}
                    boolean correct=pixels.remaining()==16;
                    for(int i=0;i<16;i++)correct&=pixels.get(i)==(byte)(i+1);
                    byte[] actual=new byte[16];pixels.duplicate().get(actual);log("atlas_poll actual="+java.util.Arrays.toString(actual)+" gl_error="+call(cls("org.lwjgl.opengl.GL11"),"glGetError"));
                    var close=atlasReadback.getClass().getDeclaredMethod("close");close.setAccessible(true);close.invoke(atlasReadback);atlasReadback=null;
                    call(cls("org.lwjgl.opengl.GL11"),"glDeleteTextures",atlasProbeTexture);
                    log("atlas_poll bytes_match="+correct);return;
                }
                if(op.equals("mesh_benchmark")) {
                    Object server=call(mc,"getSingleplayerServer");
                    if(server==null || !"EndCraft-Render-Test".equals(call(call(server,"getWorldData"),"getLevelName")))throw new IllegalStateException("Isolated render test world required");
                    if((boolean)call(cls("dev.skycraft.client.SkyClient"),"linked"))throw new IllegalStateException("Offline diagnostic required");
                    Class<?> exporter=cls("dev.skycraft.client.render.WorldExporter");
                    var method=exporter.getDeclaredMethod("exportSection",minecraft,long.class);method.setAccessible(true);
                    int x=(int)Math.floor((double)call(player,"getX")/16),z=(int)Math.floor((double)call(player,"getZ")/16);
                    long key=(long)call(cls("net.minecraft.core.SectionPos"),"asLong",x,Math.floorDiv((int)(double)call(player,"getY"),16),z);
                    for(int i=0;i<3;i++)method.invoke(null,mc,key);
                    long start=System.nanoTime();for(int i=0;i<10;i++)method.invoke(null,mc,key);
                    double ms=(System.nanoTime()-start)/1_000_000d/10;
                    var field=exporter.getDeclaredField("SECTION_MESH");field.setAccessible(true);Object mesh=field.get(null);
                    var count=mesh.getClass().getDeclaredMethod("count");count.setAccessible(true);
                    log("mesh_benchmark avg_ms="+ms+" vertices="+count.invoke(mesh)+" samples=10 (meshing only, no host)");return;
                }
                if(op.equals("render_test_ship")) {
                    Object server=call(mc,"getSingleplayerServer");
                    if(server==null || !"EndCraft-Render-Test".equals(call(call(server,"getWorldData"),"getLevelName")))throw new IllegalStateException("Isolated render test world required");
                    Object uuid=call(player,"getUUID");
                    call(server,"execute",(Runnable)()->{try {
                        Object sp=call(call(server,"getPlayerList"),"getPlayer",uuid);
                        Object ships=call(cls("dev.ryanhcode.sable.api.sublevel.SubLevelContainer"),"getContainer",call(sp,"level"));
                        for(Object ship:(List<?>)call(ships,"getAllSubLevels"))if("EndCraft_PerfShip".equals(call(ship,"getName"))){log("render test ship already exists");return;}
                        call(call(server,"getCommands"),"performPrefixedCommand",call(sp,"createCommandSourceStack"),"execute at @s positioned ~ ~3 ~-3 run sable spawn platform 2 minecraft:oak_planks EndCraft_PerfShip");
                        log("isolated render test ship submitted");
                    }catch(Throwable error){log("render test ship error="+error);}});
                    return;
                }
                if(op.equals("plot_transform_audit")) {
                    Object container=call(cls("dev.ryanhcode.sable.api.sublevel.SubLevelContainer"),"getContainer",level);
                    Class<?> vec=cls("net.minecraft.world.phys.Vec3"),mappingClass=cls("dev.skycraft.client.render.PlotTransform");
                    var ctor=mappingClass.getDeclaredConstructor(vec,vec,java.util.function.UnaryOperator.class);ctor.setAccessible(true);
                    var forBlock=mappingClass.getDeclaredMethod("forBlock",int.class,int.class,int.class);forBlock.setAccessible(true);
                    double maxError=0;int samples=0;
                    for(Object ship:(List<?>)call(container,"getAllSubLevels")) {
                        Object pose=call(ship,"renderPose",0f),plot=call(ship,"getPlot"),centre=call(vec,"atCenterOf",call(plot,"getCenterBlock"));
                        java.util.function.UnaryOperator<Object> source=p->{try{return call(pose,"transformPosition",p);}catch(Exception e){throw new RuntimeException(e);}};
                        Object origin=call(player,"position"),mapping=ctor.newInstance(centre,origin,source);
                        int bx=(int)Math.floor(vec.getField("x").getDouble(centre)),by=(int)Math.floor(vec.getField("y").getDouble(centre)),bz=(int)Math.floor(vec.getField("z").getDouble(centre));
                        for(int i=-8;i<=8;i++) {
                            Object local=vec.getConstructor(double.class,double.class,double.class).newInstance(.125,.375,.625);
                            Object expected=call(source.apply(call(local,"add",(double)(bx+i),(double)by,(double)(bz-i))),"subtract",origin);
                            Object actual;
                            @SuppressWarnings("unchecked") var transform=(java.util.function.UnaryOperator<Object>)forBlock.invoke(mapping,bx+i,by,bz-i);
                            actual=transform.apply(local);maxError=Math.max(maxError,(double)call(expected,"distanceTo",actual));samples++;
                        }
                    }
                    log("plot_transform_audit samples="+samples+" max_error="+maxError);return;
                }
                if(op.equals("particle_seed")) {
                    if(level==null || player==null)throw new IllegalStateException("World required");
                    Object marker=cls("net.minecraft.core.particles.ParticleTypes").getField("BLOCK_MARKER").get(null);
                    Object block=cls("net.minecraft.world.level.block.Blocks").getField("BARRIER").get(null);
                    Object option=cls("net.minecraft.core.particles.BlockParticleOption").getConstructor(cls("net.minecraft.core.particles.ParticleType"),cls("net.minecraft.world.level.block.state.BlockState")).newInstance(marker,call(block,"defaultBlockState"));
                    double x=(double)call(player,"getX"),y=(double)call(player,"getY"),z=(double)call(player,"getZ");
                    call(level,"addParticle",option,x,y+1,z+2,0d,0d,0d);log("seeded barrier marker particle");return;
                }
                if(op.equals("particle_audit")) {
                    if(level==null || player==null)throw new IllegalStateException("World required");
                    Class<?> buffers=cls("dev.skycraft.client.render.CaptureBuffers");var constructor=buffers.getDeclaredConstructor();constructor.setAccessible(true);Object capture=constructor.newInstance();
                    var captureMethod=cls("dev.skycraft.client.render.ParticleExporter").getDeclaredMethod("capture",minecraft,float.class,cls("net.minecraft.world.phys.Vec3"),buffers);
                    captureMethod.setAccessible(true);captureMethod.invoke(null,mc,0f,call(player,"position"),capture);
                    var field=buffers.getDeclaredField("batches");field.setAccessible(true);var batches=(Map<?,?>)field.get(capture);int vertices=0;
                    for(Object mesh:batches.values()){var count=mesh.getClass().getDeclaredMethod("count");count.setAccessible(true);vertices+=(int)count.invoke(mesh);}
                    log("particle_capture_batches="+batches.size()+" vertices="+vertices+" keys="+batches.keySet());return;
                }
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
