"""G12 (static part): no class that a DEDICATED SERVER loads may reference Minecraft client classes.

Reads compiled classes in forge-mod/build/classes/java/main with `javap -v` (read-only). A class counts as
client-only when it carries @Mod.EventBusSubscriber(value = Dist.CLIENT) or is one of the screen classes that
only such a class (or another client class) reaches. Usage: python scripts/check-dist-separation.py
Exit code 0 = separation holds. This is NOT a dedicated-server run; that remains an Owner gameplay check.
"""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CLASSES = ROOT / "forge-mod" / "build" / "classes" / "java" / "main" / "io" / "github" / "sirp00m" / "npcortex"
CLIENT_MARKERS = ("net/minecraft/client/", "com/mojang/blaze3d/", "net/minecraftforge/client/")
# Classes allowed to touch client APIs: they are only reached from Dist.CLIENT subscribers / DistExecutor lambdas.
CLIENT_CLASSES = {"ManagerScreen", "ProfileScreen", "PauseMenuHook", "C1P0ClientSync", "C1ClientRotationBlend"}


def referenced(class_file: Path) -> set[str]:
    out = subprocess.run(["javap", "-v", "-p", str(class_file)], capture_output=True, text=True, check=True).stdout
    names = set(re.findall(r"= Class\s+#\d+\s+//\s+(\S+)", out))
    names |= set(re.findall(r"L(net/minecraft/[A-Za-z0-9_/$]+|com/mojang/blaze3d/[A-Za-z0-9_/$]+|net/minecraftforge/client/[A-Za-z0-9_/$]+);", out))
    return names


def main() -> int:
    if not CLASSES.is_dir():
        print("build the mod first: forge-mod/gradlew compileJava", file=sys.stderr)
        return 2
    problems = []
    for class_file in sorted(CLASSES.glob("*.class")):
        simple = class_file.stem.split("$")[0]
        names = referenced(class_file)
        touches_client = sorted(n for n in names if n.startswith(CLIENT_MARKERS))
        reaches_client_class = sorted(n for n in names if n.rsplit("/", 1)[-1].split("$")[0] in CLIENT_CLASSES
                                      and n.startswith("io/github/sirp00m/npcortex/"))
        if simple in CLIENT_CLASSES:
            continue
        if touches_client:
            problems.append(f"{class_file.stem} (server-loadable) references client API: {touches_client[:3]}")
        if reaches_client_class and simple not in {"ManagerNetwork", "C1P0Network"}:
            problems.append(f"{class_file.stem} references client-only class {reaches_client_class}")
    # The two network classes may only name a client class inside DistExecutor lambdas (never loaded on a server).
    for name, allowed in (("ManagerNetwork", {"ManagerClient"}), ("C1P0Network", {"C1P0ClientSync"})):
        refs = {n.rsplit("/", 1)[-1] for n in referenced(CLASSES / f"{name}.class")}
        extra = {r for r in refs if r.split("$")[0] in CLIENT_CLASSES} - allowed
        if extra:
            problems.append(f"{name} references screen classes directly: {sorted(extra)}")
    if problems:
        print("FAIL dist separation")
        for p in problems:
            print(" -", p)
        return 1
    print("PASS dist separation: no server-loadable class references a client class "
          f"({len(list(CLASSES.glob('*.class')))} classes inspected; NOT a dedicated-server run)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
