#!/usr/bin/env python3
"""Local StockFlow demo API. No third-party dependencies; never use as a production server."""
import argparse
import json
import re
import sqlite3
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from contextlib import contextmanager


class InventoryStore:
    SEED_TASKS = [(1, 'Инвентаризация основного склада', 'Стеллаж A'),
                  (2, 'Проверка приёмки', 'Зона приёмки')]
    SEED_ITEMS = [
        (1, 1, 'Кабель USB-C', '2000000000015', 24),
        (2, 1, 'Зарядное устройство', '2000000000022', 12),
        (3, 1, 'Беспроводная мышь', '2000000000039', 8),
        (4, 2, 'Клавиатура', '2000000000046', 6),
        (5, 2, 'USB-хаб', '2000000000053', 10),
    ]

    def __init__(self, path):
        self.path = str(path)
        with self.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY, title TEXT NOT NULL, location TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS items (
                    id INTEGER PRIMARY KEY, quantity INTEGER, task_id INTEGER NOT NULL,
                    name TEXT NOT NULL, barcode TEXT NOT NULL, expected_quantity INTEGER NOT NULL,
                    revision INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS operations (
                    operation_id TEXT PRIMARY KEY, item_id INTEGER NOT NULL, quantity INTEGER NOT NULL,
                    base_revision INTEGER NOT NULL, applied_revision INTEGER NOT NULL
                );
            ''')
            item_columns = {row[1] for row in db.execute('PRAGMA table_info(items)')}
            for column, declaration in {
                'task_id': 'INTEGER NOT NULL DEFAULT 1', 'name': "TEXT NOT NULL DEFAULT 'Товар'",
                'barcode': "TEXT NOT NULL DEFAULT ''", 'expected_quantity': 'INTEGER NOT NULL DEFAULT 0',
                'revision': 'INTEGER NOT NULL DEFAULT 0',
            }.items():
                if column not in item_columns:
                    db.execute(f'ALTER TABLE items ADD COLUMN {column} {declaration}')
            if 'revision' not in item_columns:
                db.execute('UPDATE items SET revision = 1 WHERE quantity IS NOT NULL')
            op_columns = {row[1] for row in db.execute('PRAGMA table_info(operations)')}
            for column in ('base_revision', 'applied_revision'):
                if column not in op_columns:
                    db.execute(f'ALTER TABLE operations ADD COLUMN {column} INTEGER NOT NULL DEFAULT 0')
            if 'applied_revision' not in op_columns:
                db.execute('UPDATE operations SET applied_revision = 1')
            db.executemany('INSERT OR REPLACE INTO tasks VALUES (?, ?, ?)', self.SEED_TASKS)
            for item_id, task_id, name, barcode, expected in self.SEED_ITEMS:
                db.execute('''INSERT INTO items(id, task_id, name, barcode, expected_quantity)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET
                    task_id=excluded.task_id, name=excluded.name, barcode=excluded.barcode,
                    expected_quantity=excluded.expected_quantity''', (item_id, task_id, name, barcode, expected))

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path)
        try:
            with db:
                yield db
        finally:
            db.close()

    def apply(self, item_id, operation_id, quantity, base_revision):
        if not isinstance(operation_id, str) or not operation_id.strip() or len(operation_id) > 128:
            return 400, None
        if type(quantity) is not int or not 0 <= quantity <= 2147483647:
            return 400, None
        if type(base_revision) is not int or not 0 <= base_revision < 9223372036854775807:
            return 400, None
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            previous = db.execute(
                'SELECT item_id, quantity, base_revision, applied_revision FROM operations WHERE operation_id = ?',
                (operation_id,),
            ).fetchone()
            if previous is not None:
                if previous[:3] != (item_id, quantity, base_revision):
                    return 409, {'reason': 'idempotency_conflict'}
                return 200, {'quantity': quantity, 'revision': previous[3]}
            current = db.execute('SELECT quantity, revision FROM items WHERE id = ?', (item_id,)).fetchone()
            if current is None:
                return 404, None
            if current[1] != base_revision:
                return 409, {'reason': 'revision_conflict', 'quantity': current[0], 'revision': current[1]}
            revision = base_revision + 1
            db.execute('UPDATE items SET quantity = ?, revision = ? WHERE id = ?', (quantity, revision, item_id))
            db.execute('INSERT INTO operations VALUES (?, ?, ?, ?, ?)',
                       (operation_id, item_id, quantity, base_revision, revision))
        return 200, {'quantity': quantity, 'revision': revision}

    def quantity(self, item_id):
        with self.connect() as db:
            row = db.execute('SELECT quantity, revision FROM items WHERE id = ?', (item_id,)).fetchone()
            return None if row is None else {'quantity': row[0], 'revision': row[1]}

    def catalog(self):
        with self.connect() as db:
            tasks = []
            for task_id, title, location in db.execute('SELECT id, title, location FROM tasks ORDER BY id'):
                items = [dict(id=row[0], name=row[1], barcode=row[2], expectedQuantity=row[3],
                              countedQuantity=row[4], revision=row[5])
                         for row in db.execute('SELECT id, name, barcode, expected_quantity, quantity, revision FROM items WHERE task_id = ? ORDER BY id', (task_id,))]
                tasks.append(dict(id=task_id, title=title, location=location, items=items))
            return tasks

    def snapshot(self):
        with self.connect() as db:
            return {
                'items': [{'id': row[0], 'quantity': row[1], 'revision': row[2]}
                          for row in db.execute('SELECT id, quantity, revision FROM items ORDER BY id')],
                'appliedOperations': db.execute('SELECT COUNT(*) FROM operations').fetchone()[0],
            }


class FailurePlan:
    def __init__(self, fail_first=0, drop_first_response=False):
        self.remaining = fail_first
        self.drop = drop_first_response
        self.lock = threading.Lock()

    def should_fail(self):
        with self.lock:
            if self.remaining > 0:
                self.remaining -= 1
                return True
            return False

    def should_drop(self):
        with self.lock:
            drop = self.drop
            self.drop = False
            return drop


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/health':
            self.respond(200, {'status': 'ok'})
        elif self.path == '/api/tasks':
            self.respond(200, self.server.store.catalog())
        elif self.path == '/api/items':
            self.respond(200, self.server.store.snapshot())
        elif re.fullmatch(r'/api/items/[0-9]{1,10}', self.path):
            snapshot = self.server.store.quantity(int(self.path.rsplit('/', 1)[1]))
            self.respond(200 if snapshot is not None else 404, snapshot)
        else:
            self.respond(404)

    def do_PUT(self):
        match = re.fullmatch(r'/api/items/([0-9]{1,10})/quantity', self.path)
        if match is None:
            self.respond(404)
            return
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 1024:
                self.respond(400)
                return
            payload = json.loads(self.rfile.read(length))
            if not isinstance(payload, dict) or set(payload) != {'quantity', 'baseRevision'}:
                self.respond(400)
                return
        except (ValueError, UnicodeDecodeError):
            self.respond(400)
            return
        if self.server.failures.should_fail():
            self.respond(503)
            return
        status, response = self.server.store.apply(
            int(match.group(1)), self.headers.get('Idempotency-Key'), payload['quantity'], payload['baseRevision']
        )
        if status == 200 and self.server.failures.should_drop():
            # Commit succeeded, but the client receives no response and must retry the same key.
            self.close_connection = True
            return
        self.respond(status, response)

    def respond(self, status, payload=None):
        data = b'' if payload is None else json.dumps(payload).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Connection', 'close')
        self.end_headers()
        if data:
            self.wfile.write(data)
        self.close_connection = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8080)
    parser.add_argument('--database', default='stockflow-demo.sqlite3')
    parser.add_argument('--fail-first', type=int, default=0, help='Return HTTP 503 before processing the first N writes')
    parser.add_argument('--drop-first-response', action='store_true', help='Commit the first write, then close without responding')
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.store = InventoryStore(args.database)
    server.failures = FailurePlan(args.fail_first, args.drop_first_response)
    print(f'StockFlow demo API: http://{args.host}:{args.port}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
