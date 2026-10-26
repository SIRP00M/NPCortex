package io.github.sirp00m.npcortex.cplus;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C-F1 D+ opt-in, operator-triggered transport: NO Minecraft action execution.
 * Only call from bounded diagnostic IO worker, never from a game tick.
 * Acknowledgements are checked against event id, sequence and deterministic receipt.
 */
public final class CPlusF1Delivery {
    private CPlusF1Delivery() {}
    public record Outcome(String state, long acknowledgedSequence, int newlyAcknowledged,
                          long remainingEvents, String reason) {}
    private static final Pattern CURSOR = Pattern.compile("([0-9]{1,19}) ([0-9a-f]{64})\\n");
    /** Immutable worker-thread journal snapshot; no world mutation or live ledger access. */
    public record Source(List<CPlusDurableLedger.LedgerRecord> records) {
        public Source { records=List.copyOf(records); }
        public long lastSequence() {return records.size();}
        public UUID worldId() {return records.isEmpty()?null:records.get(0).event().worldId();}
        public int unresolvedActions() {
            java.util.Map<UUID,CPlusDurableLedger.Stage> recent=new java.util.HashMap<>();
            for(var r:records) if(r.event().stage()!=CPlusDurableLedger.Stage.FENCE)
                recent.put(r.event().actionId(),r.event().stage());
            int count=0;
            for(var state:recent.values()) if(state!=CPlusDurableLedger.Stage.CANCELLED
                    && state!=CPlusDurableLedger.Stage.VERIFIED) count++;
            return count;
        }
        public boolean matches(long sequence, String hash) {
            return sequence==0 ? "0".repeat(64).equals(hash) :
                    sequence>0 && sequence<=records.size() &&
                    records.get((int)sequence-1).event().sequence()==sequence &&
                    records.get((int)sequence-1).recordHash().equals(hash);
        }
        public List<CPlusDurableLedger.LedgerRecord> after(long seq, int max) {
            if(seq<0 || seq>records.size() || max<1 || max>16) throw new IllegalArgumentException("invalid bounded snapshot window");
            return records.subList((int)seq, Math.min(records.size(), (int)seq+max));
        }
    }
    public record CursorStatus(String state, long checkpoint, long pending, String reason) {}
    public record RecoveryAudit(String state, long localSequence, long cursorSequence,
                                long backendSequence, String reason) {}
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

