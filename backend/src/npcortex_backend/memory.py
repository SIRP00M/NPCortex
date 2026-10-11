"""SQLite-backed chat and explicit memories, scoped by player + NPC UUID.

World observations are ephemeral; we never silently persist claims from an LLM.
"""
import json
import sqlite3
import threading
from pathlib import Path


class MemoryStore:
    def __init__(self, path: Path):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self._lock = threading.RLock()
        self._db = sqlite3.connect(str(path), check_same_thread=False, timeout=10)
        self._db.execute('PRAGMA journal_mode=WAL')
        self._db.execute('PRAGMA foreign_keys=ON')
        with self._db:
            self._db.executescript('''
              CREATE TABLE IF NOT EXISTS exchanges (
                id INTEGER PRIMARY KEY, player TEXT NOT NULL, npc TEXT NOT NULL,
                character TEXT NOT NULL, user_text TEXT NOT NULL,
                reply_text TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
              );
              CREATE INDEX IF NOT EXISTS idx_exchanges_scope
                ON exchanges(player,npc,id DESC);
              CREATE TABLE IF NOT EXISTS notes (
                id INTEGER PRIMARY KEY, player TEXT NOT NULL, npc TEXT NOT NULL,
                text TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
              );
              CREATE INDEX IF NOT EXISTS idx_notes_scope ON notes(player,npc,id DESC);
              CREATE TABLE IF NOT EXISTS relationships (
                player TEXT NOT NULL, npc TEXT NOT NULL,
                chats INTEGER NOT NULL DEFAULT 0, affinity INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(player,npc)
              );
            ''')

    def history(self, player: str, npc: str, limit: int = 6) -> list[dict[str, str]]:
        with self._lock:
            rows = self._db.execute('''SELECT user_text,reply_text FROM exchanges
                WHERE player=? AND npc=? ORDER BY id DESC LIMIT ?''', (player, npc, min(limit, 12))).fetchall()
        messages = []
        for user_text, reply in reversed(rows):
            messages.extend(({'role': 'user', 'content': user_text},
                             {'role': 'assistant', 'content': reply}))
        return messages

    def record(self, player: str, npc: str, character: str, user_text: str, reply: str) -> None:
        with self._lock, self._db:
            self._db.execute('''INSERT INTO exchanges(player,npc,character,user_text,reply_text)
                VALUES(?,?,?,?,?)''', (player, npc, character, user_text[:2000], reply[:1600]))
            # Neutral deterministic activity score, NOT inferred affection or romance.
            self._db.execute('''INSERT INTO relationships(player,npc,chats,affinity) VALUES(?,?,1,1)
                ON CONFLICT(player,npc) DO UPDATE SET
                chats=chats+1, affinity=MIN(100,affinity+1)''', (player, npc))
            # Keep a bounded transcript per pairing (independent of manual notes).
            self._db.execute('''DELETE FROM exchanges WHERE player=? AND npc=? AND id NOT IN
                (SELECT id FROM exchanges WHERE player=? AND npc=? ORDER BY id DESC LIMIT 200)''',
                (player, npc, player, npc))

    def snapshot(self, player: str, npc: str) -> dict:
        with self._lock:
            rows = self._db.execute('''SELECT id,text,created_at FROM notes
                WHERE player=? AND npc=? ORDER BY id DESC LIMIT 30''', (player, npc)).fetchall()
            relationship = self._db.execute('''SELECT chats,affinity FROM relationships
                WHERE player=? AND npc=?''', (player, npc)).fetchone() or (0, 0)
            recent = self._db.execute('''SELECT user_text,reply_text FROM exchanges
                WHERE player=? AND npc=? ORDER BY id DESC LIMIT 3''', (player, npc)).fetchall()
        return {'notes': [{'id': i, 'text': text, 'created_at': created} for i, text, created in rows],
                'chats': relationship[0], 'affinity': relationship[1],
                'recent': [{'user': u[:140], 'reply': r[:140]} for u, r in recent]}

    def add_note(self, player: str, npc: str, note: str) -> int:
        note = note.strip()
        if not 1 <= len(note) <= 300:
            raise ValueError('Note must contain 1–300 characters')
        with self._lock, self._db:
            count = self._db.execute('SELECT COUNT(*) FROM notes WHERE player=? AND npc=?',
                                     (player, npc)).fetchone()[0]
            if count >= 30:
                raise ValueError('Max 30 saved notes per NPC and player')
            result = self._db.execute('INSERT INTO notes(player,npc,text) VALUES(?,?,?)',
                                      (player, npc, note))
            return result.lastrowid

    def remove_note(self, player: str, npc: str, note_id: int) -> bool:
        with self._lock, self._db:
            return self._db.execute('DELETE FROM notes WHERE player=? AND npc=? AND id=?',
                                    (player, npc, note_id)).rowcount > 0

    def clear_notes(self, player: str, npc: str) -> None:
        with self._lock, self._db:
            self._db.execute('DELETE FROM notes WHERE player=? AND npc=?', (player, npc))

    def clear_history(self, player: str, npc: str) -> None:
        """Remove mistaken dialogue for one player/NPC pair, preserving notes and affinity."""
        with self._lock, self._db:
            self._db.execute('DELETE FROM exchanges WHERE player=? AND npc=?', (player, npc))

    def reset_relationship(self, player: str, npc: str) -> None:
        with self._lock, self._db:
            self._db.execute('DELETE FROM relationships WHERE player=? AND npc=?', (player, npc))

    def notes_for_prompt(self, player: str, npc: str) -> str:
        with self._lock:
            notes = self._db.execute('SELECT text FROM notes WHERE player=? AND npc=? ORDER BY id DESC LIMIT 20',
                                     (player, npc)).fetchall()
        # User-authored notes are data from the PLAYER's perspective. JSON-quote
        # their content so a bare 'My name is Kenji' is never framed as NPC speech.
        return '\n'.join('- PLAYER NOTE: ' + json.dumps(str(text[0]).replace('\n', ' '), ensure_ascii=False)
                         for text in reversed(notes))[:3000]

    def close(self):
        with self._lock:
            self._db.close()
