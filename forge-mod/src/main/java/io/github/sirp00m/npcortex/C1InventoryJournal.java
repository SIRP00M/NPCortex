package io.github.sirp00m.npcortex;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pure Java P1-B idempotency ledger. Receipts are durable alongside the same
 * SavedData inventory snapshot, not a second process or an external inventory.
 * Fail closed at the finite limit: do not evict old IDs and silently allow replay.
 */
final class C1InventoryJournal {
    static final int MAX_RECEIPTS = 512;
    enum Lookup { NEW, REPLAY, CONFLICT, FULL }
    record Receipt(UUID id, String fingerprint, long revision, String detail) {}

    private final Map<UUID, Receipt> receipts = new LinkedHashMap<>();
    private long revision;

    long revision() { return revision; }
    int size() { return receipts.size(); }
    Collection<Receipt> receipts() { return java.util.List.copyOf(receipts.values()); }
    Receipt find(UUID id) { return receipts.get(id); }

    Lookup lookup(UUID id, String fingerprint) {
        if (id == null || fingerprint == null || fingerprint.isBlank()) throw new IllegalArgumentException("invalid transaction");
        Receipt old = receipts.get(id);
        if (old != null) return old.fingerprint().equals(fingerprint) ? Lookup.REPLAY : Lookup.CONFLICT;
        return receipts.size() == MAX_RECEIPTS ? Lookup.FULL : Lookup.NEW;
    }

    Receipt commit(UUID id, String fingerprint, String detail) {
        if (lookup(id, fingerprint) != Lookup.NEW || revision == Long.MAX_VALUE) {
            throw new IllegalStateException("transaction cannot be committed");
        }
        Receipt receipt = new Receipt(id, fingerprint, ++revision, detail);
        receipts.put(id, receipt);
        return receipt;
    }

    void restore(long revision, Collection<Receipt> source) {
        if (revision < 0 || source.size() > MAX_RECEIPTS) throw new IllegalArgumentException("invalid journal revision/size");
        receipts.clear();
        long previous = 0;
        for (Receipt receipt : source) {
            if (receipt == null || receipt.id() == null || receipt.fingerprint() == null
                    || receipt.fingerprint().isBlank() || receipt.revision() <= previous
                    || receipt.revision() > revision || receipts.putIfAbsent(receipt.id(), receipt) != null) {
                throw new IllegalArgumentException("corrupt or duplicate transaction receipt");
            }
            previous = receipt.revision();
        }
        if (revision != previous && (revision != 0 || !source.isEmpty())) {
            throw new IllegalArgumentException("journal head and revision disagree");
        }
        this.revision = revision;
    }
}
