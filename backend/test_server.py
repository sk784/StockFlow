import io
import json
import sqlite3
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from concurrent.futures import ThreadPoolExecutor

from server import FailurePlan, Handler, InventoryStore


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'inventory.sqlite3'
        self.store = InventoryStore(self.path)

    def apply(self, key, quantity, revision=0, item=1):
        return self.store.apply(item, key, quantity, revision)

    def test_duplicate_key_has_one_effect_and_survives_restart(self):
        self.assertEqual((200, {'quantity': 0, 'revision': 1}), self.apply('op-1', 0))
        restarted = InventoryStore(self.path)
        self.assertEqual((200, {'quantity': 0, 'revision': 1}), restarted.apply(1, 'op-1', 0, 0))
        self.assertEqual(1, restarted.snapshot()['appliedOperations'])

    def test_replayed_old_operation_does_not_overwrite_newer_quantity(self):
        self.apply('old', 2)
        self.apply('new', 9, revision=1)
        self.assertEqual((200, {'quantity': 2, 'revision': 1}), self.apply('old', 2))
        self.assertEqual({'quantity': 9, 'revision': 2}, self.store.quantity(1))

    def test_reused_key_with_changed_payload_returns_key_conflict(self):
        self.apply('same', 2)
        for quantity, revision, item in [(9, 0, 1), (2, 1, 1), (2, 0, 2)]:
            self.assertEqual((409, {'reason': 'idempotency_conflict'}),
                             self.apply('same', quantity, revision, item))
        self.assertEqual(1, self.store.snapshot()['appliedOperations'])

    def test_two_devices_cannot_silently_overwrite_each_other(self):
        self.assertEqual(200, self.apply('device-a', 7)[0])
        self.assertEqual((409, {'reason': 'revision_conflict', 'quantity': 7, 'revision': 1}),
                         self.apply('device-b', 12))
        self.assertEqual({'quantity': 7, 'revision': 1}, self.store.quantity(1))
        self.assertEqual(1, self.store.snapshot()['appliedOperations'])

    def test_keep_local_uses_fresh_revision_and_new_key(self):
        self.apply('device-a', 7)
        self.assertEqual(409, self.apply('device-b', 12)[0])
        current = self.store.quantity(1)
        self.assertEqual((200, {'quantity': 12, 'revision': 2}),
                         self.apply('resolved-b', 12, current['revision']))

    def test_another_change_during_conflict_resolution_causes_another_conflict(self):
        self.apply('device-a', 7)
        current = self.store.quantity(1)
        self.apply('device-c', 15, revision=1)
        self.assertEqual(409, self.apply('resolved-b', 12, current['revision'])[0])
        self.assertEqual({'quantity': 15, 'revision': 2}, self.store.quantity(1))

    def test_invalid_payload_never_changes_data(self):
        for quantity in (-1, 2147483648, 1.5, True, None, '4'):
            self.assertEqual(400, self.apply('bad', quantity)[0])
        for key in (None, '', ' ', 'x' * 129):
            self.assertEqual(400, self.apply(key, 3)[0])
        for revision in (-1, 1.5, True, None, '0', 9223372036854775807):
            self.assertEqual(400, self.apply('bad', 3, revision)[0])
        self.assertEqual(0, self.store.snapshot()['appliedOperations'])

    def test_unknown_item_is_not_created(self):
        self.assertEqual(404, self.apply('unknown', 2, item=99)[0])
        self.assertEqual(5, len(self.store.snapshot()['items']))

    def test_concurrent_duplicate_requests_are_atomic(self):
        with ThreadPoolExecutor(max_workers=4) as executor:
            results = list(executor.map(lambda _: self.apply('concurrent', 7)[0], range(12)))
        self.assertEqual([200] * 12, results)
        self.assertEqual(1, self.store.snapshot()['appliedOperations'])

    def test_concurrent_different_devices_have_only_one_winner(self):
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(lambda key: self.apply(key, 7)[0], ['a', 'b']))
        self.assertEqual([200, 409], sorted(results))
        self.assertEqual(1, self.store.snapshot()['appliedOperations'])

    def test_catalog_contains_tasks_metadata_counts_and_versions(self):
        self.apply('count', 0)
        tasks = self.store.catalog()
        self.assertEqual([1, 2], [task['id'] for task in tasks])
        item = tasks[0]['items'][0]
        self.assertEqual('2000000000015', item['barcode'])
        self.assertEqual((0, 1), (item['countedQuantity'], item['revision']))
        self.assertIsNone(tasks[0]['items'][1]['countedQuantity'])

    def test_legacy_backend_database_migrates_without_losing_counts(self):
        path = Path(self.temp.name) / 'legacy.sqlite3'
        db = sqlite3.connect(path)
        db.executescript('''
            CREATE TABLE items(id INTEGER PRIMARY KEY, quantity INTEGER);
            CREATE TABLE operations(operation_id TEXT PRIMARY KEY, item_id INTEGER NOT NULL, quantity INTEGER NOT NULL);
            INSERT INTO items VALUES (1, 9);
            INSERT INTO operations VALUES ('legacy', 1, 9);
        ''')
        db.close()
        migrated = InventoryStore(path)
        self.assertEqual({'quantity': 9, 'revision': 1}, migrated.quantity(1))
        self.assertEqual((200, {'quantity': 9, 'revision': 1}), migrated.apply(1, 'legacy', 9, 0))
        self.assertEqual(1, migrated.snapshot()['appliedOperations'])

    def request(self, body=None, key='http-op', failures=None, method='PUT', path='/api/items/1/quantity'):
        class Connection:
            def __init__(self, request):
                self.input = io.BytesIO(request)
                self.output = bytearray()
            def makefile(self, *args):
                return self.input
            def sendall(self, data):
                self.output.extend(data)
        payload = b'' if body is None else json.dumps(body).encode()
        request = (f'{method} {path} HTTP/1.1\r\nHost: localhost\r\n'
                   f'Idempotency-Key: {key}\r\nContent-Length: {len(payload)}\r\n'
                   'Content-Type: application/json\r\nConnection: close\r\n\r\n').encode() + payload
        connection = Connection(request)
        server = SimpleNamespace(store=self.store, failures=failures or FailurePlan())
        class QuietHandler(Handler):
            def log_message(self, *args):
                pass
        QuietHandler(connection, ('127.0.0.1', 12345), server)
        return bytes(connection.output)

    def test_http_write_contract_and_explicit_conflict_reason(self):
        self.assertIn(b'200 OK', self.request({'quantity': 5, 'baseRevision': 0}))
        self.assertIn(b'idempotency_conflict', self.request({'quantity': 6, 'baseRevision': 0}))
        self.assertIn(b'revision_conflict', self.request({'quantity': 6, 'baseRevision': 0}, key='device-b'))
        self.assertIn(b'400 Bad Request', self.request({'quantity': 5}))

    def test_http_catalog_and_single_item(self):
        catalog = self.request(method='GET', path='/api/tasks')
        self.assertIn(b'200 OK', catalog)
        self.assertEqual(2, len(json.loads(catalog.split(b'\r\n\r\n')[1])))
        self.assertIn(b'"revision": 0', self.request(method='GET', path='/api/items/1'))
        self.assertIn(b'404 Not Found', self.request(method='GET', path='/api/items/999'))

    def test_http_503_does_not_commit_and_retry_succeeds(self):
        failures = FailurePlan(fail_first=1)
        body = {'quantity': 5, 'baseRevision': 0}
        self.assertIn(b'503 Service Unavailable', self.request(body, failures=failures))
        self.assertEqual(0, self.store.snapshot()['appliedOperations'])
        self.assertIn(b'200 OK', self.request(body, failures=failures))

    def test_http_lost_response_commits_once(self):
        failures = FailurePlan(drop_first_response=True)
        body = {'quantity': 5, 'baseRevision': 0}
        self.assertEqual(b'', self.request(body, failures=failures))
        self.assertEqual(1, self.store.snapshot()['appliedOperations'])
        self.assertIn(b'200 OK', self.request(body, failures=failures))
        self.assertEqual(1, self.store.snapshot()['appliedOperations'])


if __name__ == '__main__':
    unittest.main()
