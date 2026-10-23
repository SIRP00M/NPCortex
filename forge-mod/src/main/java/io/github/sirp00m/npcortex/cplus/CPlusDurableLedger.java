package io.github.sirp00m.npcortex.cplus;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * C-F1 D+ prototype: fail-closed, fsync-acknowledged, append-only, hash-chained audit ledger.
 * Pure Java, synthetic/read-only actions ONLY. Not wired into C1, C2 or the Minecraft server.
 * The caller MUST run this on a separate IO thread if ever integrated: force(true) blocks.
 * Durable journal ACK is NOT a Minecraft-world/SQLite atomic-commit guarantee.
 */
public final class CPlusDurableLedger implements AutoCloseable {
    public static final int FORMAT_VERSION = 1;
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_FRAME = 2048;
    private static final byte[] ZERO = new byte[32];
    public enum Stage { FENCE, INTENT, EXECUTION_STARTED, OBSERVED_PARTIAL,
        OBSERVED_EXTERNAL, IN_DOUBT, RECONCILED, VERIFIED, CANCELLED }
    public enum Recovery { PREPARED, IN_DOUBT, VERIFIED, CANCELLED }

    /** Event links are future E/Saga-ready. Parent event is a hint, not proof of causality. */
    public record Event(long sequence, UUID eventId, UUID worldId, UUID actorId,
                        UUID taskId, UUID actionId, UUID attemptId, UUID parentEventId,
                        long epoch, Stage stage, String fingerprint, String evidenceId) {
        public Event {
            if (sequence < 1 || epoch < 1 || stage == null || fingerprint == null
                    || !fingerprint.matches("[0-9a-f]{64}")
                    || evidenceId == null || evidenceId.length() > 160)
                throw new IllegalArgumentException("invalid C-F1 event");
            for (UUID id : new UUID[]{eventId, worldId, actorId, taskId, actionId, attemptId})
                if (id == null || id.equals(new UUID(0,0))) throw new IllegalArgumentException("nil/missing identity");
            if (actionId.equals(attemptId)) throw new IllegalArgumentException("attempt identity conflict");
            if (parentEventId != null && parentEventId.equals(new UUID(0,0)))
                throw new IllegalArgumentException("nil parent event id");
            if ((stage == Stage.VERIFIED || stage == Stage.CANCELLED || stage == Stage.OBSERVED_EXTERNAL
                    || stage == Stage.OBSERVED_PARTIAL || stage == Stage.RECONCILED) && evidenceId.isBlank())
                throw new IllegalArgumentException("effect/terminal stage requires independent evidence reference");
            if (stage == Stage.FENCE && !evidenceId.isBlank())
                throw new IllegalArgumentException("fence cannot masquerade as an effect receipt");
        }
    }
    /** Immutable wire-ready records mirror a VERIFIED local hash chain, never mutable world actions. */
    public record LedgerRecord(Event event, String previousHash, String recordHash) {}
    private final List<LedgerRecord> exportRecords = new ArrayList<>();
    private final FileChannel channel;
    private final FileLock lock;
    private final Map<UUID,Event> eventIds = new HashMap<>();
    private final Map<UUID,Event> lastByAction = new HashMap<>();
    private final Map<UUID,Long> actorEpoch = new HashMap<>();
    private final Set<UUID> fencedThisInstance = new HashSet<>();
    private UUID journalWorld;
    private long sequence;
    private byte[] chainHash = ZERO.clone();
    private boolean poisoned;
    private boolean closed;

