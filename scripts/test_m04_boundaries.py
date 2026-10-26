"""Source boundary checks, not Forge gameplay. Run with python scripts/test_m04_boundaries.py."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
java = root / "forge-mod/src/main/java/io/github/sirp00m/npcortex"


def check(value, description):
    assert value, description
    print("PASS", description)


m04 = (java / "CPlusM04Runtime.java").read_text("utf-8")
inv = (java / "C1P1InventoryRuntime.java").read_text("utf-8")
cmd = (java / "C1P1InventoryCommands.java").read_text("utf-8")
main = (java / "NPCortex.java").read_text("utf-8")
policy = (java / "cplus/CPlusF2ReadOnlyPolicy.java").read_text("utf-8")
check("CPlusM04Runtime.commands()" in main, "M04 read-only command wired")
check("C1P1InventoryCommands.unequip()" in main, "C1 unequip command wired")
check('Commands.literal("preview")' in m04, "read-only block preview")
check("getChunkSource().hasChunk" in m04, "no force loading in preflight")
check("permission=UNKNOWN/backend-claim=UNVERIFIED" in m04, "unknown permission not granted")
check('literal("break")' not in m04 and "destroyBlock(" not in m04 and "setBlock(" not in m04,
      "M04 mutation entrypoints absent")
check("inventory.setItem(destinationSlot, equipped)" in inv and
      "inventory.setItem(equippedSlot, ItemStack.EMPTY)" in inv, "unequip conserves equipped stack")
check("Destination storage slot must be empty" in inv, "occupied storage rejected")
check("syncForActiveViewers(avatar);" in inv and
      inv.index("data.commit(avatar.getUUID()") < inv.index("syncForActiveViewers(avatar);"),
      "commit precedes presentation")
check("APPLIED_PENDING_WORLD_SAVE_VIEWER_SYNC_FAILED" in inv,
      "post-commit sync failure must not be reported as rollback")
check("mutation_claim_adapter_unverified" in policy, "F2 unknown default preserved")
print("M04 boundaries static PASS: 11 checks; Forge compile/gameplay PENDING")
