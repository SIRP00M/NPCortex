package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.cplus.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Cross-language golden fixture + restart delivery cursor safety; no Minecraft classes. */
public final class CPlusF1TransportSelfTest {
    private static int checks;
    private static void ok(boolean yes) {checks++;if(!yes)throw new AssertionError("C-F1 transport check="+checks);}
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("npcortex-f1-wire-");
        Path journal=root.resolve("ledger.bin"), cursor=root.resolve("delivery.cursor");
        UUID world=UUID.fromString("e8d1de63-5e11-42e8-ad64-e234315bb749");
        UUID actor=UUID.fromString("684fbc7e-46e4-4813-b821-d90897b359c0");
        UUID task=UUID.randomUUID(),action=UUID.randomUUID(),attempt=UUID.randomUUID();
        String fp="a".repeat(64);
        List<String> data=new ArrayList<>();
        try {
            try(CPlusDurableLedger ledger=CPlusDurableLedger.open(journal)) {
                ledger.append(new CPlusDurableLedger.Event(1,UUID.randomUUID(),world,actor,task,
                    UUID.randomUUID(),UUID.randomUUID(),null,1,CPlusDurableLedger.Stage.FENCE,fp,""));
                ledger.append(new CPlusDurableLedger.Event(2,UUID.randomUUID(),world,actor,task,
                    action,attempt,null,1,CPlusDurableLedger.Stage.INTENT,fp,""));
                ledger.append(new CPlusDurableLedger.Event(3,UUID.randomUUID(),world,actor,task,
                    action,attempt,null,1,CPlusDurableLedger.Stage.CANCELLED,fp,"synthetic-ทดสอบ-no-effect"));
                var rows=ledger.recordsAfter(0,8);
                ok(rows.size()==3);
                ok(ledger.matchesCheckpoint(0,"0".repeat(64)));
                ok(ledger.matchesCheckpoint(3,rows.get(2).recordHash()));
                ok(!ledger.matchesCheckpoint(3,"f".repeat(64)));
                for(var row:rows) {
                    String wire=CPlusF1Wire.json(row);
                    ok(wire.contains("\"sequence\":"+row.event().sequence()));
                    ok(CPlusF1Wire.expectedReceipt(row).length()==64);
                    data.add(wire);
                }
                boolean malformedHold=false;
                Files.writeString(cursor,"broken cursor\n",StandardCharsets.US_ASCII);
                try {
                    CPlusF1Delivery.send(ledger,cursor,CPlusF1Delivery.safeBackend("http://127.0.0.1:8765"),
                            "123456789012345678901234",1);
                } catch(java.io.IOException failClosed) {malformedHold=true;}
                ok(malformedHold);
                Files.delete(cursor);
                boolean denied=false;
                try { CPlusF1Delivery.safeBackend("https://example.com:9000"); }
                catch(IllegalArgumentException correct) { denied=true; }
                ok(denied);
            }
            if(args.length>0) Files.write(Path.of(args[0]),data,StandardCharsets.UTF_8);
            if(args.length>1) {
                String token=System.getenv("NPCORTEX_CPLUS_F1_TOKEN");
                try(CPlusDurableLedger ledger=CPlusDurableLedger.open(journal)) {
                    var out=CPlusF1Delivery.send(ledger,cursor,CPlusF1Delivery.safeBackend(args[1]),token,8);
                    ok(out.newlyAcknowledged()==3 && out.acknowledgedSequence()==3 && out.remainingEvents()==0);
                    var repeated=CPlusF1Delivery.send(ledger,cursor,CPlusF1Delivery.safeBackend(args[1]),token,8);
                    ok(repeated.newlyAcknowledged()==0 && repeated.acknowledgedSequence()==3);
                }
            }
            System.out.println("PASS C-F1 Java wire/transport checks="+checks);
        } finally {
            Files.deleteIfExists(cursor);
            Files.deleteIfExists(journal);
            Files.deleteIfExists(root);
        }
    }
}
