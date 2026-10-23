package io.github.sirp00m.npcortex.cplus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/**
 * C-F1 D+ LIVE DIAGNOSTIC BRIDGE (NOT an action executor).
 *
 * Worker serializes all file operations, including force(true), OFF Minecraft's server tick.
 * Only explicitly requested SYNTHETIC intent/cancel probes are recorded. No world effects,
 * C1 inventory changes, C2 movement requests or automatic replay are performed.
 * The ledger path MUST be in the active world's storage directory, never a global config path.
 */
public final class CPlusF1AsyncDiagnostics implements AutoCloseable {
    public record ProbeAck(UUID actionId, long epoch, long durableSequence) {}
    public record Snapshot(String state, int actions, int unresolved, long durableAcks,
                           long rejected, String lastProbe, String error) {}

    private static final String FINGERPRINT = fingerprint("C-F1-D+-synthetic-no-world-effect-v1");
    private final Path file;
    private final UUID world;
    private final ExecutorService worker;
    // HTTP never runs on the durability worker: blocked network cannot stall probe/inspect.
    private final ExecutorService transport;
    private final Map<UUID, Long> epochs = new HashMap<>(); // worker-thread only
    private CPlusDurableLedger ledger; // worker-thread only
    private volatile Snapshot snapshot = new Snapshot("NEW", 0, 0, 0, 0, "none", "");
    private volatile boolean closed;
    public record SyncSnapshot(String state, long checkpoint, int transferred, long pending, String reason) {}
    private volatile SyncSnapshot syncSnapshot = new SyncSnapshot("NOT_SYNCED", 0, 0, 0, "operator opt-in only");
    public SyncSnapshot syncStatus() { return syncSnapshot; }

    private Path cursorPath() {return file.resolveSibling("cplus_f1_delivery.cursor");}
    /** Snapshot only on the journal worker; immutable records handed to bounded network worker. */
    private CompletableFuture<CPlusF1Delivery.Source> source() {
        return enqueue(() -> {
            ensureOpened();
            return new CPlusF1Delivery.Source(ledger.deliverySnapshot());
        });
    }
    /** Durable local status, not a guessed in-memory pending count after restart. */
    public CompletableFuture<SyncSnapshot> deliveryStatus() {
        return source().thenCompose(snapshot -> submitTransport(() -> {
            var view=CPlusF1Delivery.inspect(snapshot,cursorPath());
            SyncSnapshot out=new SyncSnapshot(view.state(),view.checkpoint(),0,view.pending(),view.reason());
            syncSnapshot=out;
            return out;
        }));
    }
    /** Operator-only; manual network delivery, no C1/C2, no automatic world effect replay. */
    public CompletableFuture<SyncSnapshot> sync(String origin, String token) {
        final URI endpoint;
        try { endpoint=CPlusF1Delivery.safeBackend(origin); }
        catch(RuntimeException invalid) {return CompletableFuture.failedFuture(invalid);}
        return source().thenCompose(snapshot -> submitTransport(() -> {
            try {
                var outcome=CPlusF1Delivery.send(snapshot,cursorPath(),endpoint,token,8);
                SyncSnapshot result=new SyncSnapshot(outcome.state(),outcome.acknowledgedSequence(),
                        outcome.newlyAcknowledged(),outcome.remainingEvents(),outcome.reason());
                syncSnapshot=result;
                return result;
            } catch (IOException | RuntimeException failed) {
                var durable=CPlusF1Delivery.inspect(snapshot,cursorPath());
                syncSnapshot=new SyncSnapshot("HOLD",durable.checkpoint(),0,durable.pending(),
                        failed.getClass().getSimpleName()+": "+safeReason(failed));
                throw failed;
            }
        }));
    }
    public CompletableFuture<CPlusF1Delivery.RecoveryAudit> reconcile(String origin, String token) {
        final URI endpoint;
        try { endpoint=CPlusF1Delivery.safeBackend(origin); }
        catch(RuntimeException invalid) {return CompletableFuture.failedFuture(invalid);}
        return source().thenCompose(snapshot -> submitTransport(() ->
                CPlusF1Delivery.reconcile(snapshot,cursorPath(),endpoint,token)));
    }
    private interface TransportCall<T> { T run() throws Exception; }
    private <T> CompletableFuture<T> submitTransport(TransportCall<T> task) {
        CompletableFuture<T> result=new CompletableFuture<>();
        if(closed) {result.completeExceptionally(new IOException("diagnostic service closed"));return result;}
        try {
            transport.execute(() -> {
                try { result.complete(task.run()); }
                catch(Exception problem) {result.completeExceptionally(problem);}
            });
        } catch(RejectedExecutionException rejected) {result.completeExceptionally(rejected);}
        return result;
    }

