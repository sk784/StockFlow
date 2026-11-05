#!/usr/bin/env python3
"""Check the actual migration SQL on SQLite; this does not replace Room instrumentation tests."""
from pathlib import Path
import re
import sqlite3
import unittest

SOURCE = (Path(__file__).resolve().parents[1] / 'app/src/main/java/dev/stockflow/data/InventoryMigrations.kt').read_text()


def migrate(db, start, end):
    block = re.search(rf'val MIGRATION_{start}_{end} = object.*?override fun migrate.*?\{{(.*?)\n    }}\n}}', SOURCE, re.S)
    if block is None:
        raise AssertionError(f'Migration {start} -> {end} missing')
    statements = re.findall(r'db\.execSQL\("""(.*?)"""\.trimIndent\(\)\)|db\.execSQL\("([^"\n]+)"\)', block.group(1), re.S)
    if not statements:
        raise AssertionError('Migration SQL not found')
    for multiline, single in statements:
        db.execute(multiline or single)


class MigrationTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.executescript('''
            CREATE TABLE tasks(id INTEGER PRIMARY KEY NOT NULL, title TEXT NOT NULL, location TEXT NOT NULL);
            CREATE TABLE items(id INTEGER PRIMARY KEY NOT NULL, taskId INTEGER NOT NULL, name TEXT NOT NULL,
                barcode TEXT NOT NULL, expectedQuantity INTEGER NOT NULL, countedQuantity INTEGER,
                FOREIGN KEY(taskId) REFERENCES tasks(id) ON DELETE CASCADE);
            CREATE INDEX index_items_taskId ON items(taskId);
            INSERT INTO tasks VALUES (1, 'Task', 'Warehouse');
            INSERT INTO items VALUES (1, 1, 'A', '001', 10, 7), (2, 1, 'B', '002', 10, 0), (3, 1, 'C', '003', 10, NULL);
        ''')

    def test_one_to_three_preserves_counts_and_queues_only_counted_items(self):
        migrate(self.db, 1, 2)
        migrate(self.db, 2, 3)
        rows = self.db.execute('SELECT itemId, quantity, baseRevision, conflictRevision, conflictQuantity FROM pending_operations ORDER BY sequence').fetchall()
        self.assertEqual([(1, 7, None, None, None), (2, 0, None, None, None)], rows)
        self.assertEqual([(7, 0), (0, 0), (None, 0)], self.db.execute('SELECT countedQuantity, serverRevision FROM items ORDER BY id').fetchall())
        column = next(row for row in self.db.execute('PRAGMA table_info(items)') if row[1] == 'serverRevision')
        self.assertEqual(('INTEGER', 1, '0'), column[2:5])

    def test_two_to_three_preserves_retries_and_blocking(self):
        migrate(self.db, 1, 2)
        self.db.execute("UPDATE pending_operations SET attempts = 3, blocked = 1, lastError = 'HTTP 403' WHERE itemId = 1")
        before = self.db.execute('SELECT operationId, quantity, attempts, blocked, lastError FROM pending_operations ORDER BY sequence').fetchall()
        migrate(self.db, 2, 3)
        self.assertEqual(before, self.db.execute('SELECT operationId, quantity, attempts, blocked, lastError FROM pending_operations ORDER BY sequence').fetchall())

    def test_unique_operation_ids_remain_enforced(self):
        migrate(self.db, 1, 2)
        migrate(self.db, 2, 3)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('INSERT INTO pending_operations(operationId, itemId, quantity, attempts, blocked) SELECT operationId, 1, 9, 0, 0 FROM pending_operations LIMIT 1')


if __name__ == '__main__':
    unittest.main(verbosity=2)
