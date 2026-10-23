package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.cplus.CPlusF2ReadOnlyPolicy;

public final class CPlusF2ReadOnlyPolicySelfTest {
    private static int checks;
    private static void require(boolean cond) { checks++; if (!cond) throw new AssertionError("check="+checks); }
    public static void main(String[] args) {
        var p=CPlusF2ReadOnlyPolicy.class;
        require(CPlusF2ReadOnlyPolicy.decide(CPlusF2ReadOnlyPolicy.Status.OBSERVED,"observe").verdict()
                ==CPlusF2ReadOnlyPolicy.Verdict.ALLOW);
        for (var op:new String[]{"break_block","place_block","use_block"}) {
            var result=CPlusF2ReadOnlyPolicy.decide(CPlusF2ReadOnlyPolicy.Status.OBSERVED,op);
            require(result.verdict()==CPlusF2ReadOnlyPolicy.Verdict.UNKNOWN);
            require(result.reason().equals("mutation_claim_adapter_unverified"));
        }
        require(CPlusF2ReadOnlyPolicy.decide(CPlusF2ReadOnlyPolicy.Status.UNKNOWN_CHUNK,"break_block")
                .verdict()==CPlusF2ReadOnlyPolicy.Verdict.UNKNOWN);
        require(CPlusF2ReadOnlyPolicy.decide(CPlusF2ReadOnlyPolicy.Status.OUT_OF_BORDER,"break_block")
                .verdict()==CPlusF2ReadOnlyPolicy.Verdict.DENY);
        require(CPlusF2ReadOnlyPolicy.decide(CPlusF2ReadOnlyPolicy.Status.OUT_OF_HEIGHT,"break_block")
                .verdict()==CPlusF2ReadOnlyPolicy.Verdict.DENY);
        require(CPlusF2ReadOnlyPolicy.decide(CPlusF2ReadOnlyPolicy.Status.OBSERVED,"unknown_action")
                .verdict()==CPlusF2ReadOnlyPolicy.Verdict.DENY);
        require(CPlusF2ReadOnlyPolicy.usableEvidence("w","d","a","s","w","d","a","s",100,120,20));
        require(!CPlusF2ReadOnlyPolicy.usableEvidence("w","d","a","s","w","d","a","old",100,120,20));
        require(!CPlusF2ReadOnlyPolicy.usableEvidence("w","d","a","s","w","d","a","s",100,121,20));
        require(!CPlusF2ReadOnlyPolicy.usableEvidence("w","d","a","s","w","d","a","s",100,99,20));
        require(!CPlusF2ReadOnlyPolicy.usableEvidence("w","d","a","s","other","d","a","s",100,120,20));
        System.out.println("PASS C-F2 read-only authorization Java checks="+checks);
    }
}
