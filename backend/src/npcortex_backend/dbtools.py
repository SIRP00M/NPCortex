"""SQLite backup/verification helpers for schema upgrades (online backup API, never a raw file copy).

CLI (read-only except `backup`, which only creates a new file):
    python -m npcortex_backend.dbtools check <db>
    python -m npcortex_backend.dbtools backup <db> <dest>
"""
from pathlib import Path
import sqlite3
import sys


class BackupError(RuntimeError):
    pass


def table_counts(db: sqlite3.Connection) -> dict[str, int]:
    names = [r[0] for r in db.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
    return {name: db.execute(f'SELECT COUNT(*) FROM "{name}"').fetchone()[0] for name in names}


def _open_readonly(path: Path) -> sqlite3.Connection:
    # A WAL-mode file needs its -shm sidecar even for reads, so use query_only instead of mode=ro.
    db = sqlite3.connect(str(path))
    db.execute("PRAGMA query_only=ON")
    return db


def manifest(path: Path) -> dict:
    """user_version, integrity result and per-table row counts of a database file (opened read-only)."""
    db = _open_readonly(path)
    try:
        return {"user_version": db.execute("PRAGMA user_version").fetchone()[0],
                "integrity": db.execute("PRAGMA integrity_check").fetchone()[0],
                "counts": table_counts(db)}
    finally:
        db.close()


def backup_database(source: sqlite3.Connection, dest: Path, expected_version: int) -> dict:
    """Online-backup `source` into the NEW file `dest` and verify it; returns the verified manifest.

    Refuses to overwrite. A failed or unverifiable backup is deleted (only the file this call created)
    and raises BackupError so the caller never proceeds to a destructive step.
    """
    dest = Path(dest)
    if dest.exists():
        raise BackupError(f"Backup target already exists: {dest}")
    expected_counts = table_counts(source)
    target = sqlite3.connect(dest)
    try:
        source.backup(target)
    except BaseException:
        target.close()
        dest.unlink(missing_ok=True)
        raise
    target.close()
    try:
        found = manifest(dest)
        if found["integrity"] != "ok":
            raise BackupError("Backup failed integrity_check")
        if found["user_version"] != expected_version:
            raise BackupError(f"Backup user_version {found['user_version']} != {expected_version}")
        if found["counts"] != expected_counts:
            raise BackupError("Backup row counts differ from the source database")
    except BaseException:
        dest.unlink(missing_ok=True)
        raise
    return found


def main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[0] == "check":
        print(manifest(Path(argv[1])))
        return 0
    if len(argv) == 3 and argv[0] == "backup":
        src = _open_readonly(Path(argv[1]))
        try:
            version = src.execute("PRAGMA user_version").fetchone()[0]
            print(backup_database(src, Path(argv[2]), version))
        finally:
            src.close()
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
