package io.github.sirp00m.npcortex;
import com.google.gson.*;
import java.nio.file.*;
import java.util.UUID;
public final class CPlusOutboxSelfTest {
    public static void main(String[]args)throws Exception{
        Path dir=Files.createTempDirectory("npcortex-outbox-test-");
        try{
            JsonObject body=new JsonObject();body.addProperty("task_id",UUID.randomUUID().toString());
            JsonObject record=new JsonObject();record.add("body",body);record.addProperty("handoff_possible",false);
            Path file=CPlusOutbox.file(dir,"/v1/cplus/e3/reserve",body);
            CPlusOutbox.write(file,record);
            if(!CPlusOutbox.read(file).equals(record))throw new AssertionError("roundtrip");
            record.addProperty("handoff_possible",true);CPlusOutbox.write(file,record);
            if(!CPlusOutbox.read(file).get("handoff_possible").getAsBoolean())throw new AssertionError("handoff marker");
            Files.writeString(file.resolveSibling(file.getFileName()+".tmp"),"interrupted");
            try{CPlusOutbox.read(file);throw new AssertionError("orphan temp accepted");}catch(java.io.IOException expected){}
            System.out.println("PASS outbox immutable identity / durable marker / orphan HOLD");
        }finally{try(var files=Files.list(dir)){for(Path f:files.toList())Files.delete(f);}Files.delete(dir);}
    }
}
