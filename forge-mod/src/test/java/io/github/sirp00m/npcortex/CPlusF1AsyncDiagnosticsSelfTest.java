package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.cplus.CPlusF1AsyncDiagnostics;
import io.github.sirp00m.npcortex.cplus.CPlusDurableLedger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/** Tests worker-only durability, persistence, session epoch, corrupted-journal fail-closed. */
public final class CPlusF1AsyncDiagnosticsSelfTest {
    private static int checks;
    private static void ok(boolean value) { checks++; if (!value) throw new AssertionError("C-F1 live bridge check=" + checks); }
    public static void main(String[] unused) throws Exception {
        Path dir=Files.createTempDirectory("npcortex-f1-worker-");
        Path file=dir.resolve("test.bin");
        UUID world=UUID.randomUUID(), actor=UUID.randomUUID();
        UUID action1,action2;
        try {
            try (CPlusF1AsyncDiagnostics diag=new CPlusF1AsyncDiagnostics(file,world)) {
                var start=diag.inspect().get(5,TimeUnit.SECONDS);
                ok(start.actions()==0 && start.unresolved()==0 && start.state().equals("READY"));
                var one=diag.probe(actor).get(5, TimeUnit.SECONDS);
                var two=diag.probe(actor).get(5, TimeUnit.SECONDS);
                action1=one.actionId();action2=two.actionId();
                ok(one.epoch()==1 && two.epoch()==1);
                ok(one.durableSequence()==3 && two.durableSequence()==5);
                ok(diag.status().actions()==2 && diag.status().unresolved()==0);
                ok(diag.status().durableAcks()==2);
                ok(diag.status().lastProbe().contains("CANCELLED"));
                boolean offline=false;
                try {diag.sync("http://127.0.0.1:1","123456789012345678901234").get(7,TimeUnit.SECONDS);}
                catch(java.util.concurrent.ExecutionException expected){offline=true;}
                ok(offline && diag.syncStatus().state().equals("HOLD"));
                // Backend transport outage must not quarantine a healthy local journal.
                ok(diag.inspect().get(5,TimeUnit.SECONDS).state().equals("READY"));
            }
            try (CPlusDurableLedger verify=CPlusDurableLedger.open(file)) {
                ok(verify.recordedActions()==2);
                ok(verify.recovery(action1)==CPlusDurableLedger.Recovery.CANCELLED);
                ok(verify.recovery(action2)==CPlusDurableLedger.Recovery.CANCELLED);
            }
            try (CPlusF1AsyncDiagnostics restarted=new CPlusF1AsyncDiagnostics(file,world)) {
                ok(restarted.inspect().get(5,TimeUnit.SECONDS).actions()==2);
                var third=restarted.probe(actor).get(5,TimeUnit.SECONDS);
                ok(third.epoch()==2 && third.durableSequence()==8);
            }
            try(CPlusF1AsyncDiagnostics wrongWorld=new CPlusF1AsyncDiagnostics(file,UUID.randomUUID())) {
                boolean rejected=false;
                try { wrongWorld.inspect().join(); } catch(CompletionException expected) {rejected=true;}
                ok(rejected && wrongWorld.status().state().equals("ERROR"));
            }
            // Deliberately corrupt on disk: neither inspect nor probe repairs/truncates silently.
            byte[] bytes=Files.readAllBytes(file);
            bytes[19]^=0x40;
            Files.write(file,bytes);
            try(CPlusF1AsyncDiagnostics corrupt=new CPlusF1AsyncDiagnostics(file,world)) {
                boolean rejected=false;
                try { corrupt.inspect().join(); } catch(CompletionException expected) {rejected=true;}
                ok(rejected && corrupt.status().state().equals("ERROR"));
                rejected=false;
                try { corrupt.probe(actor).join(); } catch(CompletionException expected) {rejected=true;}
                ok(rejected && corrupt.status().actions()==0);
            }
            System.out.println("PASS C-F1 asynchronous diagnostic checks=" + checks);
        } finally {
            Files.deleteIfExists(file);Files.deleteIfExists(dir);
        }
    }
}
