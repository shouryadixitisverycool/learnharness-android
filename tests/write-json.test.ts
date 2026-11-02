import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { describe, it } from "node:test";
import { writeJsonAtomic } from "../src/store/write-json.ts";

describe("writeJsonAtomic", () => {
	it("replaces a JSON record without leaving temporary files", () => {
		const dir = fs.mkdtempSync(path.join(os.tmpdir(), "lh-atomic-json-"));
		try {
			const file = path.join(dir, "record.json");
			fs.writeFileSync(file, "old");
			writeJsonAtomic(file, { answer: 42 });
			assert.deepEqual(JSON.parse(fs.readFileSync(file, "utf8")), { answer: 42 });
			assert.deepEqual(fs.readdirSync(dir), ["record.json"]);
		} finally { fs.rmSync(dir, { recursive: true, force: true }); }
	});

	it("cleans up the temporary file when replacement fails", () => {
		const dir = fs.mkdtempSync(path.join(os.tmpdir(), "lh-atomic-json-"));
		try {
			const target = path.join(dir, "record.json");
			fs.mkdirSync(target);
			assert.throws(() => writeJsonAtomic(target, { answer: 42 }));
			assert.deepEqual(fs.readdirSync(dir), ["record.json"]);
		} finally { fs.rmSync(dir, { recursive: true, force: true }); }
	});
});