    private CPlusDurableLedger(FileChannel channel, FileLock lock) {
        this.channel = channel; this.lock = lock;
    }
    public static CPlusDurableLedger open(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        // No mkdir/delete/truncate on malformed data; caller owns directory and its ACL.
        FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        FileLock fileLock = null;
        try {
            fileLock = ch.tryLock();
            if (fileLock == null) throw new IOException("journal owned by another process");
            CPlusDurableLedger ledger = new CPlusDurableLedger(ch, fileLock);
            ledger.load();
            return ledger;
        } catch (IOException | RuntimeException ex) {
            if (fileLock != null) fileLock.release();
            ch.close();
            throw ex;
        }
    }
    private static byte[] hash(byte[] prev, byte[] payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prev); md.update(payload); return md.digest();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static byte[] encode(Event e) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(FORMAT_VERSION); out.writeLong(e.sequence());
        for (UUID id : new UUID[]{e.eventId(),e.worldId(),e.actorId(),e.taskId(),e.actionId(),e.attemptId()})
            out.writeUTF(id.toString());
        out.writeUTF(e.parentEventId()==null?"":e.parentEventId().toString());
        out.writeLong(e.epoch()); out.writeUTF(e.stage().name());
        out.writeUTF(e.fingerprint()); out.writeUTF(e.evidenceId()); out.flush();
        return bytes.toByteArray();
    }
    private static Event decode(byte[] payload) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            if (in.readInt() != FORMAT_VERSION) throw new IOException("unknown journal record version");
            long seq=in.readLong();
            UUID event=UUID.fromString(in.readUTF()),world=UUID.fromString(in.readUTF()),
                    actor=UUID.fromString(in.readUTF()), task=UUID.fromString(in.readUTF()),
                    action=UUID.fromString(in.readUTF()), attempt=UUID.fromString(in.readUTF());
            String parentText=in.readUTF();
            UUID parent=parentText.isEmpty()?null:UUID.fromString(parentText);
            long epoch=in.readLong(); Stage stage=Stage.valueOf(in.readUTF());
            String fp=in.readUTF(), evidence=in.readUTF();
            if (in.available()!=0) throw new IOException("unexpected journal record suffix");
            return new Event(seq,event,world,actor,task,action,attempt,parent,epoch,stage,fp,evidence);
        } catch (RuntimeException ex) { throw new IOException("invalid journal record", ex); }
    }
    private void load() throws IOException {
        long size=channel.size();
        if (size > MAX_BYTES) throw new IOException("journal exceeds bounded load size; quarantine required");
        ByteBuffer buffer=ByteBuffer.allocate((int)size);
        channel.position(0);
        while (buffer.hasRemaining()) if (channel.read(buffer)<0) throw new IOException("unexpected EOF");
        buffer.flip();
        while (buffer.hasRemaining()) {
            if (buffer.remaining()<4) throw new IOException("truncated journal frame header: quarantine required");
            int length=buffer.getInt();
            if (length<1 || length>MAX_FRAME || buffer.remaining()<32+length+32)
                throw new IOException("truncated or invalid journal frame: quarantine required");
            byte[] prev=new byte[32],payload=new byte[length],digest=new byte[32];
            buffer.get(prev);buffer.get(payload);buffer.get(digest);
            if (!MessageDigest.isEqual(prev,chainHash) || !MessageDigest.isEqual(digest,hash(prev,payload)))
                throw new IOException("journal checksum/chain mismatch: quarantine required");
            Event event=decode(payload);
            validateNext(event);
            accept(event,digest);
        }
        channel.position(size);
    }
    private void validateNext(Event event) throws IOException {
        if (event.sequence()!=sequence+1) throw new IOException("journal sequence discontinuity");
        if (journalWorld != null && !journalWorld.equals(event.worldId()))
            throw new IOException("journal world identity mismatch: quarantine required");
        if (eventIds.containsKey(event.eventId())) throw new IOException("reused immutable event ID");
        Long epoch=actorEpoch.get(event.actorId());
        if (event.stage()==Stage.FENCE) {
            if (epoch!=null && event.epoch()<=epoch) throw new IOException("stale session fence");
            return;
        }
        if (epoch==null || epoch!=event.epoch()) throw new IOException("missing/stale session fence");
        Event previous=lastByAction.get(event.actionId());
        if (event.stage()==Stage.INTENT) {
            if (previous!=null) throw new IOException("action ID reuse; cannot create another attempt implicitly");
            return;
        }
        if (previous==null || !previous.worldId().equals(event.worldId())
                || !previous.actorId().equals(event.actorId()) || !previous.taskId().equals(event.taskId())
                || !previous.attemptId().equals(event.attemptId())
                || !previous.fingerprint().equals(event.fingerprint()))
            throw new IOException("effect without matching durable intent or conflicting fingerprint/owner");
        if (previous.stage()==Stage.VERIFIED || previous.stage()==Stage.CANCELLED)
            throw new IOException("cannot mutate terminal action history");
        if (event.stage()==Stage.EXECUTION_STARTED &&
                (previous.stage()!=Stage.INTENT || previous.epoch()!=event.epoch()))
            throw new IOException("execution must follow current-session intent and cannot be restarted");
        if (event.stage()==Stage.CANCELLED && previous.stage()!=Stage.INTENT)
            throw new IOException("cancellation after possible effects needs explicit reconciliation, not undo");
        if (event.stage()==Stage.RECONCILED && previous.stage()!=Stage.IN_DOUBT
                && previous.stage()!=Stage.OBSERVED_PARTIAL && previous.stage()!=Stage.OBSERVED_EXTERNAL)
            throw new IOException("reconciliation requires a prior uncertain effect");
        if (event.stage()==Stage.VERIFIED && previous.stage()!=Stage.OBSERVED_PARTIAL
                && previous.stage()!=Stage.OBSERVED_EXTERNAL && previous.stage()!=Stage.RECONCILED)
            throw new IOException("completion requires effect observation or trusted reconciliation");
    }
    private void accept(Event event, byte[] digest) {
        exportRecords.add(new LedgerRecord(event, HexFormat.of().formatHex(chainHash),
                HexFormat.of().formatHex(digest)));
        sequence=event.sequence();chainHash=digest;
        if (journalWorld == null) journalWorld = event.worldId();
        eventIds.put(event.eventId(),event);
        actorEpoch.merge(event.actorId(),event.epoch(),Long::max);
        if (event.stage()!=Stage.FENCE) lastByAction.put(event.actionId(),event);
    }
    public synchronized void append(Event event) throws IOException {
        if (closed || poisoned) throw new IOException("journal closed or unsafe after IO error");
        if (event.stage()!=Stage.FENCE && !fencedThisInstance.contains(event.actorId()))
            throw new IOException("restart requires a new actor session fence before any append");
        validateNext(event);
        byte[] payload=encode(event);
        if (payload.length>MAX_FRAME) throw new IOException("journal record too large");
        long newSize=channel.size()+4L+32+payload.length+32;
        if (newSize>MAX_BYTES) throw new IOException("journal capacity reached; fail closed");
        byte[] digest=hash(chainHash,payload);
        ByteBuffer frame=ByteBuffer.allocate(4+32+payload.length+32);
        frame.putInt(payload.length).put(chainHash).put(payload).put(digest).flip();
        try {
            channel.position(channel.size());
            while(frame.hasRemaining()) channel.write(frame);
            channel.force(true); // explicit synthetic durability barrier, NEVER call this on server tick
        } catch(IOException failure) {
            poisoned=true; // do not append or hand out any ACK after ambiguous partial write
            throw failure;
        }
        accept(event,digest); // an ACK is meaningful only after force() completed
        if (event.stage()==Stage.FENCE) fencedThisInstance.add(event.actorId());
    }
    public synchronized long nextSequence() { return sequence+1; }
    /** A bounded snapshot is safe to deliver off-tick, even if there are no unacked events. */
    public synchronized List<LedgerRecord> recordsAfter(long after, int limit) {
        if (after < 0 || limit < 1 || limit > 32) throw new IllegalArgumentException("bounded delivery required");
        List<LedgerRecord> result = new ArrayList<>();
        for (LedgerRecord r : exportRecords) {
            if (r.event().sequence() > after) result.add(r);
            if (result.size() == limit) break;
        }
        return List.copyOf(result);
    }
    /** Capture immutable delivery metadata off-tick; network IO never owns the ledger worker. */
    public synchronized List<LedgerRecord> deliverySnapshot() {
        if (closed || poisoned) throw new IllegalStateException("unsafe journal snapshot");
        return List.copyOf(exportRecords);
    }
    /** Validate a persisted delivery cursor against immutable local history before trusting it. */
    public synchronized boolean matchesCheckpoint(long checkpoint, String hash) {
        if (checkpoint == 0) return "0".repeat(64).equals(hash);
        return checkpoint > 0 && checkpoint <= exportRecords.size()
                && exportRecords.get((int)checkpoint - 1).recordHash().equals(hash);
    }
    public synchronized Recovery recovery(UUID actionId) {
        Event last=lastByAction.get(actionId);
        if (last==null) return null;
        return switch(last.stage()) {
            case VERIFIED -> Recovery.VERIFIED;
            case CANCELLED -> Recovery.CANCELLED;
            case INTENT -> Recovery.PREPARED;
            default -> Recovery.IN_DOUBT; // EXECUTING/PARTIAL/EXTERNAL/uncertain all need reconciliation
        };
    }
    /** An existing ledger is forever bound to one world, including on reopen. */
    public synchronized UUID boundWorld() { return journalWorld; }
    /** Read-only journal metadata, safe for a synthetic async diagnostic bridge. */
    public synchronized long nextEpoch(UUID actor) {
        Objects.requireNonNull(actor, "actor");
        long prior = actorEpoch.getOrDefault(actor, 0L);
        if (prior == Long.MAX_VALUE) throw new IllegalStateException("actor epoch exhausted");
        return prior + 1;
    }
    public synchronized int unresolvedActions() {
        int count = 0;
        for (Event e : lastByAction.values())
            if (recovery(e.actionId()) != Recovery.VERIFIED && recovery(e.actionId()) != Recovery.CANCELLED) count++;
        return count;
    }
    public synchronized int recordedActions() { return lastByAction.size(); }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed=true;
        try { lock.release(); } finally { channel.close(); }
    }
}
