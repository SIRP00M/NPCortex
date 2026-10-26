package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.cplus.CPlusM04Contract;
import java.util.UUID;

/** Pure Java contract gates; cannot certify native mining or crash durability. */
public final class CPlusM04ContractSelfTest {
    private static int count;
    private static void yes(boolean value, String msg) {count++; if(!value)throw new AssertionError(msg);}
    private static void rejects(Runnable task) {
        boolean ok=false; try{task.run();}catch(IllegalArgumentException|IllegalStateException expected){ok=true;}
        yes(ok,"must reject unsafe transition");
    }
    public static void main(String[] argv) {
        var id=new CPlusM04Contract.Identity(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        var state=CPlusM04Contract.newTask(id);
        yes(state.nextStep()==0,"planned");
        rejects(()->CPlusM04Contract.acceptVerified(state,id,
                witness(CPlusM04Contract.Step.BREAK,CPlusM04Contract.Effect.VERIFIED)));
        rejects(()->CPlusM04Contract.acceptVerified(state,id,
                witness(CPlusM04Contract.Step.OBSERVE,CPlusM04Contract.Effect.UNKNOWN)));
        var wrong=new CPlusM04Contract.Identity(id.world(),id.actor(),UUID.randomUUID(),id.requester(),id.task(),id.attempt());
        rejects(()->CPlusM04Contract.acceptVerified(state,wrong,
                witness(CPlusM04Contract.Step.OBSERVE,CPlusM04Contract.Effect.NONE)));
        var first=CPlusM04Contract.acceptVerified(state,id,
                witness(CPlusM04Contract.Step.OBSERVE,CPlusM04Contract.Effect.NONE));
        yes(first.nextStep()==1 && first.lifecycle()==CPlusM04Contract.Lifecycle.ACTIVE,"first committed witness");
        rejects(()->CPlusM04Contract.acceptVerified(first,id,
                witness(CPlusM04Contract.Step.OBSERVE,CPlusM04Contract.Effect.NONE)));
        var parked=CPlusM04Contract.hold(first,id,CPlusM04Contract.Effect.PARTIAL);
        yes(parked.nextStep()==1 && parked.witnesses().size()==1,"partial is preserved");
        rejects(()->CPlusM04Contract.acceptVerified(parked,id,
                witness(CPlusM04Contract.Step.EQUIP,CPlusM04Contract.Effect.NONE)));
        yes(CPlusM04Contract.metadataRetry(parked,id)==parked,"metadata does not replay");
        rejects(()->CPlusM04Contract.metadataRetry(parked,wrong));
        var complete=CPlusM04Contract.newTask(id);
        for(var step:CPlusM04Contract.ORDER){
            var outcome=step==CPlusM04Contract.Step.TERMINAL || step==CPlusM04Contract.Step.VERIFY_BLOCK
                    || step==CPlusM04Contract.Step.PICKUP || step==CPlusM04Contract.Step.VERIFY_INVENTORY
                    ? CPlusM04Contract.Effect.VERIFIED : CPlusM04Contract.Effect.NONE;
            complete=CPlusM04Contract.acceptVerified(complete,id,witness(step,outcome));
        }
        yes(complete.lifecycle()==CPlusM04Contract.Lifecycle.COMPLETED,"terminal needs all witnesses");
        var terminal=complete;
        rejects(()->CPlusM04Contract.hold(terminal,id,CPlusM04Contract.Effect.UNKNOWN));
        rejects(()->CPlusM04Contract.acceptVerified(terminal,id,witness(CPlusM04Contract.Step.TERMINAL,CPlusM04Contract.Effect.VERIFIED)));
        System.out.println("M04 D-Lite Contract PASS: "+count+" assertions");
    }
    private static CPlusM04Contract.StepWitness witness(CPlusM04Contract.Step step,CPlusM04Contract.Effect effect){
        return new CPlusM04Contract.StepWitness(step,UUID.randomUUID(),42,"a".repeat(64),effect);
    }
}
