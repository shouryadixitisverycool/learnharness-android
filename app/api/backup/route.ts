import {
	BackupError,
	BackupLimitError,
	MAX_ENCRYPTED_BACKUP_BYTES,
	exportLearningData,
	readBackupDownload,
	previewLearningData,
	restoreLearningData,
	RestoreBusyError,
	stageBackupDownload,
} from "@/src/server";

export const dynamic = "force-dynamic";
const MAX_REQUEST_BYTES = MAX_ENCRYPTED_BACKUP_BYTES + 1024 * 1024;

class RequestTooLargeError extends Error {}

async function readForm(request: Request): Promise<FormData> {
	if (Number(request.headers.get("content-length")) > MAX_REQUEST_BYTES) {
		void request.body?.cancel().catch(() => undefined);
		throw new RequestTooLargeError();
	}
	const reader = request.body?.getReader();
	if (!reader) throw new SyntaxError("Missing upload");
	const chunks: Uint8Array[] = [];
	let size = 0;
	try {
		for (;;) {
			const { done, value } = await reader.read();
			if (done) break;
			size += value.byteLength;
			if (size > MAX_REQUEST_BYTES) throw new RequestTooLargeError();
			chunks.push(value);
		}
		const body = new Uint8Array(size);
		let offset = 0;
		for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.byteLength; }
		return await new Response(body, { headers: { "Content-Type": request.headers.get("content-type") ?? "" } }).formData();
	} finally {
		void reader.cancel().catch(() => undefined);
		reader.releaseLock();
	}
}

export async function GET(request: Request) {
	const id = new URL(request.url).searchParams.get("download");
	const archive = id ? readBackupDownload(id) : null;
	if (!archive) return Response.json({ error: "This backup download has expired. Create it again." }, { status: 404, headers: { "Cache-Control": "no-store" } });
	return new Response(new Uint8Array(archive), { headers: {
		"Content-Type": "application/octet-stream",
		"Content-Disposition": 'attachment; filename="learnharness-backup.lhbackup"',
		"Content-Length": String(archive.byteLength),
		"Cache-Control": "no-store",
	} });
}

export async function POST(request: Request) {
	let form: FormData;
	try { form = await readForm(request); }
	catch (error) {
		return Response.json({ error: error instanceof RequestTooLargeError ? "This backup is too large to import." : "Could not read the selected backup." }, {
			status: error instanceof RequestTooLargeError ? 413 : 400,
			headers: { "Cache-Control": "no-store" },
		});
	}
	const operation = form.get("operation");
	if (operation === "export") {
		const password = form.get("password");
		if (typeof password !== "string") return Response.json({ error: "Enter a backup password." }, { status: 400, headers: { "Cache-Control": "no-store" } });
		try {
			const id = stageBackupDownload(exportLearningData(password));
			return Response.json({ downloadUrl: `/api/backup?download=${id}` }, { headers: { "Cache-Control": "no-store" } });
		} catch (error) {
			return Response.json({ error: error instanceof BackupError ? error.message : "Could not create a backup." }, {
				status: error instanceof BackupLimitError ? 413 : error instanceof BackupError ? 400 : 500,
				headers: { "Cache-Control": "no-store" },
			});
		}
	}
	const file = form.get("backup");
	const password = form.get("password");
	if ((operation !== "preview" && operation !== "merge" && operation !== "replace") || !(file instanceof File) || typeof password !== "string") {
		return Response.json({ error: "Select a LearnHarness backup file." }, { status: 400, headers: { "Cache-Control": "no-store" } });
	}
	if (file.size > MAX_ENCRYPTED_BACKUP_BYTES) return Response.json({ error: "Choose an encrypted backup no larger than 24 MiB." }, { status: 413, headers: { "Cache-Control": "no-store" } });
	const bytes = new Uint8Array(await file.arrayBuffer());
	try {
		const summary = operation === "preview" ? previewLearningData(bytes, password) : restoreLearningData(bytes, password, operation);
		return Response.json({ summary }, { headers: { "Cache-Control": "no-store" } });
	} catch (error) {
		const busy = error instanceof RestoreBusyError;
		const invalid = error instanceof BackupError;
		const tooLarge = error instanceof BackupLimitError;
		return Response.json({ error: error instanceof Error ? error.message : "Could not restore this backup." }, {
			status: busy ? 409 : tooLarge ? 413 : invalid ? 400 : 500,
			headers: { "Cache-Control": "no-store" },
		});
	}
}
