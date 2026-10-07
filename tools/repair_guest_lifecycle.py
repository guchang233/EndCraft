"""Idempotent lifecycle repair applied after adapt_guest.py to the pinned guest."""
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
path=ROOT/'mc/src/main/java/dev/skycraft/link/SkyLink.java'
text=path.read_text(encoding='utf-8')
if 'withSegment' not in text:
    text=text.replace('private static final MethodHandle MAP_VIEW_OF_FILE;',
        'private static final MethodHandle MAP_VIEW_OF_FILE;\n\tprivate static final MethodHandle CLOSE_HANDLE;\n\tprivate static final MethodHandle UNMAP_VIEW;\n\tprivate static MemorySegment mappingHandle;')
    text=text.replace('\t\tGET_TICK_COUNT64 =',
        '\t\tCLOSE_HANDLE = linker.downcallHandle(k32.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));\n'
        '\t\tUNMAP_VIEW = linker.downcallHandle(k32.find("UnmapViewOfFile").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));\n\t\tGET_TICK_COUNT64 =')
    text=text.replace('public static MemorySegment segment() {\n\t\treturn shm;\n\t}',
        '''public static <T> T withSegment(java.util.function.Function<MemorySegment,T> operation) {
        // All external access holds the same lock as disconnect/unmap.
        return operation.apply(shm);
    }
    private static void disconnect() {
        MemorySegment old=shm; shm=null; skyrimPid=0; generation++; overlayBack=1;
        try {
            if(old!=null) { int ignored=(int)UNMAP_VIEW.invokeExact(old); }
            if(mappingHandle!=null) { int ignored=(int)CLOSE_HANDLE.invokeExact(mappingHandle); }
        } catch(Throwable error) { SkyCraft.LOG.warn("EndCraft: mapping cleanup failed",error); }
        mappingHandle=null;
    }''')
    text=text.replace('if (shm != null) {\n\t\t\tLONG.setRelease',
        '''if (shm != null) {
            long beat=(long)LONG.getAcquire(shm,OFF_HEADER+H_SKYRIM_HEARTBEAT);
            long now=tickCount();
            if(beat==0 || now<beat || now-beat>=HEARTBEAT_TIMEOUT_MS) {
                disconnect();
                SkyCraft.LOG.info("EndCraft: host disconnected; released mapping for reconnect");
                return;
            }
\t\t\tLONG.setRelease''')
    text=text.replace('SkyCraft.LOG.error("SkyCraft: MapViewOfFile failed");',
        'int ignored=(int)CLOSE_HANDLE.invokeExact(handle);\n\t\t\t\tSkyCraft.LOG.error("EndCraft: MapViewOfFile failed");')
    text=text.replace('if (magic != MAGIC || version != VERSION) {',
        '''long beat=seg.get(JAVA_LONG,OFF_HEADER+H_SKYRIM_HEARTBEAT);
            long nativeNow=tickCount();
            if(beat==0 || nativeNow<beat || nativeNow-beat>=HEARTBEAT_TIMEOUT_MS) {
                int ignoredView=(int)UNMAP_VIEW.invokeExact(view);
                int ignoredHandle=(int)CLOSE_HANDLE.invokeExact(handle);
                return;
            }
            if (magic != MAGIC || version != VERSION) {
                int ignoredView=(int)UNMAP_VIEW.invokeExact(view);
                int ignoredHandle=(int)CLOSE_HANDLE.invokeExact(handle);''')
    text=text.replace('\t\t\tshm = seg;', '\t\t\tmappingHandle=handle;\n\t\t\tshm = seg;')
    text=re.sub(r'public static (?!synchronized|final|class)', 'public static synchronized ',text)
    text=text.replace('linked to Skyrim','linked to Endfield').replace("Skyrim's shared memory","Endfield shared memory").replace("Skyrim hasn't created it yet","Endfield has not created it yet").replace('is Skyrim running as administrator?','host mapping permissions are incompatible')
    path.write_text(text,encoding='utf-8')

path=ROOT/'mc/src/main/java/dev/skycraft/world/SkyCollision.java'
text=path.read_text(encoding='utf-8')
text=text.replace('private static boolean drainOnce() {\n\t\tMemorySegment s = SkyLink.segment();',
    'private static boolean drainOnce() {\n\t\treturn SkyLink.withSegment(SkyCollision::drainMapped);\n\t}\n\tprivate static boolean drainMapped(MemorySegment s) {')
path.write_text(text,encoding='utf-8')

path=ROOT/'mc/src/client/java/dev/skycraft/client/FrameExporter.java'
text=path.read_text(encoding='utf-8')
if 'SkyLink.segment()' in text:
    text=text.replace('\t\tMemorySegment shm = SkyLink.segment();', '\t\tfinal Staging finished=newest;\n\t\tSkyLink.withSegment(shm -> {')
    start=text.index('SkyLink.withSegment(shm -> {')
    end=text.index('// Anything older',start)
    chunk=text[start:end].replace('newest.','finished.')
    text=text[:start]+chunk+'return null;\n\t\t});\n\t\t'+text[end:]
path.write_text(text,encoding='utf-8')

path=ROOT/'mc/src/client/java/dev/skycraft/client/ProbeTelemetry.java'
text=path.read_text(encoding='utf-8')
text=text.replace('var memory = SkyLink.segment();\n        if (memory == null || SkyLink.active()) return;',
    '''SkyLink.withSegment(memory -> {
            if(memory!=null && !SkyLink.active()) publish(client,memory);
            return null;
        });
    }
    private static void publish(Minecraft client,java.lang.foreign.MemorySegment memory) {''')
path.write_text(text,encoding='utf-8')
print('Guest mapping lifecycle repaired; all raw access holds the disconnect lock.')
