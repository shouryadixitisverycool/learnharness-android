import * as fs from "node:fs";
import * as path from "node:path";
import { createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID, scryptSync } from "node:crypto";
import { gunzipSync, gzipSync } from "node:zlib";
import { CURRENT_CHAT_VERSION } from "../core/contracts.ts";
import { CURRENT_GRAPH_FORMAT_VERSION } from "../core/contracts/graph.ts";
import { parseSourceOrganization } from "../core/source-organization.ts";
import { isValidSegmentPath } from "../core/prompt-segments.ts";
import { sanitizeRenderSettings } from "../core/render-settings.ts";
import { sanitizeUserSettings, CURRENT_SETTINGS_VERSION } from "../core/settings.ts";
import { isCheckpoint, isCoverageEntry, isGraphChain, isGraphNode, isNotesSource, isPosition } from "../core/graph-validation.ts";
import { projectView } from "../core/view.ts";

const FORMAT = "learnharness-backup";
const VERSION = 1;
export const MAX_BACKUP_BYTES = 24 * 1024 * 1024;
export const MAX_ENCRYPTED_BACKUP_BYTES = MAX_BACKUP_BYTES + 64;
const MAX_BODY_BYTES = 48 * 1024 * 1024;
const MAX_ARCHIVE_JSON_BYTES = 66 * 1024 * 1024;
const MAX_FILE_BYTES = 12 * 1024 * 1024;
const MAX_ENTRIES = 20_000;
const ENCRYPTED_MAGIC = Buffer.from([0x4c, 0x48, 0x42, 0x4b, 1]);
const SALT_BYTES = 16;
const IV_BYTES = 12;
const AUTH_TAG_BYTES = 16;
const MIN_PASSWORD_BYTES = 12;
const MAX_PASSWORD_BYTES = 1024;
const SECRET_FREE_SETTINGS = new Set(["version", "general", "dictation", "claudeCode", "agent", "models", "customProvider", "appearance", "graph", "compaction"]);
const GRAPH_FILES = new Set([
	"nodes.jsonl", "chains.jsonl", "sessions.jsonl", "coverage.jsonl", "sources.json",
	"source-organization.json", "positions.json", "render-settings.json", "format.json",
]);
const LEDGER_KIND: Record<string, "nodes" | "chains" | "sessions" | "coverage"> = {
	"nodes.jsonl": "nodes", "chains.jsonl": "chains", "sessions.jsonl": "sessions", "coverage.jsonl": "coverage",
};

export class BackupError extends Error {}
export class BackupLimitError extends BackupError {}

export interface BackupSummary {
	createdAt: string;
	sessions: number;
	graphRecords: number;
	promptOverrides: number;
	includesSettings: boolean;
}

interface BackupEntry {
	path: string;
	size: number;
	sha256: string;
	data: string;
}

interface ParsedBackup {
	createdAt: string;
	entries: Map<string, Buffer>;
	summary: BackupSummary;
}

function fail(message: string): never { throw new BackupError(message); }
function failLimit(message: string): never { throw new BackupLimitError(message); }
function passwordBytes(password: string): Buffer {
	const bytes = Buffer.from(password, "utf8");
	if (bytes.length < MIN_PASSWORD_BYTES || bytes.length > MAX_PASSWORD_BYTES || !/\S/u.test(password)) {
		bytes.fill(0);
		fail(`Use a backup password with at least ${MIN_PASSWORD_BYTES} UTF-8 bytes.`);
	}
	return bytes;
}
function validatePassword(password: string): void { passwordBytes(password).fill(0); }
function deriveKey(password: Buffer, salt: Buffer): Buffer {
	return scryptSync(password, salt, 32, { N: 1 << 16, r: 8, p: 1, maxmem: 96 * 1024 * 1024 });
}
function isRecord(value: unknown): value is Record<string, unknown> { return typeof value === "object" && value !== null && !Array.isArray(value); }
function safePath(file: string): boolean {
	if (file === "settings.json") return true;
	if (/^chats\/[A-Za-z0-9_-]+\.json$/.test(file)) return true;
	const match = /^graph\/([^/]+)$/.exec(file);
	if (match && GRAPH_FILES.has(match[1]!)) return true;
	return file.startsWith("prompts/") && isValidSegmentPath(file.slice("prompts/".length));
}
function sha256(bytes: Uint8Array): string { return createHash("sha256").update(bytes).digest("hex"); }

