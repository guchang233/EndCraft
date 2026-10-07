import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import com.sun.tools.attach.VirtualMachine;

/** Read the real client/server equipment and flight state on their owning threads. */
public class EndCraftFlightAuditV2 {
    public static void main(String[] args) throws Exception {
        var vm=VirtualMachine.attach(args[0]);
        try {vm.loadAgent(args[1]);} finally {vm.detach();}
    }
    public static void agentmain(String args, Instrumentation instrumentation) throws Exception {
        Class<?> mc=null;
        for(var type:instrumentation.getAllLoadedClasses()) if(type.getName().equals("net.minecraft.client.Minecraft")) {mc=type;break;}
        if(mc==null) throw new IllegalStateException("Not Minecraft");
        Object client=mc.getMethod("getInstance").invoke(null);
        ClassLoader loader=mc.getClassLoader();
        mc.getMethod("execute",Runnable.class).invoke(client,(Runnable)()-> {
            try {
                Object player=client.getClass().getField("player").get(client);
                StringBuilder s=new StringBuilder(state(player,loader));
                Object window=client.getClass().getMethod("getWindow").invoke(client);
                for(String name:new String[]{"getWidth","getHeight","getGuiScale"}) s.append(name).append('=').append(window.getClass().getMethod(name).invoke(window)).append('\n');
                Class<?> bridge=Class.forName("dev.skycraft.client.SkyClient",true,loader);
                for(String name:new String[]{"linked","holdPos","unlinkedHold"}) {var f=bridge.getDeclaredField(name);f.setAccessible(true);s.append(name).append('=').append(f.get(null)).append('\n');}
                Class<?> collider=Class.forName("dev.skycraft.client.SkyCollider",true,loader);
                double px=(double)player.getClass().getMethod("getX").invoke(player), py=(double)player.getClass().getMethod("getY").invoke(player), pz=(double)player.getClass().getMethod("getZ").invoke(player);
                s.append("ground_at=").append(collider.getMethod("groundAt",double.class,double.class,double.class,double.class).invoke(null,px,py,pz,2.5)).append('\n');
                Class<?> collision=Class.forName("dev.skycraft.world.SkyCollision",true,loader);
                for(int dy:new int[]{0,-1,-8}) s.append("known_").append(dy).append('=').append(collision.getMethod("isKnown",int.class,int.class,int.class).invoke(null,(int)Math.floor(px),(int)Math.floor(py)+dy,(int)Math.floor(pz))).append('\n');
                Object options=client.getClass().getField("options").get(client);
                Object key=options.getClass().getField("keyJump").get(options);
                s.append("jump_key_down=").append(key.getClass().getMethod("isDown").invoke(key)).append('\n');
                write("client",s.toString());
                Object server=client.getClass().getMethod("getSingleplayerServer").invoke(client);
                Object uuid=player.getClass().getMethod("getUUID").invoke(player);
                if(server!=null) server.getClass().getMethod("execute",Runnable.class).invoke(server,(Runnable)()-> {
                    try {
                        Object list=server.getClass().getMethod("getPlayerList").invoke(server);
                        Object sp=list.getClass().getMethod("getPlayer",java.util.UUID.class).invoke(list,uuid);
                        write("server",state(sp,loader));
                    } catch(Exception e) {write("server",e.toString());}
                });
            } catch(Exception e) {write("client",e.toString());}
        });
    }
    private static String state(Object player,ClassLoader loader) throws Exception {
        if(player==null) return "player=null";
        StringBuilder s=new StringBuilder();
        for(String name:new String[]{"position","getDeltaMovement","onGround","isFallFlying","isInWater","getXRot","getHealth"}) {
            s.append(name).append('=').append(player.getClass().getMethod(name).invoke(player)).append('\n');
        }
        Class<?> slot=Class.forName("net.minecraft.world.entity.EquipmentSlot",true,loader);
        Object chest=player.getClass().getMethod("getItemBySlot",slot).invoke(player,slot.getField("CHEST").get(null));
        s.append("chest=").append(chest).append('\n');
        s.append("chest_damage=").append(chest.getClass().getMethod("getDamageValue").invoke(chest)).append('\n');
        for(var method:player.getClass().getMethods()) if(method.getName().toLowerCase().contains("flight")||method.getName().toLowerCase().contains("fallfly")) s.append("flight_method=").append(method).append('\n');
        Object inventory=player.getClass().getMethod("getInventory").invoke(player);
        int size=(int)inventory.getClass().getMethod("getContainerSize").invoke(inventory);
        for(int i=0;i<size;++i) s.append("slot_").append(i).append('=').append(inventory.getClass().getMethod("getItem",int.class).invoke(inventory,i)).append('\n');
        return s.toString();
    }
    private static void write(String side,String value) {
        try {Files.writeString(Path.of("endcraft-flight-"+side+".txt"),value);} catch(Exception ignored) {}
    }
}
