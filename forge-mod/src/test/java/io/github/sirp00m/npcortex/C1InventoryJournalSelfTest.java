package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Standalone assertions; tests no Forge calls or disk fsync guarantees. */
public final class C1InventoryJournalSelfTest {
    private static int assertions;
    private static void check(boolean yes, String why) {
        assertions++;
        if (!yes) throw new AssertionError(why);
    }
    private static void throwsIllegal(Runnable body, String why) {
        boolean threw = false;
        try { body.run(); } catch (IllegalArgumentException | IllegalStateException expected) { threw = true; }
        check(threw, why);
    }
    public static void main(String[] args) {
        C1InventoryJournal yui = new C1InventoryJournal();
        C1InventoryJournal hina = new C1InventoryJournal();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        check(yui.revision() == 0, "starting revision");
        check(yui.size() == 0, "starting receipts");
        check(yui.lookup(a, "give|minecraft:diamond_sword|1") == C1InventoryJournal.Lookup.NEW, "new id");
        var receipt = yui.commit(a, "give|minecraft:diamond_sword|1", "ok");
        check(receipt.revision() == 1, "first revision");
        check(yui.size() == 1, "one receipt");
        check(yui.lookup(a, "give|minecraft:diamond_sword|1") == C1InventoryJournal.Lookup.REPLAY, "duplicate same op");
        check(yui.lookup(a, "give|minecraft:diamond_sword|2") == C1InventoryJournal.Lookup.CONFLICT, "same id new op");
        check(yui.lookup(b, "clear|0") == C1InventoryJournal.Lookup.NEW, "second op");
        yui.commit(b, "clear|0", "cleared");
        check(yui.revision() == 2, "revision monotonic");
        check(yui.receipts().size() == 2, "receipts accessible");
        check(hina.lookup(a, "give|minecraft:diamond_sword|1") == C1InventoryJournal.Lookup.NEW, "separate actor journal");
        hina.commit(a, "give|minecraft:diamond_sword|1", "hina op");
        check(hina.size() == 1 && yui.size() == 2, "isolation");
        C1InventoryJournal restored = new C1InventoryJournal();
        restored.restore(yui.revision(), yui.receipts());
        check(restored.revision() == 2, "restored revision");
        check(restored.lookup(a, "give|minecraft:diamond_sword|1") == C1InventoryJournal.Lookup.REPLAY, "restart dedupe");
        check(restored.lookup(b, "clear|0") == C1InventoryJournal.Lookup.REPLAY, "restart second receipt");
        check(restored.lookup(a, "equip|1|mainhand") == C1InventoryJournal.Lookup.CONFLICT, "restart conflict");
        throwsIllegal(() -> restored.commit(a, "give|minecraft:diamond_sword|1", "duplicate"), "no duplicate commit");
        throwsIllegal(() -> restored.commit(a, "different", "conflict"), "no conflicting commit");
        throwsIllegal(() -> restored.restore(-1, List.of()), "negative revision rejected");
        throwsIllegal(() -> restored.restore(5, yui.receipts()), "head mismatch rejected");
        throwsIllegal(() -> new C1InventoryJournal().restore(2, List.of(
                new C1InventoryJournal.Receipt(a, "op", 1, ""),
                new C1InventoryJournal.Receipt(a, "op", 2, ""))), "duplicate receipts rejected");
        throwsIllegal(() -> new C1InventoryJournal().restore(2, List.of(
                new C1InventoryJournal.Receipt(a, "op", 2, ""),
                new C1InventoryJournal.Receipt(b, "op", 1, ""))), "unordered revision rejected");
        throwsIllegal(() -> new C1InventoryJournal().restore(1, List.of(
                new C1InventoryJournal.Receipt(a, "", 1, ""))), "empty fingerprint rejected");
        throwsIllegal(() -> yui.lookup(null, "op"), "no null transaction id");
        throwsIllegal(() -> yui.lookup(UUID.randomUUID(), ""), "no empty operation");
        C1InventoryJournal bounded = new C1InventoryJournal();
        for (int i = 0; i < C1InventoryJournal.MAX_RECEIPTS; i++) {
            UUID id = UUID.nameUUIDFromBytes(("tx" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            check(bounded.lookup(id, "set|0") == C1InventoryJournal.Lookup.NEW, "capacity before commit");
            bounded.commit(id, "set|0", "ok");
        }
        check(bounded.size() == C1InventoryJournal.MAX_RECEIPTS, "capacity reached");
        check(bounded.lookup(UUID.randomUUID(), "other") == C1InventoryJournal.Lookup.FULL, "fail closed when full");
        UUID first = UUID.nameUUIDFromBytes("tx0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        check(bounded.lookup(first, "set|0") == C1InventoryJournal.Lookup.REPLAY, "old UUID still dedupes");
        throwsIllegal(() -> bounded.commit(UUID.randomUUID(), "other", "bad"), "no evict on full");
        C1InventoryJournal restoredFull = new C1InventoryJournal();
        restoredFull.restore(bounded.revision(), bounded.receipts());
        check(restoredFull.lookup(first, "set|0") == C1InventoryJournal.Lookup.REPLAY, "full journal round trip");
        check(restoredFull.lookup(UUID.randomUUID(), "new") == C1InventoryJournal.Lookup.FULL, "limit survives restart");
        check(yui.revision() == 2, "other actor capacity has no effect");
        System.out.println("C1 Inventory Journal PASS: " + assertions + " assertions (includes 512 capacity iterations)");
    }
}