export function encryptBackup(compressed: Uint8Array, password: string): Buffer {
	if (compressed.byteLength === 0 || compressed.byteLength > MAX_BACKUP_BYTES) failLimit("This backup exceeds the 24 MiB transfer limit.");
	const pass = passwordBytes(password);
	const salt = randomBytes(SALT_BYTES);
	const iv = randomBytes(IV_BYTES);
	const header = Buffer.concat([ENCRYPTED_MAGIC, salt, iv]);
	let key: Buffer | undefined;
	try {
		key = deriveKey(pass, salt);
		const cipher = createCipheriv("aes-256-gcm", key, iv);
		cipher.setAAD(header);
		return Buffer.concat([header, cipher.update(compressed), cipher.final(), cipher.getAuthTag()]);
	} finally { key?.fill(0); pass.fill(0); }
}

export function decryptBackup(encrypted: Uint8Array, password: string): Buffer {
	if (encrypted.byteLength > MAX_ENCRYPTED_BACKUP_BYTES) failLimit("Choose an encrypted LearnHarness backup no larger than 24 MiB.");
	const headerBytes = ENCRYPTED_MAGIC.byteLength + SALT_BYTES + IV_BYTES;
	if (encrypted.byteLength < headerBytes + AUTH_TAG_BYTES) fail("This is not an encrypted LearnHarness backup.");
	const bytes = Buffer.from(encrypted);
	const header = bytes.subarray(0, headerBytes);
	if (!header.subarray(0, ENCRYPTED_MAGIC.byteLength).equals(ENCRYPTED_MAGIC)) fail("This is not an encrypted LearnHarness backup.");
	const salt = header.subarray(ENCRYPTED_MAGIC.byteLength, ENCRYPTED_MAGIC.byteLength + SALT_BYTES);
	const iv = header.subarray(ENCRYPTED_MAGIC.byteLength + SALT_BYTES);
	const pass = passwordBytes(password);
	let key: Buffer | undefined;
	try {
		key = deriveKey(pass, salt);
		const decipher = createDecipheriv("aes-256-gcm", key, iv);
		decipher.setAAD(header);
		decipher.setAuthTag(bytes.subarray(-AUTH_TAG_BYTES));
		return Buffer.concat([decipher.update(bytes.subarray(headerBytes, -AUTH_TAG_BYTES)), decipher.final()]);
	} catch {
		return fail("The password is incorrect or this backup is damaged.");
	} finally { key?.fill(0); pass.fill(0); }
}
function decodeUtf8(bytes: Buffer): string {
	try { return new TextDecoder("utf-8", { fatal: true }).decode(bytes); }
	catch { return fail("The backup contains invalid text encoding."); }
}
function fileBytes(root: string, relative: string): Buffer | null {
	const target = path.join(root, ...relative.split("/"));
	try {
		const stat = fs.lstatSync(target);
		if (!stat.isFile() || stat.isSymbolicLink()) return null;
		if (stat.size > MAX_FILE_BYTES) failLimit("A data file is too large to include in one backup.");
		return fs.readFileSync(target);
	} catch (error) {
		if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
		throw error;
	}
}
function walkFiles(dir: string, prefix: string, out: string[]): void {
	let children: fs.Dirent[];
	try {
		const stat = fs.lstatSync(dir);
		if (!stat.isDirectory() || stat.isSymbolicLink()) return;
		children = fs.readdirSync(dir, { withFileTypes: true });
	}
	catch (error) { if ((error as NodeJS.ErrnoException).code === "ENOENT") return; throw error; }
	for (const child of children) {
		const relative = prefix ? `${prefix}/${child.name}` : child.name;
		const target = path.join(dir, child.name);
		if (child.isDirectory()) walkFiles(target, relative, out);
		else if (child.isFile() && safePath(relative)) out.push(relative);
	}
}
function safeSettings(raw: unknown): Record<string, unknown> {
	const clean = sanitizeUserSettings(raw);
	if (!clean || clean.version > CURRENT_SETTINGS_VERSION) fail("This backup uses a newer settings format. Update LearnHarness before restoring it.");
	return Object.fromEntries(Object.entries(clean).filter(([key]) => SECRET_FREE_SETTINGS.has(key)));
}