    public static URI safeBackend(String raw) {
        URI uri = URI.create(raw);
        String host=uri.getHost();
        if (!"http".equalsIgnoreCase(uri.getScheme()) || host==null
                || !(host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"))
                || uri.getRawUserInfo()!=null || uri.getRawPath()!=null && !uri.getRawPath().isEmpty()
                || uri.getRawQuery()!=null || uri.getRawFragment()!=null)
            throw new IllegalArgumentException("C-F1 transport requires a loopback HTTP origin with no path");
        return uri;
    }
    private static long[] loadCursor(Path cursor, Source ledger) throws IOException {
        if (Files.exists(cursor.resolveSibling(cursor.getFileName()+".tmp")))
            throw new IOException("orphaned delivery .tmp exists; preserve evidence and HOLD");
        if (!Files.exists(cursor)) return new long[]{0};
        byte[] bytes=Files.readAllBytes(cursor);
        if (bytes.length>96) throw new IOException("delivery checkpoint over budget; HOLD");
        Matcher match=CURSOR.matcher(new String(bytes,StandardCharsets.US_ASCII));
        if (!match.matches()) throw new IOException("delivery checkpoint malformed; HOLD");
        long seq;
        try {seq=Long.parseLong(match.group(1));}
        catch(NumberFormatException bad) {throw new IOException("delivery sequence overflow; HOLD",bad);}
        if (!ledger.matches(seq,match.group(2)))
            throw new IOException("delivery cursor disagrees with immutable journal; HOLD");
        return new long[]{seq};
    }
    private static void durableCursor(Path path, CPlusDurableLedger.LedgerRecord record) throws IOException {
        Path temp=path.resolveSibling(path.getFileName()+".tmp");
        byte[] data=(record.event().sequence()+" "+record.recordHash()+"\n").getBytes(StandardCharsets.US_ASCII);
        // Deliberately preserve orphaned temp data on failure/crash for operator audit.
        // Never overwrite or delete a preexisting .tmp without an explicit recovery decision.
        try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            ByteBuffer b=ByteBuffer.wrap(data);
            while(b.hasRemaining()) channel.write(b);
            channel.force(true);
        }
        Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        // The immutable ledger remains the authority. Missing checkpoint after a power
        // failure only causes idempotent redelivery, never a missing journal event.
    }
    private static String responseField(String body, String key) throws IOException {
        String expression="\\\""+Pattern.quote(key)+"\\\"\\s*:\\s*\\\"([^\\\"\\\\]{0,128})\\\"";
        Matcher m=Pattern.compile(expression).matcher(body);
        if (!m.find()) throw new IOException("missing durable ACK field: "+key);
        String result=m.group(1);
        if (m.find()) throw new IOException("duplicate ACK field: "+key);
        return result;
    }
    private static long responseSequence(String body) throws IOException {
        Matcher m=Pattern.compile("\\\"sequence\\\"\\s*:\\s*([0-9]{1,19})").matcher(body);
        if (!m.find()) throw new IOException("missing ACK sequence");
        long result;
        try { result=Long.parseLong(m.group(1)); }
        catch(NumberFormatException e) { throw new IOException("ACK sequence overflow",e); }
        if (m.find()) throw new IOException("duplicate ACK sequence");
        return result;
    }
    private static void confirm(URI base, String token, CPlusDurableLedger.LedgerRecord item) throws IOException {
        var e=item.event();
        String body="{\"world_id\":\""+e.worldId()+"\",\"sequence\":"+e.sequence()
                +",\"event_id\":\""+e.eventId()+"\",\"receipt_id\":\""
                +CPlusF1Wire.expectedReceipt(item)+"\"}";
        String response=CPlusTransport.post(base.resolve("/v1/cplus/f1/confirm"),token,body,3,1024);
        if (!"CONFIRMED".equals(responseField(response,"status")) || responseSequence(response)!=e.sequence())
            throw new IOException("backend did not durably confirm receipt; retry confirmation later");
    }
    /** Compatibility helper for existing pure Java tests, never used by the live network worker. */
    public static Outcome send(CPlusDurableLedger ledger, Path cursor, URI base,
                                String token, int maxEvents) throws IOException {
        return send(new Source(ledger.deliverySnapshot()), cursor, base, token, maxEvents);
    }
    public static CursorStatus inspect(Source source, Path cursor) {
        try {
            long seq=loadCursor(cursor,source)[0];
            return new CursorStatus("READY",seq,source.lastSequence()-seq,"durable local cursor; world-wide journal");
        } catch(IOException problem) {
            return new CursorStatus("HOLD",-1,-1,"durable cursor unsafe: "+problem.getMessage());
        }
    }
    public static Outcome send(Source ledger, Path cursor, URI base,
                                String token, int maxEvents) throws IOException {
        Objects.requireNonNull(ledger);
        if (token==null || token.length()<24 || token.length()>512 || maxEvents<1 || maxEvents>16)
            throw new IOException("C-F1 transport disabled: token length or bounded batch invalid");
        safeBackend(base.toString());
        long sequence=loadCursor(cursor,ledger)[0];
        if (sequence>0) {
            var last=ledger.after(sequence-1,1);
            if(last.size()!=1 || last.get(0).event().sequence()!=sequence)
                throw new IOException("saved ACK cursor has no source event; HOLD");
            confirm(base,token,last.get(0)); // resumes lost confirmation after process restart
        }
        List<CPlusDurableLedger.LedgerRecord> batch=ledger.after(sequence,maxEvents);
        int sent=0;
        for (var item : batch) {
            var event=item.event();
            String body=CPlusF1Wire.json(item);
            if (body.getBytes(StandardCharsets.UTF_8).length>4096)
                throw new IOException("C-F1 wire payload exceeds budget");
            String ack=CPlusTransport.post(base.resolve("/v1/cplus/f1/events"),token,body,3,1024);
            if (!"ACKED".equals(responseField(ack,"status"))
                    || !event.eventId().toString().equals(responseField(ack,"event_id"))
                    || event.sequence()!=responseSequence(ack)
                    || !CPlusF1Wire.expectedReceipt(item).equals(responseField(ack,"receipt_id")))
                throw new IOException("backend ACK inconsistent; HOLD");
            durableCursor(cursor,item); // checkpoint never precedes fully validated durable backend ACK
            sequence=event.sequence(); sent++;
            confirm(base,token,item); // lost confirmation is retried from the saved cursor

        }
        return new Outcome("READY", sequence, sent, ledger.lastSequence()-sequence, "diagnostic sync only");
    }
    /** Read-only server checkpoint comparison. No replay, ACK advancement, or world effects. */
    public static RecoveryAudit reconcile(Source source, Path cursor, URI base, String token) throws IOException {
        if (source.worldId()==null) throw new IOException("empty journal has no world identity; probe first");
        if (token==null || token.length()<24 || token.length()>512) throw new IOException("bridge credential invalid");
        safeBackend(base.toString());
        CursorStatus local=inspect(source,cursor);
        if (!"READY".equals(local.state()))
            return new RecoveryAudit("HOLD",source.lastSequence(),-1,-1,local.reason());
        String body="{\"world_id\":\""+source.worldId()+"\"}";
        String response=CPlusTransport.post(base.resolve("/v1/cplus/f1/status"),token,body,3,4096);
        if(!source.worldId().toString().equals(responseField(response,"world_id")))
            return new RecoveryAudit("HOLD",source.lastSequence(),local.checkpoint(),-1,"backend world identity mismatch");
        long remote=responseSequence(response);
        String hash=responseField(response,"record_hash");
        if(!HEX.matcher(hash).matches() || !source.matches(remote,hash))
            return new RecoveryAudit("HOLD",source.lastSequence(),local.checkpoint(),remote,"backend checkpoint/chain mismatch");
        if(remote<local.checkpoint())
            return new RecoveryAudit("HOLD",source.lastSequence(),local.checkpoint(),remote,"backend behind durably confirmed cursor");
        if(source.unresolvedActions()>0)
            return new RecoveryAudit("IN_DOUBT",source.lastSequence(),local.checkpoint(),remote,
                    "unresolved local actions="+source.unresolvedActions()+"; no world observation or replay");
        if(remote==source.lastSequence() && local.pending()==0)
            return new RecoveryAudit("READY",source.lastSequence(),local.checkpoint(),remote,"transport consistent; world-effects unverified");
        return new RecoveryAudit("PENDING",source.lastSequence(),local.checkpoint(),remote,
                "outstanding journal events or ACK confirmations; no replay allowed");
    }

}
