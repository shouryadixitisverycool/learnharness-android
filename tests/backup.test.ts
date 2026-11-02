import assert from "node:assert/strict";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { createRequire } from "node:module";
import { createHash } from "node:crypto";
import { gunzipSync, gzipSync } from "node:zlib";
import { afterEach, test } from "node:test";
import { BackupLimitError, MAX_ENCRYPTED_BACKUP_BYTES, createBackup, decryptBackup, encryptBackup, inspectBackup, recoverInterruptedRestore, restoreBackup } from "../src/store/backup.ts";

const { recoverDataRestore } = createRequire(import.meta.url)("../android/runtime/entry.cjs") as { recoverDataRestore: (dataDir: string) => void };

const roots: string[] = [];
const PASSWORD = "correct horse battery staple";
function tempRoot(): string {
	const root = fs.mkdtempSync(path.join(os.tmpdir(), "learnharness-backup-test-"));
	roots.push(root);
	return root;
}
function write(root: string, relative: string, value: string): void {
	const file = path.join(root, relative);
	fs.mkdirSync(path.dirname(file), { recursive: true });
	fs.writeFileSync(file, value);
}
function read(root: string, relative: string): string {
	return fs.readFileSync(path.join(root, relative), "utf8");
}
function nodeLine(id: string): string {
	return JSON.stringify({ id, name: id, statement: `${id} statement`, aliases: [], createdAt: "2026-11-02T00:00:00.000Z", provenance: [] });
}
function profile(root: string, title: string): void {
	write(root, "settings.json", JSON.stringify({ version: 1, general: { autoUpdate: false }, apiKey: "must-not-export" }));
	write(root, "chats/chat_one.json", JSON.stringify({ id: "chat_one", title, createdAt: "2026-11-02T00:00:00.000Z", updatedAt: "2026-11-02T00:00:00.000Z", messages: [], version: 1 }));
	write(root, "graph/nodes.jsonl", `${nodeLine("n_one")}\n`);
	write(root, "graph/format.json", JSON.stringify({ version: 1 }));
	write(root, "prompts/system-prompt.md", "Be precise.\n");
}

afterEach(() => { for (const root of roots.splice(0)) fs.rmSync(root, { recursive: true, force: true }); });

test("backup exports the allowlist and round-trips into a synthetic profile", () => {
	const source = path.join(tempRoot(), "source");
	profile(source, "Original");
	write(source, "graph/nodes.jsonl", `${nodeLine("n_one")}\n${nodeLine("n_two")}\n`);
	write(source, "graph/chains.jsonl", `${JSON.stringify({ id: "arg_one", premises: ["n_one"], conclusion: "n_two", createdAt: "2026-11-02T00:00:00.000Z", provenance: [] })}\n`);
	write(source, "auth.json", "secret");
	write(source, "jobs/job.json", "transient");
	write(source, "debug/provider-events/event.jsonl", "private diagnostics");
	write(source, "other/ignored.json", "not application data");
	const archive = createBackup(source, PASSWORD);
	const manifest = JSON.parse(gunzipSync(decryptBackup(archive, PASSWORD)).toString("utf8")) as { entries: Array<{ path: string; data: string }> };
	assert.deepEqual(manifest.entries.map((entry) => entry.path), ["chats/chat_one.json", "graph/chains.jsonl", "graph/format.json", "graph/nodes.jsonl", "prompts/system-prompt.md", "settings.json"]);
	assert.equal(JSON.stringify(manifest).includes("must-not-export"), false);
	assert.equal(JSON.stringify(manifest).includes("secret"), false);
	const parsed = inspectBackup(archive, PASSWORD);
	assert.deepEqual(parsed.summary, { createdAt: parsed.summary.createdAt, sessions: 1, graphRecords: 3, promptOverrides: 1, includesSettings: true });
	const target = path.join(tempRoot(), "target", "data");
	write(target, "jobs/current.json", "keep");
	write(target, "debug/current.log", "keep");
	restoreBackup(target, parsed, "replace");
	assert.equal(JSON.parse(read(target, "chats/chat_one.json")).title, "Original");
	const nodeIds = new Set(read(target, "graph/nodes.jsonl").trim().split("\n").map((line) => JSON.parse(line).id));
	const chain = JSON.parse(read(target, "graph/chains.jsonl"));
	assert.ok(nodeIds.has(chain.conclusion) && chain.premises.every((id: string) => nodeIds.has(id)));
	assert.equal(read(target, "jobs/current.json"), "keep");
	assert.equal(read(target, "debug/current.log"), "keep");
});

test("merge keeps current session and settings while adding graph records", () => {
	const source = path.join(tempRoot(), "source");
	profile(source, "From backup");
	write(source, "graph/nodes.jsonl", `${nodeLine("n_one")}\n${nodeLine("n_two")}\n`);
	const parsed = inspectBackup(createBackup(source, PASSWORD), PASSWORD);
	const target = path.join(tempRoot(), "target", "data");
	profile(target, "On phone");
	write(target, "settings.json", JSON.stringify({ version: 1, general: { autoUpdate: true } }));
	restoreBackup(target, parsed, "merge");
	assert.equal(JSON.parse(read(target, "chats/chat_one.json")).title, "On phone");
	assert.equal(JSON.parse(read(target, "settings.json")).general.autoUpdate, true);
	assert.deepEqual(read(target, "graph/nodes.jsonl").trim().split("\n").map((line) => JSON.parse(line).id), ["n_one", "n_two"]);
});