function validateContent(relative: string, bytes: Buffer): Buffer {
	if (!safePath(relative)) fail("The backup contains an unsupported file path.");
	const text = decodeUtf8(bytes);
	if (relative.startsWith("prompts/")) return bytes;
	if (relative.endsWith(".jsonl")) {
		const kind = LEDGER_KIND[path.basename(relative)];
		for (const line of text.split("\n")) {
			if (!line.trim()) continue;
			let value: unknown;
			try { value = JSON.parse(line); } catch { return fail("The backup contains a damaged graph ledger."); }
			const valid = kind === "nodes" ? isGraphNode(value) : kind === "chains" ? isGraphChain(value) : kind === "sessions" ? isCheckpoint(value) : isCoverageEntry(value);
			if (!valid) fail("The backup contains an unsupported graph record.");
		}
		return bytes;
	}
	let value: unknown;
	try { value = JSON.parse(text); } catch { return fail("The backup contains damaged structured data."); }
	if (relative === "settings.json") return Buffer.from(`${JSON.stringify(safeSettings(value), null, "\t")}\n`);
	if (relative.startsWith("chats/")) {
		const id = path.basename(relative, ".json");
		if (!isRecord(value) || value.id !== id || typeof value.title !== "string" || !value.title.trim()
			|| typeof value.createdAt !== "string" || !Number.isFinite(Date.parse(value.createdAt))
			|| typeof value.updatedAt !== "string" || !Number.isFinite(Date.parse(value.updatedAt)) || !Array.isArray(value.messages)
			|| (value.version !== undefined && (!Number.isInteger(value.version) || (value.version as number) < 1 || (value.version as number) > CURRENT_CHAT_VERSION))
			|| value.messages.some((message) => !isRecord(message) || typeof message.id !== "string"
				|| !["user", "assistant", "toolResult", "custom"].includes(String(message.role)))) fail("The backup contains a session this app cannot restore.");
		try { projectView(value.messages as never[]); }
		catch { return fail("The backup contains a damaged session transcript."); }
		return bytes;
	}
	const name = path.basename(relative);
	if (name === "format.json") {
		if (!isRecord(value) || value.version !== CURRENT_GRAPH_FORMAT_VERSION) fail("This backup uses a newer knowledge-graph format.");
	} else if (name === "sources.json") {
		if (!isRecord(value) || Object.entries(value).some(([id, source]) => !isNotesSource(source) || source.id !== id)) fail("The backup contains damaged source records.");
	} else if (name === "source-organization.json") {
		try { parseSourceOrganization(value); } catch { return fail("The backup contains damaged source folders."); }
	} else if (name === "positions.json") {
		if (!isRecord(value) || !isRecord(value.positions) || Object.values(value.positions).some((position) => !isPosition(position))) fail("The backup contains damaged graph positions.");
	} else if (name === "render-settings.json") {
		if (!isRecord(value)) fail("The backup contains damaged graph settings.");
		sanitizeRenderSettings(value);
	}
	return bytes;
}

function validateGraphRelationships(entries: ReadonlyMap<string, Buffer>): void {
	const nodeIds = new Set<string>();
	for (const line of decodeUtf8(entries.get("graph/nodes.jsonl") ?? Buffer.alloc(0)).split("\n")) {
		if (line.trim()) nodeIds.add((JSON.parse(line) as { id: string }).id);
	}
	for (const line of decodeUtf8(entries.get("graph/chains.jsonl") ?? Buffer.alloc(0)).split("\n")) {
		if (!line.trim()) continue;
		const chain = JSON.parse(line) as { premises: string[]; conclusion: string };
		if (!nodeIds.has(chain.conclusion) || !chain.premises.every((id) => nodeIds.has(id))) {
			fail("The backup contains a reasoning chain with a missing knowledge record.");
		}
	}
}

