package io.github.sirp00m.npcortex;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.net.URI;

/** Immutable per-attempt messages. Never stores credentials or replays movement.
 * Disk IO runs on a worker. Orphan temp files fail closed.
 */
final class CPlusOutbox {
    static Path directory(net.minecraft.server.MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data/npcortex/transport");
    }
    static Path file(Path dir,String route,JsonObject body) {
        String task=body.get("task_id").getAsString();java.util.UUID.fromString(task);
        return dir.resolve(task+"-"+route.substring(route.lastIndexOf('/')+1)+".json");
    }
    static void write(Path file,JsonObject object) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp=file.resolveSibling(file.getFileName()+".tmp");
        byte[] bytes=object.toString().getBytes(StandardCharsets.UTF_8);
        try(FileChannel out=FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            ByteBuffer buf=ByteBuffer.wrap(bytes);while(buf.hasRemaining())out.write(buf);out.force(true);
        }
        Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    static JsonObject read(Path file) throws IOException {
        if(Files.exists(file.resolveSibling(file.getFileName()+".tmp")) || Files.isSymbolicLink(file)
                || Files.size(file)>60000)throw new IOException("outbox unsafe; preserve evidence and HOLD");
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }
    static String send(Path dir,URI base,String token,String route,JsonObject body) {
        try {
            Path file=file(dir,route,body);JsonObject record;
            if(Files.exists(file)) {
                record=read(file);
                if(!record.get("body").equals(body) || !route.equals(record.get("route").getAsString()))
                    throw new IOException("outbox identity/payload conflict");
            } else {
                record=new JsonObject();record.addProperty("route",route);record.add("body",body.deepCopy());
                record.addProperty("handoff_possible",false);write(file,record);
            }

            CPlusM03Fault.check(dir,route,body,false); // TEMP M03, inert unless explicitly armed
            String reply=io.github.sirp00m.npcortex.cplus.CPlusTransport.post(base.resolve(route),token,body.toString(),5,24000);
            CPlusM03Fault.check(dir,route,body,true); // TEMP M03, before runtime ACK/handoff
            // Before handing the reserve ACK back to the game thread, fence the crash window.
            if(route.endsWith("/reserve")){record.addProperty("handoff_possible",true);write(file,record);}
            return reply;
        }catch(IOException e){throw new IllegalStateException("C+ outbox HOLD: "+e.getMessage(),e);}
    }
}
