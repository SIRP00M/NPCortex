"""Explicit, short-lived SQLite units of work for the localhost backend.

Do not use ``with sqlite3.connect(...)`` as a connection lifecycle: SQLite's
built-in context manager commits/rolls back but does NOT close the connection.
A fresh connection per operation avoids sharing connections across HTTP threads.
No model inference happens inside a database transaction.
"""
from contextlib import contextmanager
from pathlib import Path
import sqlite3


class SQLiteDatabase:
    """Owns connection opening, transaction boundaries and guaranteed closing."""

    def __init__(self, path: Path, timeout: float = 5.0):
        self.path = Path(path)
        self.timeout = timeout

    def _open(self) -> sqlite3.Connection:
        # Autocommit makes BEGIN/COMMIT/ROLLBACK boundaries explicit everywhere.
        db = sqlite3.connect(str(self.path), timeout=self.timeout, isolation_level=None)
        try:
            db.row_factory = sqlite3.Row
            db.execute("PRAGMA foreign_keys=ON")
            db.execute("PRAGMA busy_timeout=5000")
            return db
        except BaseException:
            db.close()
            raise

    @contextmanager
    def transaction(self, *, write: bool = False):
        """Snapshot read or serialized write transaction, always closing the file.

        Callers must not start a second BEGIN, and must not retain rows/cursors
        after the context has exited. A raised exception rolls back all writes.
        """
        db = self._open()
        try:
            if not write:
                db.execute("PRAGMA query_only=ON")
            db.execute("BEGIN IMMEDIATE" if write else "BEGIN")
            try:
                yield db
                db.commit()
            except BaseException:
                if db.in_transaction:
                    db.rollback()
                raise
        finally:
            db.close()

    def read(self):
        return self.transaction(write=False)

    def write(self):
        return self.transaction(write=True)

    @contextmanager
    def maintenance(self):
        """Startup-only connection for WAL pragmas and atomic schema scripts.

        ``executescript`` manages its own transaction in existing v1/v2 schema
        migrations; wrapping it inside another transaction would commit early.
        Any interrupted schema script is rolled back before closing.
        """
        db = self._open()
        try:
            try:
                yield db
            except BaseException:
                if db.in_transaction:
                    db.rollback()
                raise
        finally:
            db.close()