function makeSummary(createdAt: string, entries: Map<string, Buffer>): BackupSummary {
	let graphRecords = 0;
	for (const [file, bytes] of entries) {
		if (file.startsWith("graph/") && file.endsWith(".jsonl")) graphRecords += decodeUtf8(bytes).split("\n").filter((line) => line.trim()).length;
	}
	return {
		createdAt,
		sessions: [...entries.keys()].filter((file) => file.startsWith("chats/")).length,
		graphRecords,
		promptOverrides: [...entries.keys()].filter((file) => file.startsWith("prompts/")).length,
		includesSettings: entries.has("settings.json"),
	};
}

export function createBackup(dataDir: string, password: string): Buffer {
	validatePassword(password);
	if (fs.existsSync(dataDir) && fs.lstatSync(dataDir).isSymbolicLink()) fail("The profile data folder is a link and cannot be backed up safely.");
	const files: string[] = [];
	for (const rel of ["settings.json", "chats", "graph", "prompts"]) {
		const target = path.join(/*turbopackIgnore: true*/ dataDir, rel);
		if (rel === "settings.json") { if (fileBytes(dataDir, rel)) files.push(rel); }
		else walkFiles(target, rel, files);
	}
	files.sort();
	if (files.length > MAX_ENTRIES) failLimit("This profile has too many files for one backup.");
	let total = 0;
	const entries: BackupEntry[] = [];
	const contents = new Map<string, Buffer>();
	for (const relative of files) {
		const found = fileBytes(dataDir, relative);
		if (!found) continue;
		const bytes = validateContent(relative, found);
		total += bytes.byteLength;
		if (bytes.byteLength > MAX_FILE_BYTES || total > MAX_BODY_BYTES) failLimit("This profile is too large for one backup.");
		contents.set(relative, bytes);
		entries.push({ path: relative, size: bytes.byteLength, sha256: sha256(bytes), data: bytes.toString("base64") });
	}
	validateGraphRelationships(contents);
	const json = Buffer.from(JSON.stringify({ format: FORMAT, version: VERSION, createdAt: new Date().toISOString(), entries }));
	if (json.byteLength > MAX_ARCHIVE_JSON_BYTES) failLimit("This profile is too large for one backup.");
	const compressed = gzipSync(json);
	if (compressed.byteLength > MAX_BACKUP_BYTES) failLimit("This profile’s backup exceeds the 24 MiB transfer limit.");
	return encryptBackup(compressed, password);
}

export function inspectBackup(encrypted: Uint8Array, password: string): ParsedBackup {
	const compressed = decryptBackup(encrypted, password);
	let parsed: unknown;
	try { parsed = JSON.parse(decodeUtf8(gunzipSync(compressed, { maxOutputLength: MAX_ARCHIVE_JSON_BYTES }))); }
	catch { return fail("This file is not a valid LearnHarness backup."); }
	if (!isRecord(parsed) || parsed.format !== FORMAT || parsed.version !== VERSION || typeof parsed.createdAt !== "string"
		|| !Number.isFinite(Date.parse(parsed.createdAt)) || !Array.isArray(parsed.entries)) fail("This file is not a supported LearnHarness backup.");
	if (parsed.entries.length > MAX_ENTRIES) failLimit("This backup contains too many files.");
	const entries = new Map<string, Buffer>();
	let total = 0;
	for (const item of parsed.entries) {
		if (!isRecord(item) || typeof item.path !== "string" || !safePath(item.path) || typeof item.data !== "string"
			|| !Number.isInteger(item.size) || (item.size as number) < 0 || (item.size as number) > MAX_FILE_BYTES
			|| typeof item.sha256 !== "string" || !/^[a-f0-9]{64}$/.test(item.sha256) || entries.has(item.path)) fail("The backup has an invalid or duplicate entry.");
		const bytes = Buffer.from(item.data, "base64");
		if (bytes.toString("base64") !== item.data || bytes.byteLength !== item.size || sha256(bytes) !== item.sha256) fail("The backup is incomplete or has been changed.");
		total += bytes.byteLength;
		if (total > MAX_BODY_BYTES) failLimit("The backup expands beyond the supported size.");
		entries.set(item.path, validateContent(item.path, bytes));
	}
	validateGraphRelationships(entries);
	return { createdAt: parsed.createdAt, entries, summary: makeSummary(parsed.createdAt, entries) };
}

