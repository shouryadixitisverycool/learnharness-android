import fs from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";

export function writeJsonAtomic(file: string, value: unknown): void {
	const tmp = path.join(path.dirname(file), `.${path.basename(file)}.${randomUUID()}.tmp`);
	try {
		fs.writeFileSync(tmp, `${JSON.stringify(value, null, "\t")}\n`, "utf-8");
		fs.renameSync(tmp, file);
	} finally {
		fs.rmSync(tmp, { force: true });
	}
}
