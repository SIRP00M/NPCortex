package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.cplus.*;
import java.util.List;
import java.util.UUID;

/** Standalone Java17 C-F0 behavioral contracts; independent of Forge runtime. */
public final class CPlusF0ContractsSelfTest {
    private static int checks;
    private static void yes(boolean value) {
        checks++;
        if (!value) throw new AssertionError("check " + checks);
    }
    private static void rejects(Runnable action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("expected rejection on check " + checks);
    }
    private static final UUID W=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID A=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID G=UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID T=UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID X=UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID N=UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static CPlusIdentity id() { return new CPlusIdentity(W,"minecraft:overworld",A,G,T,X,N); }
    public static void main(String[] args) {
        CPlusIdentity identity=id();
        yes(identity.actorId().equals(A));
        yes(identity.attemptId().equals(N));
        rejects(()->new CPlusIdentity(new UUID(0,0),"minecraft:overworld",A,G,T,X,N));
        rejects(()->new CPlusIdentity(W,"minecraft:OVERWORLD",A,G,T,X,N));
        rejects(()->new CPlusIdentity(W,"bad dimension",A,G,T,X,N));
        rejects(()->new CPlusIdentity(W,"minecraft:overworld",A,G,T,X,X));
        rejects(()->new CPlusIdentity(W,"minecraft:overworld",A,null,T,X,N));
        yes(CPlusIdentity.resource("tacz:weapon/test", "skill").equals("tacz:weapon/test"));
        rejects(()->CPlusIdentity.resource("MINECRAFT:block", "skill"));
        var target=CPlusActionContract.Target.block(120,65,-84);
        yes(target.kind()==CPlusActionContract.TargetKind.BLOCK && target.entityId()==null);
        yes(CPlusActionContract.Target.none().kind()==CPlusActionContract.TargetKind.NONE);
        yes(CPlusActionContract.Target.entity(A).entityId().equals(A));
        rejects(()->new CPlusActionContract.Target(CPlusActionContract.TargetKind.BLOCK,1,null,3,null));
        rejects(()->new CPlusActionContract.Target(CPlusActionContract.TargetKind.NONE,1,2,3,null));
        rejects(()->new CPlusActionContract.Target(CPlusActionContract.TargetKind.ENTITY,null,null,null,new UUID(0,0)));
        rejects(()->CPlusActionContract.Target.block(Integer.MIN_VALUE,0,0));
        var expected=new CPlusActionContract.ExpectedOutcome("npcortex:block_state", "open", "true");
        var action=new CPlusActionContract(1,identity,"minecraft:use_block",target,expected);
        yes(action.protocolVersion()==CPlusActionContract.VERSION && action.target().x()==120);
        rejects(()->new CPlusActionContract(2,identity,"minecraft:use_block",target,expected));
        rejects(()->new CPlusActionContract(1,identity,"minecraft:UseBlock",target,expected));
        rejects(()->new CPlusActionContract(1,identity,"minecraft:use_block",null,expected));
        rejects(()->new CPlusActionContract.ExpectedOutcome("bad type","open","true"));
        rejects(()->new CPlusActionContract.ExpectedOutcome("npcortex:block_state","","true"));
        var no=CPlusLifecycle.Proof.none();
        var verified=new CPlusLifecycle.Proof(CPlusLifecycle.ProofKind.VERIFIED_WORLD_RECEIPT,"server:receipt-001");
        var cancel=new CPlusLifecycle.Proof(CPlusLifecycle.ProofKind.CANCEL_ACKNOWLEDGED,"c1:terminal-001");
        var recovery=new CPlusLifecycle.Proof(CPlusLifecycle.ProofKind.TRUSTED_RECONCILIATION,"world:observation-008");
        var st=CPlusLifecycle.State.REQUESTED;
        st=CPlusLifecycle.transition(st,CPlusLifecycle.State.VALIDATED,no);
        st=CPlusLifecycle.transition(st,CPlusLifecycle.State.WAIT_RESOURCE,no);
        st=CPlusLifecycle.transition(st,CPlusLifecycle.State.PREPARED,no);
        st=CPlusLifecycle.transition(st,CPlusLifecycle.State.EXECUTING,no);
        st=CPlusLifecycle.transition(st,CPlusLifecycle.State.VERIFYING,no);
        st=CPlusLifecycle.transition(st,CPlusLifecycle.State.COMPLETED,verified);
        yes(CPlusLifecycle.terminal(st));
        final var completed=st;
        rejects(()->CPlusLifecycle.transition(completed,CPlusLifecycle.State.EXECUTING,no));
        rejects(()->CPlusLifecycle.transition(CPlusLifecycle.State.VERIFYING,CPlusLifecycle.State.COMPLETED,no));
        rejects(()->CPlusLifecycle.transition(CPlusLifecycle.State.VERIFYING,CPlusLifecycle.State.COMPLETED,cancel));
        rejects(()->CPlusLifecycle.transition(CPlusLifecycle.State.EXECUTING,CPlusLifecycle.State.COMPLETED,verified));
        rejects(()->CPlusLifecycle.transition(CPlusLifecycle.State.IN_DOUBT,CPlusLifecycle.State.VERIFYING,no));
        yes(CPlusLifecycle.transition(CPlusLifecycle.State.IN_DOUBT,CPlusLifecycle.State.VERIFYING,recovery)
                ==CPlusLifecycle.State.VERIFYING);
        rejects(()->CPlusLifecycle.transition(CPlusLifecycle.State.CANCEL_PENDING,CPlusLifecycle.State.VERIFYING,no));
        yes(CPlusLifecycle.transition(CPlusLifecycle.State.CANCEL_PENDING,CPlusLifecycle.State.VERIFYING,recovery)
                ==CPlusLifecycle.State.VERIFYING);
        rejects(()->CPlusLifecycle.transition(CPlusLifecycle.State.CANCEL_PENDING,CPlusLifecycle.State.CANCELLED,no));
        yes(CPlusLifecycle.transition(CPlusLifecycle.State.CANCEL_PENDING,CPlusLifecycle.State.CANCELLED,cancel)
                ==CPlusLifecycle.State.CANCELLED);
        yes(CPlusLifecycle.transition(CPlusLifecycle.State.EXECUTING,CPlusLifecycle.State.IN_DOUBT,no)
                ==CPlusLifecycle.State.IN_DOUBT);
        rejects(()->new CPlusLifecycle.Proof(CPlusLifecycle.ProofKind.VERIFIED_WORLD_RECEIPT,""));
        rejects(()->new CPlusLifecycle.Proof(CPlusLifecycle.ProofKind.NONE,"spoofed"));
        yes(!CPlusLifecycle.canAutomaticallyRetry(CPlusLifecycle.State.FAILED,true));
        yes(!CPlusLifecycle.canAutomaticallyRetry(CPlusLifecycle.State.IN_DOUBT,false));
        yes(CPlusLifecycle.canAutomaticallyRetry(CPlusLifecycle.State.FAILED,false));
        var k1=CPlusResources.Key.actor(W,"minecraft:overworld",CPlusResources.Kind.ACTOR_MOTION,A);
        var k2=CPlusResources.Key.block(W,"minecraft:overworld",CPlusResources.Kind.CONTAINER,12,65,-5);
        var k3=CPlusResources.Key.actor(W,"minecraft:overworld",CPlusResources.Kind.ACTOR_INVENTORY,A);
        var sorted=CPlusResources.acquireOrder(List.of(k2,k3,k1));
        yes(sorted.size()==3);
        yes(sorted.get(0).equals(k1));
        yes(sorted.get(1).equals(k3));
        yes(sorted.get(2).equals(k2));
        rejects(()->CPlusResources.acquireOrder(List.of(k1,k1)));
        rejects(()->new CPlusResources.Key(W,"minecraft:overworld",CPlusResources.Kind.ENTITY,"random-entity"));
        rejects(()->new CPlusResources.Key(W,"minecraft:overworld",CPlusResources.Kind.BLOCK,"not,a,position"));
        rejects(()->new CPlusResources.Key(W,"minecraft:overworld",CPlusResources.Kind.BLOCK,"99999999,64,0"));
        rejects(()->CPlusResources.Key.actor(W,"minecraft:overworld",CPlusResources.Kind.BLOCK,A));
        rejects(()->CPlusResources.Key.block(W,"minecraft:overworld",CPlusResources.Kind.BLOCK,40000000,0,0));
        rejects(()->CPlusResources.acquireOrder(java.util.Collections.nCopies(17,k1)));
        var qt1=new CPlusResources.QueueTicket(A,T,0);
        var qt2=new CPlusResources.QueueTicket(A,X,1);
        yes(qt1.compareTo(qt2)<0);
        rejects(()->new CPlusResources.QueueTicket(A,T,-1));
        rejects(()->qt1.compareTo(new CPlusResources.QueueTicket(W,X,2)));
        yes(CPlusOwnership.authoritativeOwner(CPlusOwnership.Operation.PLAYER_PHYSICS)==CPlusOwnership.Owner.C1_PLAYER);
        yes(CPlusOwnership.authoritativeOwner(CPlusOwnership.Operation.PATH_REQUEST)==CPlusOwnership.Owner.C2_NAVIGATION);
        yes(CPlusOwnership.authoritativeOwner(CPlusOwnership.Operation.ACTION_COORDINATION)==CPlusOwnership.Owner.CPLUS_ACTION);
        yes(CPlusOwnership.authoritativeOwner(CPlusOwnership.Operation.WORLD_VERIFY)==CPlusOwnership.Owner.SERVER_WORLD);
        yes(!CPlusOwnership.directMutationAllowed(CPlusOwnership.Owner.CPLUS_ACTION,CPlusOwnership.Operation.PLAYER_PHYSICS));
        yes(!CPlusOwnership.directMutationAllowed(CPlusOwnership.Owner.C0_BRAIN,CPlusOwnership.Operation.WORLD_MUTATION));
        yes(CPlusOwnership.directMutationAllowed(CPlusOwnership.Owner.C1_PLAYER,CPlusOwnership.Operation.INVENTORY_MUTATION));
        yes(CPlusCommands.proposedPublicCommands().size()==4);
        yes(CPlusCommands.proposed("follow").route()==CPlusCommands.Route.C2_NAVIGATION);
        yes(CPlusCommands.proposed("stop").route()==CPlusCommands.Route.CPLUS_ACTION);
        yes(CPlusCommands.proposed("status").route()==CPlusCommands.Route.STATUS_AGGREGATOR);
        yes(CPlusCommands.proposed("spawn").minimumPermission()==2);
        yes(CPlusCommands.proposed("unknown")==null);
        yes("follow".equals(CPlusCommands.legacyAliasDestination("c2 follow")));
        yes("spawn".equals(CPlusCommands.legacyAliasDestination("c1 spawn")));
        yes(CPlusCommands.debug("action").minimumPermission()==2);
        yes(!CPlusCommands.debug("c1").visibleToPlayers());
        rejects(()->CPlusCommands.debug("qwen"));
        System.out.println("PASS C-F0 pure Java contract checks="+checks);
    }
}
