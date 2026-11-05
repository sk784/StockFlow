#!/usr/bin/env python3
"""Black-box smoke test against a disposable emulator and an isolated local demo server."""
import argparse
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'backend'))
from server import FailurePlan, Handler, InventoryStore, ThreadingHTTPServer

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--reset-app', action='store_true', help='Required: clears dev.stockflow data on the selected disposable emulator')
parser.add_argument('--drop-first-response', action='store_true')
parser.add_argument('--output', type=Path, default=ROOT / 'build/smoke')
args = parser.parse_args()
if not args.reset_app or not args.serial.startswith('emulator-'):
    parser.error('Use a disposable emulator and explicitly pass --reset-app; physical devices are not supported.')
sdk = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
adb = [str(sdk / 'platform-tools/adb'), '-s', args.serial]
args.output.mkdir(parents=True, exist_ok=True)
(args.output / 'status.txt').write_text('RUNNING\n')

def command(*parts, binary=False):
    return subprocess.check_output([*adb, *parts], timeout=25, text=not binary)

def shell(*parts):
    return command('shell', *parts)

def screen():
    for _ in range(3):
        result = shell('uiautomator', 'dump', '/sdcard/stockflow-smoke.xml')
        if 'dumped to' in result.lower():
            data = command('exec-out', 'cat', '/sdcard/stockflow-smoke.xml')
            root = ET.fromstring(data)
            (args.output / 'latest-ui.xml').write_text(data)
            return root
        time.sleep(0.5)
    raise RuntimeError('Accessibility root is not ready')

def texts(tree):
    return '\n'.join(node.get('text', '') + ' ' + node.get('content-desc', '') for node in tree.iter('node'))

def wait(check, label, timeout=90):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except (subprocess.SubprocessError, ET.ParseError, RuntimeError) as error:
            last_error = error
        time.sleep(1)
    raise AssertionError(f'Timed out: {label}; last error: {last_error}')

def wait_text(text, timeout=60):
    return wait(lambda: text in texts(screen()), text, timeout)

