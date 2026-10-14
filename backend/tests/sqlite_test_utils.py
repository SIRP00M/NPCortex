"""Windows-safe SQLite fixture: transaction AND connection lifecycle are explicit."""
from contextlib import closing, contextmanager
import sqlite3


@contextmanager
def sqlite_test_connection(path):
    # sqlite3.Connection.__exit__ commits/rolls back, but does not close.
    with closing(sqlite3.connect(path)) as db:
        with db:
            yield db