test("invalid paths and damaged digests are rejected before any restore", () => {
	const source = path.join(tempRoot(), "source");
	profile(source, "Original");
	const archive = createBackup(source, PASSWORD);
	const manifest = JSON.parse(gunzipSync(decryptBackup(archive, PASSWORD)).toString("utf8"));
	manifest.entries[0].path = "../auth.json";
	assert.throws(() => inspectBackup(encryptBackup(gzipSync(Buffer.from(JSON.stringify(manifest))), PASSWORD), PASSWORD), /invalid or duplicate entry/i);
	const valid = JSON.parse(gunzipSync(decryptBackup(archive, PASSWORD)).toString("utf8"));
	valid.entries[0].data = Buffer.from("changed").toString("base64");
	assert.throws(() => inspectBackup(encryptBackup(gzipSync(Buffer.from(JSON.stringify(valid))), PASSWORD), PASSWORD), /incomplete|changed/i);
	const malformed = JSON.parse(gunzipSync(decryptBackup(archive, PASSWORD)).toString("utf8"));
	const nodes = malformed.entries.find((entry: { path: string }) => entry.path === "graph/nodes.jsonl");
	const body = Buffer.from("{}\n");
	nodes.data = body.toString("base64");
	nodes.size = body.byteLength;
	nodes.sha256 = createHash("sha256").update(body).digest("hex");
	const target = path.join(tempRoot(), "target", "data");
	write(target, "marker", "unchanged");
	assert.throws(() => {
		const parsed = inspectBackup(encryptBackup(gzipSync(Buffer.from(JSON.stringify(malformed))), PASSWORD), PASSWORD);
		restoreBackup(target, parsed, "replace");
	}, /unsupported graph record/i);
	assert.equal(read(target, "marker"), "unchanged");
});

test("archives reject graph chains whose endpoints are missing and classify size limits", () => {
	const source = path.join(tempRoot(), "source");
	profile(source, "Original");
	write(source, "graph/nodes.jsonl", `${nodeLine("n_one")}\n${nodeLine("n_two")}\n`);
	write(source, "graph/chains.jsonl", `${JSON.stringify({ id: "arg_one", premises: ["n_one"], conclusion: "n_two", createdAt: "2026-11-02T00:00:00.000Z", provenance: [] })}\n`);
	const manifest = JSON.parse(gunzipSync(decryptBackup(createBackup(source, PASSWORD), PASSWORD)).toString("utf8"));
	const chain = manifest.entries.find((entry: { path: string }) => entry.path === "graph/chains.jsonl");
	const damagedChain = Buffer.from(`${JSON.stringify({ id: "arg_one", premises: ["missing"], conclusion: "n_two", createdAt: "2026-11-02T00:00:00.000Z", provenance: [] })}\n`);
	chain.data = damagedChain.toString("base64");
	chain.size = damagedChain.byteLength;
	chain.sha256 = createHash("sha256").update(damagedChain).digest("hex");
	const target = path.join(tempRoot(), "target", "data");
	write(target, "marker", "unchanged");
	const invalid = encryptBackup(gzipSync(Buffer.from(JSON.stringify(manifest))), PASSWORD);
	assert.throws(() => restoreBackup(target, inspectBackup(invalid, PASSWORD), "replace"), /missing knowledge record/i);
	assert.equal(read(target, "marker"), "unchanged");
	assert.throws(() => inspectBackup(new Uint8Array(MAX_ENCRYPTED_BACKUP_BYTES + 1), PASSWORD), BackupLimitError);
	const validArchive = createBackup(source, PASSWORD);
	assert.throws(() => inspectBackup(validArchive, "definitely wrong password"), /password is incorrect/i);
	const damagedArchive = Buffer.from(validArchive);
	const damageIndex = damagedArchive.length - 20;
	damagedArchive[damageIndex] = damagedArchive[damageIndex]! ^ 1;
	assert.throws(() => inspectBackup(damagedArchive, PASSWORD), /password is incorrect/i);
	assert.throws(() => createBackup(source, "short"), /at least 12 UTF-8 bytes/i);
});

test("prepared restore journal rolls back to the previous profile", () => {
	const data = path.join(tempRoot(), "profile", "data");
	const parent = path.dirname(data);
	fs.mkdirSync(data, { recursive: true });
	write(data, "marker", "new profile");
	const previousName = ".data.lh-restore-previous-test";
	const stageName = ".data.lh-restore-stage-test";
	write(path.join(parent, previousName), "marker", "old profile");
	write(path.join(parent, stageName), "marker", "unused stage");
	write(parent, ".data.lh-restore.json", JSON.stringify({ version: 1, stage: stageName, previous: previousName, phase: "prepared" }));
	recoverInterruptedRestore(data);
	assert.equal(read(data, "marker"), "old profile");
	assert.equal(fs.existsSync(path.join(parent, stageName)), false);
	assert.equal(fs.existsSync(path.join(parent, ".data.lh-restore.json")), false);
});

test("Android startup keeps committed data and removes its previous snapshot", () => {
	const data = path.join(tempRoot(), "profile", "data");
	const parent = path.dirname(data);
	write(data, "marker", "restored profile");
	const previousName = ".data.lh-restore-previous-test";
	const stageName = ".data.lh-restore-stage-test";
	write(path.join(parent, previousName), "marker", "old profile");
	write(parent, ".data.lh-restore.json", JSON.stringify({ version: 1, stage: stageName, previous: previousName, phase: "committed" }));
	recoverDataRestore(data);
	assert.equal(read(data, "marker"), "restored profile");
	assert.equal(fs.existsSync(path.join(parent, previousName)), false);
	assert.equal(fs.existsSync(path.join(parent, ".data.lh-restore.json")), false);
});
