import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import com.sun.tools.attach.VirtualMachine;

/** Two temporary MC blocks in the real scene; originals restored on the server thread. */
public class EndCraftDepthAuditAgentV2 {
    static Object server,level;static Class<?> posType,stateType;
    static final Map<Object,Object[]> edits=new LinkedHashMap<>();
    static void note(String text){try{Files.writeString(Path.of("endcraft-depth-audit.txt"),text+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception ignored){}}
    public static void main(String[] args)throws Exception{
        var vm=VirtualMachine.attach(args[0]);try{vm.loadAgent(args[1],args[2]);}finally{vm.detach();}
    }
    static void restore() {
        if(server==null)return;
        try {server.getClass().getMethod("execute",Runnable.class).invoke(server,(Runnable)()->{
            try {
                for(var e:edits.entrySet()) {
                    var current=level.getClass().getMethod("getBlockState",posType).invoke(level,e.getKey());
                    if(current.equals(e.getValue()[1]))level.getClass().getMethod("setBlockAndUpdate",posType,stateType).invoke(level,e.getKey(),e.getValue()[0]);
                }
                edits.clear();note("Temporary marker blocks restored; external changes preserved.");
            }catch(Exception e){note("restore error="+e);}
        });}catch(Exception e){note("restore error="+e);}
    }
    public static void agentmain(String operation,Instrumentation instrumentation)throws Exception {
        if(operation.equals("restore")){restore();return;}
        if(!operation.equals("place")||!edits.isEmpty())throw new IllegalArgumentException("place/restore only; restore previous test first");
        Class<?> type=null;for(var c:instrumentation.getAllLoadedClasses())if(c.getName().equals("net.minecraft.client.Minecraft")){type=c;break;}
        if(type==null)throw new IllegalStateException("Not Minecraft");
        var client=type.getMethod("getInstance").invoke(null);var loader=type.getClassLoader();
        type.getMethod("execute",Runnable.class).invoke(client,(Runnable)()->{
            try {
                var player=client.getClass().getField("player").get(client);server=client.getClass().getMethod("getSingleplayerServer").invoke(client);
                var uuid=player.getClass().getMethod("getUUID").invoke(player);
                double px=(double)player.getClass().getMethod("getX").invoke(player),py=(double)player.getClass().getMethod("getY").invoke(player),pz=(double)player.getClass().getMethod("getZ").invoke(player);
                double yaw=Math.toRadians((float)player.getClass().getMethod("getYRot").invoke(player));
                int x=(int)Math.floor(px-Math.sin(yaw)*4),z=(int)Math.floor(pz+Math.cos(yaw)*4);
                var collider=Class.forName("dev.skycraft.client.SkyCollider",false,loader);
                double ground=(double)collider.getMethod("groundAt",double.class,double.class,double.class,double.class).invoke(null,x+.5,py,z+.5,3.0);
                if(!Double.isFinite(ground))throw new IllegalStateException("No verified test terrain");
                server.getClass().getMethod("execute",Runnable.class).invoke(server,(Runnable)()->{
                    try {
                        var list=server.getClass().getMethod("getPlayerList").invoke(server);var sp=list.getClass().getMethod("getPlayer",UUID.class).invoke(list,uuid);
                        level=sp.getClass().getMethod("level").invoke(sp);
                        posType=Class.forName("net.minecraft.core.BlockPos",true,loader);stateType=Class.forName("net.minecraft.world.level.block.state.BlockState",true,loader);
                        var blocks=Class.forName("net.minecraft.world.level.block.Blocks",true,loader);
                        StringBuilder log=new StringBuilder("measured ground="+ground);
                        for(int i=0;i<2;++i){
                            int y=i==0?(int)Math.floor(ground)-1:(int)Math.ceil(ground)+1;
                            var p=posType.getConstructor(int.class,int.class,int.class).newInstance(x+i,y,z);
                            var old=level.getClass().getMethod("getBlockState",posType).invoke(level,p);
                            var block=blocks.getField(i==0?"RED_CONCRETE":"LIME_CONCRETE").get(null);var state=block.getClass().getMethod("defaultBlockState").invoke(block);
                            edits.put(p,new Object[]{old,state});level.getClass().getMethod("setBlockAndUpdate",posType,stateType).invoke(level,p,state);
                            log.append("\n").append(i==0?"underground red":"above-ground lime").append("=").append(p);
                        }
                        note(log.toString());
                        new Timer(true).schedule(new TimerTask(){public void run(){restore();}},60000);
                    }catch(Exception e){note("place error="+e);restore();}
                });
            }catch(Exception e){note("client error="+e);}
        });
    }
}