    public CPlusF1AsyncDiagnostics(Path file, UUID world) {
        this.file = Objects.requireNonNull(file);
        this.world = Objects.requireNonNull(world);
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), r -> {
                    Thread thread = new Thread(r, "npcortex-cf1-ledger-io");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.transport = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8),r -> {
                    Thread thread=new Thread(r,"npcortex-cf1-diagnostic-http");
                    thread.setDaemon(true);
                    return thread;
                },new ThreadPoolExecutor.AbortPolicy());
    }

    /** Explicitly initialize for read-only inspection; actual file opening is off-thread. */
    public CompletableFuture<Snapshot> inspect() {
        return enqueue(() -> {
            ensureOpened();
            return snapshot;
        });
    }

    /** Write synthetic durable checkpoints and cancel BEFORE any side effect (there are none). */
    public CompletableFuture<ProbeAck> probe(UUID actor) {
        Objects.requireNonNull(actor);
        if (actor.equals(new UUID(0, 0))) throw new IllegalArgumentException("nil actor");
        UUID action = UUID.randomUUID(), attempt = UUID.randomUUID(), task = UUID.randomUUID();
        return enqueue(() -> {
            ensureOpened();
            Long epoch = epochs.get(actor);
            if (epoch == null) {
                epoch = ledger.nextEpoch(actor);
                append(new CPlusDurableLedger.Event(ledger.nextSequence(), UUID.randomUUID(), world,
                        actor, task, UUID.randomUUID(), UUID.randomUUID(), null, epoch,
                        CPlusDurableLedger.Stage.FENCE, FINGERPRINT, ""));
                epochs.put(actor, epoch); // only after durable fence ACK
            }
            append(new CPlusDurableLedger.Event(ledger.nextSequence(), UUID.randomUUID(), world,
                    actor, task, action, attempt, null, epoch,
                    CPlusDurableLedger.Stage.INTENT, FINGERPRINT, ""));
            append(new CPlusDurableLedger.Event(ledger.nextSequence(), UUID.randomUUID(), world,
                    actor, task, action, attempt, null, epoch,
                    CPlusDurableLedger.Stage.CANCELLED, FINGERPRINT, "synthetic-no-world-effect"));
            Snapshot before = snapshot;
            snapshot = new Snapshot("READY", ledger.recordedActions(), ledger.unresolvedActions(),
                    before.durableAcks() + 1, before.rejected(), "CANCELLED action=" + action, "");
            return new ProbeAck(action, epoch, ledger.nextSequence() - 1);
        });
    }

    public Snapshot status() { return snapshot; } // zero blocking disk access on server tick

    private void ensureOpened() throws IOException {
        if (ledger != null) return;
        // Directory changes happen only on the dedicated worker, not on Minecraft ticks.
        Files.createDirectories(file.getParent());
        ledger = CPlusDurableLedger.open(file); // refuses corrupt records / concurrent process writer
        if (ledger.boundWorld() != null && !ledger.boundWorld().equals(world)) {
            ledger.close();
            ledger = null;
            throw new IOException("journal bound to a different world; operator investigation required");
        }
        Snapshot old = snapshot;
        snapshot = new Snapshot("READY", ledger.recordedActions(), ledger.unresolvedActions(),
                old.durableAcks(), old.rejected(), old.lastProbe(), "");
    }

    private void append(CPlusDurableLedger.Event event) throws IOException {
        ledger.append(event); // includes force(true) on worker thread; completion follows durable ACK
    }

    private interface IOCall<T> { T run() throws IOException; }
    private <T> CompletableFuture<T> enqueue(IOCall<T> call) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed) {
            result.completeExceptionally(new IllegalStateException("C-F1 diagnostic service stopped"));
            return result;
        }
        try {
            worker.execute(() -> {
                if (snapshot.state().equals("ERROR")) {
                    result.completeExceptionally(new IOException("C-F1 journal quarantined; operator inspection required"));
                    return;
                }
                try {
                    result.complete(call.run());
                } catch (IOException | RuntimeException failure) {
                    Snapshot before = snapshot;
                    snapshot = new Snapshot("ERROR", before.actions(), before.unresolved(), before.durableAcks(),
                            before.rejected() + 1, before.lastProbe(), failure.getClass().getSimpleName()
                                    + ": " + safeReason(failure));
                    // Never auto-truncate/repair or replay any action on an IO failure.
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException rejected) {
            Snapshot before = snapshot;
            snapshot = new Snapshot(before.state(), before.actions(), before.unresolved(), before.durableAcks(),
                    before.rejected() + 1, before.lastProbe(), before.error());
            result.completeExceptionally(rejected);
        }
        return result;
    }

    private static String safeReason(Exception e) {
        String reason = String.valueOf(e.getMessage()).replace('\n', ' ').replace('\r', ' ');
        return reason.substring(0, Math.min(120, reason.length()));
    }
    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    /** Called during server shutdown, NEVER from the normal END-tick callback. */
    @Override public void close() throws IOException {
        if (closed) return;
        closed=true;
        worker.shutdown();
        transport.shutdown();
        IOException failure=null;
        try {
            if (!worker.awaitTermination(3,TimeUnit.SECONDS)) {
                worker.shutdownNow();
                failure=new IOException("journal shutdown timed out; inspect journal after restart");
            }
            if(!transport.awaitTermination(4,TimeUnit.SECONDS)) {
                transport.shutdownNow();
                failure=new IOException("network shutdown timed out; cursor may be uncertain");
            }
        } catch(InterruptedException interrupted) {
            worker.shutdownNow();transport.shutdownNow();
            Thread.currentThread().interrupt();
            failure=new IOException("interrupted diagnostic shutdown; inspect after restart",interrupted);
        }
        // The ledger is only accessed by a fully stopped journal worker.
        if(worker.isTerminated() && ledger!=null) {
            try {ledger.close();ledger=null;}
            catch(IOException e) {if(failure==null)failure=e;else failure.addSuppressed(e);}
        }
        if(failure!=null)throw failure;
    }
}
