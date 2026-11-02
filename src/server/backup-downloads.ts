import { randomBytes } from "node:crypto";

const MAX_PENDING_DOWNLOADS = 2;
const DOWNLOAD_TTL_MS = 5 * 60_000;
const pending = new Map<string, { bytes: Buffer; expiresAt: number }>();

function discardExpired(now: number): void {
	for (const [id, item] of pending) if (item.expiresAt <= now) pending.delete(id);
}

export function stageBackupDownload(bytes: Uint8Array): string {
	const now = Date.now();
	discardExpired(now);
	while (pending.size >= MAX_PENDING_DOWNLOADS) pending.delete(pending.keys().next().value!);
	const id = randomBytes(32).toString("hex");
	pending.set(id, { bytes: Buffer.from(bytes), expiresAt: now + DOWNLOAD_TTL_MS });
	return id;
}

export function readBackupDownload(id: string): Buffer | null {
	const now = Date.now();
	discardExpired(now);
	return /^[a-f0-9]{64}$/.test(id) ? pending.get(id)?.bytes ?? null : null;
}
