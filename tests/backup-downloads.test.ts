import assert from "node:assert/strict";
import { test } from "node:test";
import { readBackupDownload, stageBackupDownload } from "../src/server/backup-downloads.ts";

test("backup downloads use opaque bounded in-memory handles", () => {
	const first = stageBackupDownload(Buffer.from("one"));
	const second = stageBackupDownload(Buffer.from("two"));
	const third = stageBackupDownload(Buffer.from("three"));
	assert.match(third, /^[a-f0-9]{64}$/);
	assert.equal(readBackupDownload(first), null);
	assert.equal(readBackupDownload(second)?.toString(), "two");
	assert.equal(readBackupDownload(third)?.toString(), "three");
	assert.equal(readBackupDownload("../../auth.json"), null);
});
