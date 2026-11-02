import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import test from 'node:test';
import { downloadFile } from '../components/artifacts/download.ts';

const { authorized } = createRequire(import.meta.url)('../android/runtime/entry.cjs');
test('Android local server requires its per-launch capability and exact host/origin', () => {
    const token = 'a'.repeat(64);
    const headers = { host: '127.0.0.1:49152', cookie: `other=value; lh_android=${token}`, origin: 'http://127.0.0.1:49152' };
    assert.equal(authorized({ headers }, 49152, token), true);
    assert.equal(authorized({ headers: { ...headers, origin: undefined } }, 49152, token), true);
    for (const changes of [{ cookie: '' }, { cookie: `lh_android=${'b'.repeat(64)}` }, { cookie: 'lh_android=a' }, { host: 'evil.test:49152' }, { origin: 'https://evil.test' }]) {
        assert.equal(authorized({ headers: { ...headers, ...changes } }, 49152, token), false);
    }
    assert.equal(authorized({ headers }, 49152, ''), false);
});

test('Android artifact downloads reach the native document picker without creating a blob URL', () => {
    const previous = Object.getOwnPropertyDescriptor(globalThis, 'window');
    let saved: unknown;
    Object.defineProperty(globalThis, 'window', { configurable: true, value: { lhAndroidSaveText: (...args: unknown[]) => { saved = args; } } });
    try {
        downloadFile('figure.svg', '<svg/>', 'image/svg+xml');
        assert.deepEqual(saved, ['figure.svg', '<svg/>', 'image/svg+xml']);
    } finally {
        if (previous) Object.defineProperty(globalThis, 'window', previous);
        else Reflect.deleteProperty(globalThis, 'window');
    }
});