function readJsonFile(file: string): Record<string, unknown> | null {
	try { const raw: unknown = JSON.parse(fs.readFileSync(file, "utf8")); return isRecord(raw) ? raw : null; }
	catch { return null; }
}
function checkNoLinkedPath(root: string, relative: string): void {
	let current = root;
	for (const part of relative.split("/")) {
		current = path.join(current, part);
		try { if (fs.lstatSync(current).isSymbolicLink()) fail("Restore stopped because this profile contains a linked data path."); }
		catch (error) { if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error; }
	}
}
function mergeGraphFile(stage: string, relative: string, bytes: Buffer): Buffer {
	const dest = path.join(stage, ...relative.split("/"));
	if (relative.endsWith(".jsonl")) {
		const current = fs.existsSync(dest) ? fs.readFileSync(dest, "utf8") : "";
		if (current) validateContent(relative, Buffer.from(current));
		const lines = current.split("\n").filter(Boolean);
		const seen = new Set(lines);
		for (const line of decodeUtf8(bytes).split("\n").filter(Boolean)) if (!seen.has(line)) { lines.push(line); seen.add(line); }
		return Buffer.from(lines.length ? `${lines.join("\n")}\n` : "");
	}
	const incoming = JSON.parse(decodeUtf8(bytes)) as Record<string, unknown>;
	if (!fs.existsSync(dest)) return bytes;
	const currentBytes = fs.readFileSync(dest);
	validateContent(relative, currentBytes);
	const current = readJsonFile(dest);
	if (!current) fail("Merge stopped because existing graph data is damaged.");
	const name = path.basename(relative);
	if (name === "render-settings.json" || name === "format.json" || name === "source-organization.json") return Buffer.from(`${JSON.stringify(current, null, "\t")}\n`);
	if (name === "sources.json") return Buffer.from(`${JSON.stringify({ ...incoming, ...current }, null, "\t")}\n`);
	if (name === "positions.json" && isRecord(incoming.positions) && isRecord(current.positions)) {
		return Buffer.from(`${JSON.stringify({ ...incoming, ...current, positions: { ...incoming.positions, ...current.positions } }, null, "\t")}\n`);
	}
	return bytes;
}

function journalPath(dataDir: string): string {
	return path.join(path.dirname(dataDir), `.${path.basename(dataDir)}.lh-restore.json`);
}
function readJournal(dataDir: string): { stage: string; previous: string; phase: "prepared" | "committed" } | null {
	try {
		const value: unknown = JSON.parse(fs.readFileSync(journalPath(dataDir), "utf8"));
		if (!isRecord(value) || value.version !== 1 || typeof value.stage !== "string" || typeof value.previous !== "string"
			|| (value.phase !== "prepared" && value.phase !== "committed")) return null;
		const prefix = `.${path.basename(dataDir)}.lh-restore-`;
		if (path.basename(value.stage) !== value.stage || path.basename(value.previous) !== value.previous
			|| !value.stage.startsWith(`${prefix}stage-`) || !value.previous.startsWith(`${prefix}previous-`)
			|| !/^[-A-Za-z0-9]+$/.test(value.stage.slice(`${prefix}stage-`.length))
			|| !/^[-A-Za-z0-9]+$/.test(value.previous.slice(`${prefix}previous-`.length))) return null;
		return { stage: value.stage, previous: value.previous, phase: value.phase };
	} catch { return null; }
}
export function recoverInterruptedRestore(dataDir: string): void {
	const journal = readJournal(dataDir);
	if (!journal) return;
	const parent = path.dirname(dataDir);
	const stage = path.join(parent, journal.stage);
	const previous = path.join(parent, journal.previous);
	const hasStage = fs.existsSync(stage);
	const hasPrevious = fs.existsSync(previous);
	if (journal.phase === "prepared" && hasPrevious) {
		fs.rmSync(dataDir, { recursive: true, force: true });
		fs.renameSync(previous, dataDir);
	} else if (journal.phase === "committed" && !fs.existsSync(dataDir)) {
		if (hasStage) fs.renameSync(stage, dataDir);
		else if (hasPrevious) fs.renameSync(previous, dataDir);
	}
	fs.rmSync(stage, { recursive: true, force: true });
	fs.rmSync(previous, { recursive: true, force: true });
	fs.rmSync(journalPath(dataDir), { force: true });
}

