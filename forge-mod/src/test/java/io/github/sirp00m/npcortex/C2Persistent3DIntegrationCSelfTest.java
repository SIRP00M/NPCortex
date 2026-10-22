package io.github.sirp00m.npcortex;
import java.util.List;
import java.util.UUID;

/** C-3D pure Java contracts, NOT a substitute for Forge physics/gameplay. */
public final class C2Persistent3DIntegrationCSelfTest {
    private static int count;
    private static void yes(boolean value) {count++;if(!value)throw new AssertionError("assertion "+count);}
    private static void rejected(Runnable action) {
        try {action.run();throw new AssertionError("Should reject");}
        catch(IllegalArgumentException expected){count++;}
    }
    public static void main(String[] args) {
        var a=new C2TraversabilityGraph.Node(0,68,0,69);
        var b=new C2TraversabilityGraph.Node(1,68,0,69);
        var c=new C2TraversabilityGraph.Node(2,69,0,70);
        var d=new C2TraversabilityGraph.Node(3,69,0,70);
        var e=new C2TraversabilityGraph.Node(4,68,0,69);
        var walk=new C1Persistent3DChain.Stage(a,b,C2TraversabilityGraph.MoveKind.WALK);
        var jump=new C1Persistent3DChain.Stage(b,c,C2TraversabilityGraph.MoveKind.JUMP_UP);
        var walk2=new C1Persistent3DChain.Stage(c,d,C2TraversabilityGraph.MoveKind.WALK);
        var descend=new C1Persistent3DChain.Stage(d,e,C2TraversabilityGraph.MoveKind.SAFE_DESCEND);
        var chain=new C1Persistent3DChain(List.of(walk,jump,walk2,descend));
        yes(chain.count()==4);yes(chain.current().equals(walk));yes(chain.consumed()==0);
        yes(chain.tick());yes(chain.stageAge()==1);yes(chain.advance());
        yes(chain.current().equals(jump));yes(chain.consumed()==1);yes(chain.stageAge()==0);
        yes(chain.advance());yes(chain.current().equals(walk2));yes(chain.advance());
        yes(chain.current().equals(descend));yes(!chain.advance());
        rejected(()->new C1Persistent3DChain(List.of(walk)));
        rejected(()->new C1Persistent3DChain(List.of(jump,walk)));
        rejected(()->new C1Persistent3DChain(List.of(walk,walk2)));
        rejected(()->new C1Persistent3DChain(List.of(walk,jump,walk2,descend,walk,jump,walk2)));
        rejected(()->new C1Persistent3DChain.Stage(a,c,C2TraversabilityGraph.MoveKind.JUMP_UP));
        rejected(()->new C1Persistent3DChain.Stage(b,c,C2TraversabilityGraph.MoveKind.WALK));
        var route=C2WaypointExecution.followSmoothed(List.of(a,b,c,d,e));
        yes(route.totalSegments()==4);yes(route.currentIndex()==1);
        UUID token=UUID.randomUUID();
        route.startedMixedChain(token,4);
        yes(route.expectedTicket().equals(token));yes(route.receiptTarget().equals(e));
        yes(route.observe(token,true,"",false)==C2WaypointExecution.Update.ACTIVE);
        yes(route.observe(UUID.randomUUID(),false,"MIXED_COMPLETE",true)==C2WaypointExecution.Update.FAILED);
        yes(route.observe(token,false,"MIXED_COMPLETE",true)==C2WaypointExecution.Update.COMPLETE);
        yes(route.done());yes(!route.waiting());yes(!route.acceptsTicket(token));
        var fail=C2WaypointExecution.followSmoothed(List.of(a,b,c));
        fail.startedMixedChain(token,2);
        yes(fail.observe(token,false,"MIXED_UNSAFE_NEXT_STAGE",false)==C2WaypointExecution.Update.FAILED);
        yes(!fail.done());yes(!fail.waiting());
        var non3d=C2WaypointExecution.followSmoothed(List.of(a,b));
        rejected(()->non3d.startedMixedChain(token,1));
        System.out.println("PASS C-3D mixed Follow contracts: "+count+" assertions (pure Java)");
    }
}
