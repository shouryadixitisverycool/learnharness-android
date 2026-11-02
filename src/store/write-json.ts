import fs from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";

export function writeTextAtomic(file: string, text: string): void {
	const tmp = path.join(path.dirname(file), `.${path.basename(file)}.${randomUUID()}.tmp`);
	try {
		fs.writeFileSync(tmp, text, "utf-8");
		fs.renameSync(tmp, file);
	} finally {
		fs.rmSync(tmp, { force: true });
	}
}

export function writeJsonAtomic(file: string, value: unknown): void {
	writeTextAtomic(file, `${JSON.stringify(value, null, "\t")}\n`);
}
