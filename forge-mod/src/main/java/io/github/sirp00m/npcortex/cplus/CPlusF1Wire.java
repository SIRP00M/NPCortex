package io.github.sirp00m.npcortex.cplus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** No Forge/HTTP dependencies: cross-language v1 immutable durable-ledger envelope. */
public final class CPlusF1Wire {
    private CPlusF1Wire() {}
    private static String esc(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            switch(c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if(c < 0x20) b.append(String.format("\\u%04x", (int)c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }
    private static String uuid(Object uuid) { return esc(uuid==null?"":uuid.toString()); }
    public static String json(CPlusDurableLedger.LedgerRecord record) {
        var e=record.event();
        return "{\"version\":1,\"previous_hash\":"+esc(record.previousHash())
                +",\"record_hash\":"+esc(record.recordHash())+",\"event\":{"
                +"\"sequence\":"+e.sequence()+",\"event_id\":"+uuid(e.eventId())
                +",\"world_id\":"+uuid(e.worldId())+",\"actor_id\":"+uuid(e.actorId())
                +",\"task_id\":"+uuid(e.taskId())+",\"action_id\":"+uuid(e.actionId())
                +",\"attempt_id\":"+uuid(e.attemptId())
                +",\"parent_event_id\":"+(e.parentEventId()==null?"null":uuid(e.parentEventId()))
                +",\"epoch\":"+e.epoch()+",\"stage\":"+esc(e.stage().name())
                +",\"fingerprint\":"+esc(e.fingerprint())
                +",\"evidence_id\":"+esc(e.evidenceId())+"}}";
    }
    public static String expectedReceipt(CPlusDurableLedger.LedgerRecord record) {
        var e=record.event();
        var input=e.eventId()+":"+e.sequence()+":"+record.recordHash();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.US_ASCII)));
        } catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
