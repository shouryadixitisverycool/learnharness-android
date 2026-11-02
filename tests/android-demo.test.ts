import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { createServer } from 'node:net';
import test from 'node:test';
import { runAndroidDemo } from '../src/agent/subagents/android-demo.ts';

test('Android demo bridge sends the authored HTML/probe and validates the reply', async () => {
    const name = `lh-demo-${randomBytes(32).toString('hex')}`;
    let valid = true;
    const server = createServer({ allowHalfOpen: true }, socket => {
        let request = '';
        socket.on('data', chunk => { request += chunk; });
        socket.on('end', () => {
            assert.deepEqual(JSON.parse(request), { html: '<button>Test</button>', probe: 'Promise.resolve(true)', settleMs: 0 });
            socket.end(JSON.stringify(valid ? { ok: true, consoleErrors: [], pageErrors: [], failedRequests: [], probeResult: true } : { ok: true }));
        });
    });
    await new Promise<void>(resolve => server.listen(`\0${name}`, resolve));
    try {
        const result = await runAndroidDemo(name, '<button>Test</button>', { probe: 'Promise.resolve(true)', settleMs: 0 });
        assert.equal(result.ok, true);
        assert.equal(result.probeResult, true);
        valid = false;
        await assert.rejects(runAndroidDemo(name, '<button>Test</button>', { probe: 'Promise.resolve(true)', settleMs: 0 }), /Invalid Android demo response/);
        await assert.rejects(runAndroidDemo('invalid', ''), /Invalid Android demo socket/);
    } finally { await new Promise<void>(resolve => server.close(() => resolve())); }
});