def tap_node(node):
    coordinates = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    assert len(coordinates) == 4, node.attrib
    shell('input', 'tap', str((coordinates[0] + coordinates[2]) // 2), str((coordinates[1] + coordinates[3]) // 2))

def find_node(text, scroll=False):
    attempts = 0
    def locate():
        nonlocal attempts
        attempts += 1
        tree = screen()
        nodes = list(tree.iter('node'))
        parents = {child: parent for parent in tree.iter() for child in parent}
        exact = [node for node in nodes if node.get('text') == text or node.get('content-desc') == text]
        matches = exact or [node for node in nodes if text in node.get('text', '') or text in node.get('content-desc', '')]
        def clickable(node):
            current = node
            while current is not None:
                if current.get('clickable') == 'true' and current.get('enabled') != 'false':
                    return True
                current = parents.get(current)
            return False
        if matches:
            return [next((node for node in matches if clickable(node)), matches[0])]
        if scroll and attempts % 3 == 0:
            shell('input', 'swipe', '360', '1080', '360', '500', '250')
        return None
    return wait(locate, f'find {text}', 40)[0]

def click(text):
    tap_node(find_node(text, scroll=True))

def ensure_inventory():
    click('Задания')
    if 'Сканировать товар' not in texts(screen()):
        click('Инвентаризация основного склада')
    wait_text('Сканировать товар')
    top()

def enter(value):
    node = next(node for node in screen().iter('node') if node.get('class') == 'android.widget.EditText')
    tap_node(node)
    shell('input', 'keyevent', 'KEYCODE_MOVE_END')
    # All values used by this smoke test are ASCII and short.
    for _ in range(len(node.get('text', ''))):
        shell('input', 'keyevent', 'KEYCODE_DEL')
    shell('input', 'text', value)
    shell('input', 'keyevent', 'KEYCODE_BACK')

def top():
    shell('input', 'swipe', '360', '600', '360', '1400', '250')
    shell('input', 'swipe', '360', '600', '360', '1400', '250')

def screenshot(name):
    (args.output / f'{name}.png').write_bytes(command('exec-out', 'screencap', '-p', binary=True))
    print(f'Captured {name}', flush=True)

def edit(name, quantity):
    top()
    click(name)
    wait_text('Фактическое количество')
    enter(str(quantity))
    click('Сохранить')
    wait(lambda: 'Фактическое количество' not in texts(screen()), 'quantity dialog closed')
    top()

def offline(enabled):
    shell('cmd', 'connectivity', 'airplane-mode', 'enable' if enabled else 'disable')
    shell('svc', 'wifi', 'disable' if enabled else 'enable')
    shell('svc', 'data', 'disable' if enabled else 'enable')
    time.sleep(2)

def remote(quantity):
    current = json.load(urllib.request.urlopen('http://127.0.0.1:8080/api/items/1', timeout=5))
    request = urllib.request.Request('http://127.0.0.1:8080/api/items/1/quantity',
        json.dumps({'quantity': quantity, 'baseRevision': current['revision']}).encode(),
        {'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())}, method='PUT')
    return json.load(urllib.request.urlopen(request, timeout=5))

class QuietHandler(Handler):
    def log_message(self, *arguments):
        pass

previous_airplane = shell('settings', 'get', 'global', 'airplane_mode_on').strip()
previous_wifi = shell('settings', 'get', 'global', 'wifi_on').strip()
previous_data = shell('settings', 'get', 'global', 'mobile_data').strip()
with tempfile.TemporaryDirectory(prefix='stockflow-smoke-') as temp:
    server = ThreadingHTTPServer(('127.0.0.1', 8080), QuietHandler)
    server.store = InventoryStore(Path(temp) / 'demo.sqlite3')
    server.failures = FailurePlan(drop_first_response=args.drop_first_response)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        command('install', '-r', str(ROOT / 'app/build/outputs/apk/debug/app-debug.apk'))
        shell('pm', 'clear', 'dev.stockflow')
        shell('pm', 'revoke', 'dev.stockflow', 'android.permission.CAMERA')
        offline(False)
        shell('am', 'start', '-W', '-n', 'dev.stockflow/.MainActivity')
        click('Загрузить задания')
        wait_text('Инвентаризация основного склада')
        time.sleep(4)
        screenshot('01-tasks')
        click('Инвентаризация основного склада')
        wait_text('Сканировать товар')
        screenshot('02-inventory')
        edit('Кабель USB-C', 0)
        wait(lambda: server.store.quantity(1)['quantity'] == 0, 'first upload')
        wait_text('Нет изменений к отправке', 120)
        assert server.store.snapshot()['appliedOperations'] == 1
        print('PASS: zero and upload' + (' with lost-response recovery' if args.drop_first_response else ''), flush=True)

        click('Сканировать товар')
        wait_text('Найти товар по штрихкоду')
        screenshot('03-manual-barcode')
        enter('2000000000015')
        click('Найти')
        wait_text('Фактическое количество')
        click('Отмена')
        print('PASS: manual barcode lookup', flush=True)

        offline(True)
        edit('Зарядное устройство', 12)
        assert server.store.quantity(2)['quantity'] is None
        shell('am', 'force-stop', 'dev.stockflow')
        shell('am', 'start', '-W', '-n', 'dev.stockflow/.MainActivity')
        ensure_inventory()
        find_node('Факт: 12', scroll=True)
        screenshot('04-offline-after-restart')
        click('Отправка')
        find_node('Зарядное устройство', scroll=True)
        screenshot('04-sync-offline')
        ensure_inventory()
        offline(False)
        wait(lambda: server.store.quantity(2)['quantity'] == 12, 'upload after offline restart')
        wait_text('Нет изменений к отправке')
        print('PASS: offline save, process restart and background recovery', flush=True)

        for local, external, choice, expected in [(12, 7, 'Оставить моё', 12), (19, 9, 'Принять серверное', 9)]:
            offline(True)
            edit('Кабель USB-C', local)
            remote(external)
            offline(False)
            click('Конфликты')
            wait_text('Разрешить конфликт')
            screenshot('05-conflict' if expected == 12 else '06-conflict-use-server')
            click('Разрешить конфликт')
            screenshot('05-conflict-details' if expected == 12 else '06-conflict-details')
            click(choice)
            wait(lambda: 'Разрешение конфликта' not in texts(screen()), 'conflict details closed')
            ensure_inventory()
            wait_text('Нет изменений к отправке')
            wait(lambda: server.store.quantity(1)['quantity'] == expected, f'conflict resolution: {choice}')
            top()
            find_node(f'Факт: {expected}', scroll=True)
            assert server.store.quantity(2)['quantity'] == 12
            print(f'PASS: conflict resolution: {choice}', flush=True)
        screenshot('07-resolved')

        click('Сканировать товар')
        click('Разрешить камеру')
        click('While using the app')
        time.sleep(5)
        camera_ui = texts(screen())
        assert not any(message in camera_ui for message in (
            'Камера недоступна', 'Сканер недоступен', 'Не удалось открыть камеру', 'Не удалось распознать код',
        )), camera_ui
        camera_dump = shell('dumpsys', 'media.camera')
        (args.output / 'camera-active.txt').write_text(camera_dump)
        assert 'dev.stockflow' in camera_dump.split('== Camera service events log')[0]
        screenshot('08-camera-preview')
        click('Закрыть')
        wait(lambda: 'dev.stockflow' not in shell('dumpsys', 'media.camera').split('== Camera service events log')[0], 'camera released')
        print('PASS: camera permission and preview lifecycle', flush=True)
        ensure_inventory()
        shell('input', 'keyevent', 'KEYCODE_BACK')
        wait_text('STOCKFLOW')
        screenshot('09-task-progress')
        (args.output / 'server-final.json').write_text(json.dumps(server.store.snapshot(), indent=2))
        with server.store.connect() as source:
            destination = sqlite3.connect(args.output / 'backend.sqlite3')
            try:
                source.backup(destination)
            finally:
                destination.close()
        (args.output / 'status.txt').write_text('PASS\n')
    except BaseException:
        (args.output / 'status.txt').write_text('FAIL\n')
        screenshot('failure')
        (args.output / 'logcat.txt').write_text(command('logcat', '-d', '-t', '1000'))
        raise
    finally:
        shell('cmd', 'connectivity', 'airplane-mode', 'enable' if previous_airplane == '1' else 'disable')
        shell('svc', 'wifi', 'enable' if previous_wifi == '1' else 'disable')
        shell('svc', 'data', 'enable' if previous_data == '1' else 'disable')
        server.shutdown()
        server.server_close()
