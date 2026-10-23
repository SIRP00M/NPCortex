package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.cplus.CPlusDurableLedger;
import io.github.sirp00m.npcortex.cplus.CPlusEffectRecovery;
import io.github.sirp00m.npcortex.cplus.CPlusF1EffectGuard;
import io.github.sirp00m.npcortex.cplus.CPlusLifecycle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** No Minecraft mutations: deterministic journal fault and effect tests. */
public final class CPlusF1DPlusSelfTest {
    private static int checks;
    private static void ok(boolean condition) { checks++; if(!condition)throw new AssertionError("check "+checks); }
    private static void rejects(IORunnable action) {
        checks++;
        try { action.run(); throw new AssertionError("expected fail-closed check "+checks); }
        catch(IOException|IllegalArgumentException|IllegalStateException expected) { /* expected */ }
    }
    interface IORunnable { void run() throws IOException; }
    private static final UUID W=UUID.randomUUID(), A=UUID.randomUUID(), T=UUID.randomUUID(),
            ACT=UUID.randomUUID(), ATT=UUID.randomUUID();
    private static final String FP="a".repeat(64);
    private static CPlusDurableLedger.Event event(CPlusDurableLedger j, CPlusDurableLedger.Stage stage,
            UUID action, UUID attempt, long epoch, String fingerprint, String evidence) {
        return new CPlusDurableLedger.Event(j.nextSequence(),UUID.randomUUID(),W,A,T,action,attempt,
                null,epoch,stage,fingerprint,evidence);
    }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("npcortex-cf1-dplus-");
        Path file=dir.resolve("action-ledger.bin");
        var fence=CPlusDurableLedger.Stage.FENCE;
        var intent=CPlusDurableLedger.Stage.INTENT;
        var begin=CPlusDurableLedger.Stage.EXECUTION_STARTED;
        var observe=CPlusDurableLedger.Stage.OBSERVED_PARTIAL;
        var external=CPlusDurableLedger.Stage.OBSERVED_EXTERNAL;
        var doubt=CPlusDurableLedger.Stage.IN_DOUBT;
        var reconcile=CPlusDurableLedger.Stage.RECONCILED;
        var done=CPlusDurableLedger.Stage.VERIFIED;
        try {
            try (CPlusDurableLedger j=CPlusDurableLedger.open(file)) {
                ok(j.nextSequence()==1);
                rejects(()->j.append(event(j,intent,ACT,ATT,1,FP,""))); // no session fence
                j.append(event(j,fence,UUID.randomUUID(),UUID.randomUUID(),1,FP,""));
                ok(j.nextSequence()==2);
                rejects(()->j.append(event(j,begin,ACT,ATT,1,FP,""))); // intent required
                j.append(event(j,intent,ACT,ATT,1,FP,""));
                ok(j.recovery(ACT)==CPlusDurableLedger.Recovery.PREPARED);
                rejects(()->j.append(event(j,intent,ACT,ATT,1,FP,""))); // no second attempt
                rejects(()->j.append(event(j,done,ACT,ATT,1,FP,"no-observation")));
                j.append(event(j,begin,ACT,ATT,1,FP,""));
                rejects(()->j.append(event(j,begin,ACT,ATT,1,FP,""))); // no duplicate execution
                rejects(()->j.append(event(j,CPlusDurableLedger.Stage.CANCELLED,ACT,ATT,1,FP,"cancel-ack")));
                ok(j.recovery(ACT)==CPlusDurableLedger.Recovery.IN_DOUBT);
                rejects(()->j.append(event(j,observe,ACT,ATT,1,"b".repeat(64),"inventory-1")));
                j.append(event(j,observe,ACT,ATT,1,FP,"inventory-1"));
                j.append(event(j,external,ACT,ATT,1,FP,"event-other-actor-2"));
                ok(j.recovery(ACT)==CPlusDurableLedger.Recovery.IN_DOUBT);
                j.append(event(j,doubt,ACT,ATT,1,FP,""));
                rejects(()->j.append(event(j,done,ACT,ATT,1,FP,"too-early")));
                j.append(event(j,reconcile,ACT,ATT,1,FP,"world-observation-4"));
                j.append(event(j,done,ACT,ATT,1,FP,"verified-receipt-5"));
                ok(j.recovery(ACT)==CPlusDurableLedger.Recovery.VERIFIED);
                rejects(()->j.append(event(j,external,ACT,ATT,1,FP,"late-claim")));
                rejects(()->CPlusDurableLedger.open(file)); // live file lock
            }
            try (CPlusDurableLedger j=CPlusDurableLedger.open(file)) {
                ok(j.recovery(ACT)==CPlusDurableLedger.Recovery.VERIFIED);
                rejects(()->j.append(event(j,intent,UUID.randomUUID(),UUID.randomUUID(),1,FP,"")));
                j.append(event(j,fence,UUID.randomUUID(),UUID.randomUUID(),2,FP,""));
                UUID second=UUID.randomUUID(); UUID secondAttempt=UUID.randomUUID();
                j.append(event(j,intent,second,secondAttempt,2,FP,""));
                j.append(event(j,begin,second,secondAttempt,2,FP,""));
                ok(j.recovery(second)==CPlusDurableLedger.Recovery.IN_DOUBT);
            }
            byte[] original=Files.readAllBytes(file);
            // crash during last append must NOT silently truncate, skip or replay.
            Files.write(file,java.util.Arrays.copyOf(original,original.length-7));
            rejects(()->CPlusDurableLedger.open(file));
            Files.write(file,original);
            byte[] corrupted=original.clone(); corrupted[30]^=0x4;
            Files.write(file,corrupted);
            rejects(()->CPlusDurableLedger.open(file));
            Files.write(file,original);
            try(CPlusDurableLedger j=CPlusDurableLedger.open(file)) {ok(j.recordedActions()==2);}
            var owned=new CPlusEffectRecovery.Observation(true,20,8,4,0,"server:verified-inventory-1");
            var resolution=CPlusEffectRecovery.resolve(owned);
            ok(resolution.outcome()==CPlusEffectRecovery.Outcome.EXTERNALLY_CONSUMED);
            ok(resolution.next()==CPlusEffectRecovery.Action.REPLAN_REMAINING);
            ok(resolution.credited()==8 && resolution.remaining()==12);
            ok(CPlusEffectRecovery.resolve(new CPlusEffectRecovery.Observation(true,20,20,0,0,"inv-2")).next()
                    ==CPlusEffectRecovery.Action.COMPLETE);
            ok(CPlusEffectRecovery.resolve(new CPlusEffectRecovery.Observation(true,20,8,4,8,"partial-uncertain")).outcome()
                    ==CPlusEffectRecovery.Outcome.UNKNOWN);
            ok(CPlusEffectRecovery.resolve(new CPlusEffectRecovery.Observation(false,20,8,4,0,"")).credited()==0);
            rejects(()->new CPlusEffectRecovery.Observation(true,20,8,4,9,"bad"));
            rejects(()->new CPlusEffectRecovery.Observation(true,20,8,4,0,""));
            ok(CPlusF1EffectGuard.classifyFailure(CPlusLifecycle.State.EXECUTING,true,
                    CPlusF1EffectGuard.Evidence.NONE)==CPlusLifecycle.State.IN_DOUBT);
            ok(CPlusF1EffectGuard.classifyFailure(CPlusLifecycle.State.VERIFYING,true,
                    CPlusF1EffectGuard.Evidence.RECONCILED_OBSERVATION)==CPlusLifecycle.State.IN_DOUBT);
            ok(CPlusF1EffectGuard.classifyFailure(CPlusLifecycle.State.EXECUTING,true,
                    CPlusF1EffectGuard.Evidence.VERIFIED_NO_EFFECT)==CPlusLifecycle.State.FAILED);
            ok(!CPlusF1EffectGuard.allowCompensation(true,false,true));
            ok(CPlusF1EffectGuard.allowCompensation(true,true,true));
            System.out.println("PASS C-F1 D+ pure Java synthetic checks="+checks);
        } finally {
            Files.deleteIfExists(file); Files.deleteIfExists(dir);
        }
    }
}
