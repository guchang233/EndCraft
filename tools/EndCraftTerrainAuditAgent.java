import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import com.sun.tools.attach.VirtualMachine;

/** Compare terrain queries on the two actual game threads without moving a player. */
public class EndCraftTerrainAuditAgent {
    public static void main(String[] args) throws Exception {
        var vm=VirtualMachine.attach(args[0]);
        try {vm.loadAgent(args[1]);} finally {vm.detach();}
    }
    static String audit(Object player,ClassLoader loader) throws Exception {
        var entity=Class.forName("net.minecraft.world.entity.Entity",false,loader);
        var vec=Class.forName("net.minecraft.world.phys.Vec3",false,loader);
        var sky=Class.forName("dev.skycraft.world.SkyCollision",false,loader);
        var helper=Class.forName("dev.skycraft.world.SmoothTerrainCollision",false,loader);
        var ctor=vec.getConstructor(double.class,double.class,double.class);
        var vanilla=entity.getDeclaredMethod("collide",vec);vanilla.setAccessible(true);
        var result=new StringBuilder("player="+player+"\nposition="+entity.getMethod("position").invoke(player));
        result.append("\nsmoothPredicate=").append(sky.getMethod("usesSmoothCollider",entity).invoke(null,player));
        for(double[] d:new double[][]{{.2,-.08,0},{-.2,-.08,0},{0,-.08,.2},{0,-.08,-.2},{.14,-.08,.14},{-.14,-.08,-.14},{0,.42,0}}) {
            var move=ctor.newInstance(d[0],d[1],d[2]);
            result.append("\nrequested=").append(move).append(" actual=").append(vanilla.invoke(player,move))
                .append(" solver=").append(helper.getMethod("collide",entity,vec).invoke(null,player,move));
        }
        return result.toString();
    }
    static void write(String side,String result) {try {Files.writeString(Path.of("endcraft-terrain-audit-"+side+".txt"),result);}catch(Exception ignored){}}
    public static void agentmain(String args,Instrumentation instrumentation) throws Exception {
        Class<?> type=null;for(var c:instrumentation.getAllLoadedClasses()) if(c.getName().equals("net.minecraft.client.Minecraft")){type=c;break;}
        if(type==null) throw new IllegalStateException("Not the project Minecraft client");
        var client=type.getMethod("getInstance").invoke(null);var loader=type.getClassLoader();
        type.getMethod("execute",Runnable.class).invoke(client,(Runnable)()->{
            try {
                var player=client.getClass().getField("player").get(client);
                write("client",audit(player,loader));
                var server=client.getClass().getMethod("getSingleplayerServer").invoke(client);
                if(server==null) {write("server","No integrated server");return;}
                var uuid=player.getClass().getMethod("getUUID").invoke(player);
                server.getClass().getMethod("execute",Runnable.class).invoke(server,(Runnable)()->{
                    try {
                        var list=server.getClass().getMethod("getPlayerList").invoke(server);
                        var sp=list.getClass().getMethod("getPlayer",java.util.UUID.class).invoke(list,uuid);
                        write("server",audit(sp,loader));
                    }catch(Exception e){write("server","error="+e);}
                });
            }catch(Exception e){write("client","error="+e);}
        });
    }
}
