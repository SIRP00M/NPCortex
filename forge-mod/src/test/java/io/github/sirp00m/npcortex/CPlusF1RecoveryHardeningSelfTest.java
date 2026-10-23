package io.github.sirp00m.npcortex;

import com.sun.net.httpserver.HttpServer;
import io.github.sirp00m.npcortex.cplus.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** H1-H4: persistent local evidence, read-only remote comparison, worker isolation. */
public final class CPlusF1RecoveryHardeningSelfTest {
    private static int checks;
    private static void ok(boolean value) {checks++;if(!value)throw new AssertionError("C-F1 hardening check="+checks);}
    private static final String TOKEN="test-123456789012345678901234567890";
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("cf1-harden-");
        Path journal=dir.resolve("test.bin"),cursor=dir.resolve("cursor.dat");
        UUID world=UUID.randomUUID(),actor=UUID.randomUUID(),task=UUID.randomUUID(),
                action=UUID.randomUUID(),attempt=UUID.randomUUID();
        HttpServer remote=null;
        try {
            List<CPlusDurableLedger.LedgerRecord> records;
            try(CPlusDurableLedger ledger=CPlusDurableLedger.open(journal)) {
                String fp="a".repeat(64);
                ledger.append(new CPlusDurableLedger.Event(1,UUID.randomUUID(),world,actor,task,UUID.randomUUID(),
                        UUID.randomUUID(),null,1,CPlusDurableLedger.Stage.FENCE,fp,""));
                ledger.append(new CPlusDurableLedger.Event(2,UUID.randomUUID(),world,actor,task,action,
                        attempt,null,1,CPlusDurableLedger.Stage.INTENT,fp,""));
                ledger.append(new CPlusDurableLedger.Event(3,UUID.randomUUID(),world,actor,task,action,
                        attempt,null,1,CPlusDurableLedger.Stage.CANCELLED,fp,"synthetic-no-effects"));
                records=ledger.deliverySnapshot();
            }
            var source=new CPlusF1Delivery.Source(records);
            ok(source.lastSequence()==3 && source.worldId().equals(world));
            ok(CPlusF1Delivery.inspect(source,cursor).pending()==3);
            Path temp=cursor.resolveSibling(cursor.getFileName()+".tmp");
            Files.writeString(temp,"orphan journal evidence",StandardCharsets.UTF_8);
            ok(CPlusF1Delivery.inspect(source,cursor).state().equals("HOLD"));
            try {CPlusF1Delivery.send(source,cursor,new URI("http://127.0.0.1:1"),TOKEN,8);
                 throw new AssertionError("orphaned checkpoint not held"); }
            catch(java.io.IOException expected) {ok(Files.exists(temp));}
            Files.delete(temp); // explicit test-only cleanup; production never auto-removes
            Files.writeString(cursor,"broken\n");
            ok(CPlusF1Delivery.inspect(source,cursor).state().equals("HOLD"));
            Files.delete(cursor);
            // Final Gate: cursor corruption and crash-residue matrix, entirely
            // inside a disposable temporary directory; no operator world touched.
            List<String> unsafeCursors=List.of(
                    "",                         // truncated/empty disk write
                    "3 "+records.get(2).recordHash(), // missing trailing LF
                    "3 "+"f".repeat(64)+"\n", // hash not in durable journal
                    "4 "+records.get(2).recordHash()+"\n", // ahead of journal
                    "9999999999999999999 "+"f".repeat(64)+"\n", // overflows long
                    "3 "+records.get(2).recordHash().toUpperCase(java.util.Locale.ROOT)+"\n",
                    "z".repeat(97));            // oversized cursor
            for(String bad:unsafeCursors) {
                Files.writeString(cursor,bad,StandardCharsets.US_ASCII);
                ok(CPlusF1Delivery.inspect(source,cursor).state().equals("HOLD"));
                ok(Files.readString(cursor,StandardCharsets.US_ASCII).equals(bad));
                Files.delete(cursor);
            }
            String validCursor="3 "+records.get(2).recordHash()+"\n";
            Files.writeString(cursor,validCursor,StandardCharsets.US_ASCII);
            Files.writeString(temp,"orphan after atomic replace",StandardCharsets.US_ASCII);
            ok(CPlusF1Delivery.inspect(source,cursor).state().equals("HOLD"));
            ok(Files.exists(temp) && Files.readString(cursor,StandardCharsets.US_ASCII).equals(validCursor));
            Files.delete(temp); // cleanup ONLY in synthetic fixture
            ok(CPlusF1Delivery.inspect(source,cursor).state().equals("READY"));
            Files.delete(cursor);
            ok(CPlusF1Delivery.inspect(source,cursor).state().equals("READY"));
            AtomicReference<String> answer=new AtomicReference<>();
            remote=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            remote.createContext("/v1/cplus/f1/status",exchange -> {
                byte[] payload=answer.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,payload.length);
                try(var out=exchange.getResponseBody()) {out.write(payload);}
            });
            remote.start();
            URI base=new URI("http://127.0.0.1:"+remote.getAddress().getPort());
            answer.set(status(world,0,"0".repeat(64)));
            ok(CPlusF1Delivery.reconcile(source,cursor,base,TOKEN).state().equals("PENDING"));
            answer.set(status(world,3,"f".repeat(64)));
            ok(CPlusF1Delivery.reconcile(source,cursor,base,TOKEN).state().equals("HOLD"));
            answer.set(status(UUID.randomUUID(),3,records.get(2).recordHash()));
            ok(CPlusF1Delivery.reconcile(source,cursor,base,TOKEN).state().equals("HOLD"));
            answer.set(status(world,4,records.get(2).recordHash()));
            ok(CPlusF1Delivery.reconcile(source,cursor,base,TOKEN).state().equals("HOLD"));
            answer.set(status(world,3,records.get(2).recordHash()));
            ok(CPlusF1Delivery.reconcile(source,cursor,base,TOKEN).state().equals("PENDING"));
            var unfinished=new CPlusF1Delivery.Source(records.subList(0,2));
            answer.set(status(world,2,records.get(1).recordHash()));
            ok(CPlusF1Delivery.reconcile(unfinished,cursor,base,TOKEN).state().equals("IN_DOUBT"));
            answer.set(status(world,3,records.get(2).recordHash()));
            Files.writeString(cursor,"3 "+records.get(2).recordHash()+"\n",StandardCharsets.US_ASCII);
            ok(CPlusF1Delivery.reconcile(source,cursor,base,TOKEN).state().equals("READY"));
            answer.set(status(world,2,records.get(1).recordHash()));
            ok(CPlusF1Delivery.reconcile(source,cursor,base,TOKEN).state().equals("HOLD"));
            remote.stop(0);remote=null;
            // Simulate slow backend: confirm inspect/probe are not starved by HTTP.
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            remote=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            remote.createContext("/v1/cplus/f1/events",exchange -> {
                entered.countDown();
                try{release.await(4,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                byte[] data="unavailable".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503,data.length);
                try(var out=exchange.getResponseBody()){out.write(data);}
            });
            remote.start();
            Path live=dir.resolve("live.bin");
            try(CPlusF1AsyncDiagnostics diag=new CPlusF1AsyncDiagnostics(live,world)) {
                diag.probe(actor).get(4,TimeUnit.SECONDS);
                var pending=diag.sync("http://127.0.0.1:"+remote.getAddress().getPort(),TOKEN);
                ok(entered.await(2,TimeUnit.SECONDS));
                // Backend is still blocked. Journal write MUST finish before network is released.
                var probe=diag.probe(actor).get(2,TimeUnit.SECONDS);
                ok(probe.durableSequence()==5);
                ok(diag.inspect().get(2,TimeUnit.SECONDS).actions()==2);
                release.countDown();
                try{pending.get(5,TimeUnit.SECONDS);throw new AssertionError("unexpected fake ACK");}
                catch(ExecutionException expected) {ok(diag.syncStatus().state().equals("HOLD"));}
                var durable=diag.deliveryStatus().get(3,TimeUnit.SECONDS);
                ok(durable.checkpoint()==0 && durable.pending()==5);
            }
            remote.stop(0);remote=null;
            System.out.println("PASS C-F1 recovery hardening Java checks="+checks);
        } finally {
            if(remote!=null)remote.stop(0);
            try(var paths=Files.list(dir)) { for(var p:paths.toList()) Files.deleteIfExists(p); }
            Files.deleteIfExists(dir);
        }
    }
    private static String status(UUID world,long sequence,String hash) {
        return "{\"world_id\":\""+world+"\",\"sequence\":"+sequence+
                ",\"record_hash\":\""+hash+"\",\"events\":"+sequence+"}";
    }
}