export function restoreBackup(dataDir: string, backup: ParsedBackup, mode: "merge" | "replace"): BackupSummary {
	recoverInterruptedRestore(dataDir);
	const parent = path.dirname(dataDir);
	fs.mkdirSync(parent, { recursive: true });
	if (fs.existsSync(dataDir) && fs.lstatSync(dataDir).isSymbolicLink()) fail("Restore stopped because the profile data folder is a link.");
	const prefix = `.${path.basename(dataDir)}.lh-restore-`;
	const stage = path.join(parent, `${prefix}stage-${randomUUID()}`);
	const previous = path.join(parent, `${prefix}previous-${randomUUID()}`);
	const journal = journalPath(dataDir);
	const journalTemp = `${journal}.tmp-${randomUUID()}`;
	let movedOld = false;
	try {
		fs.mkdirSync(stage);
		if (fs.existsSync(dataDir)) {
			const replaced = mode === "replace" ? new Set(["settings.json", "chats", "graph", "prompts"]) : null;
			for (const name of fs.readdirSync(dataDir)) {
				if (replaced?.has(name)) continue;
				fs.cpSync(path.join(dataDir, name), path.join(stage, name), { recursive: true, errorOnExist: true, force: false, dereference: false });
			}
		}
		for (const [relative, source] of backup.entries) {
			const target = path.join(stage, ...relative.split("/"));
			checkNoLinkedPath(stage, relative);
			if (mode === "merge" && fs.existsSync(target)) {
				if (relative.startsWith("chats/") || relative.startsWith("prompts/") || relative === "settings.json") continue;
				if (relative.startsWith("graph/")) {
					const merged = mergeGraphFile(stage, relative, source);
					fs.mkdirSync(path.dirname(target), { recursive: true });
					fs.writeFileSync(target, merged);
					continue;
				}
			}
			fs.mkdirSync(path.dirname(target), { recursive: true });
			fs.writeFileSync(target, source);
		}
		const initial = { version: 1, stage: path.basename(stage), previous: path.basename(previous), phase: "prepared" };
		fs.writeFileSync(journalTemp, JSON.stringify(initial));
		fs.renameSync(journalTemp, journal);
		if (fs.existsSync(dataDir)) { fs.renameSync(dataDir, previous); movedOld = true; }
		fs.renameSync(stage, dataDir);
		fs.writeFileSync(journalTemp, JSON.stringify({ ...initial, phase: "committed" }));
		fs.renameSync(journalTemp, journal);
		fs.rmSync(previous, { recursive: true, force: true });
		fs.rmSync(journal, { force: true });
		return backup.summary;
	} catch (error) {
		if (movedOld && fs.existsSync(previous)) {
			fs.rmSync(dataDir, { recursive: true, force: true });
			fs.renameSync(previous, dataDir);
		}
		fs.rmSync(stage, { recursive: true, force: true });
		fs.rmSync(journalTemp, { force: true });
		fs.rmSync(journal, { force: true });
		throw error;
	}
}
