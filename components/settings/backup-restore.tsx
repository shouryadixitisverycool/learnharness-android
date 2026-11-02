"use client";

import { useRef, useState } from "react";

interface Summary {
	sessions: number;
	graphRecords: number;
	promptOverrides: number;
	includesSettings: boolean;
}

export function BackupRestoreSection() {
	const input = useRef<HTMLInputElement>(null);
	const [file, setFile] = useState<File | null>(null);
	const [password, setPassword] = useState("");
	const [downloadUrl, setDownloadUrl] = useState("");
	const [summary, setSummary] = useState<Summary | null>(null);
	const [busy, setBusy] = useState(false);
	const [confirmReplace, setConfirmReplace] = useState(false);
	const [message, setMessage] = useState("");
	const [error, setError] = useState("");

	async function createBackup() {
		setBusy(true);
		setError("");
		setDownloadUrl("");
		setMessage("Creating encrypted backup…");
		try {
			const body = new FormData();
			body.set("operation", "export");
			body.set("password", password);
			const response = await fetch("/api/backup", { method: "POST", body });
			const result = await response.json() as { error?: string; downloadUrl?: string };
			if (!response.ok || !result.downloadUrl) throw new Error(result.error ?? "The backup could not be created.");
			setDownloadUrl(result.downloadUrl);
			setPassword("");
			setMessage("Encrypted backup ready. Save it to a private location.");
		} catch (reason) {
			setError(reason instanceof Error ? reason.message : "The backup could not be created.");
			setMessage("");
		} finally { setBusy(false); }
	}

	async function transfer(operation: "preview" | "merge" | "replace", selected = file) {
		if (!selected) return;
		setBusy(true);
		setError("");
		setMessage(operation === "preview" ? "Unlocking and checking backup…" : "Restoring backup…");
		try {
			const body = new FormData();
			body.set("operation", operation);
			body.set("password", password);
			body.set("backup", selected, selected.name);
			const response = await fetch("/api/backup", { method: "POST", body });
			const result = await response.json() as { error?: string; summary?: Summary };
			if (!response.ok || !result.summary) throw new Error(result.error ?? "The backup could not be processed.");
			if (operation === "preview") {
				setSummary(result.summary);
				setMessage("Backup checked. Choose how to restore it.");
			} else {
				setMessage("Restore complete. Reloading LearnHarness…");
				setPassword("");
				window.setTimeout(() => window.location.reload(), 350);
			}
		} catch (reason) {
			setError(reason instanceof Error ? reason.message : "The backup could not be checked or restored.");
			setMessage("");
		} finally { setBusy(false); }
	}

	function chooseFile(next: File | null) {
		setFile(next);
		setPassword("");
		setDownloadUrl("");
		setSummary(null);
		setConfirmReplace(false);
		setMessage("");
		setError("");
	}

	return (
		<div className="min-w-0 space-y-3 border-t border-border px-5 py-4" data-testid="backup-restore">
			<div className="min-w-0">
				<h3 className="font-ui text-sm font-medium">Backup and restore</h3>
				<p className="mt-1 break-words text-xs text-muted-foreground">
				Backups encrypt sessions, knowledge, settings and prompt overrides with your password, up to 24 MiB. LearnHarness doesn’t save or recover the password; keep it in a password manager and save the download within five minutes. Saved provider credentials, subscription sign-ins, diagnostics and temporary jobs aren’t included.
				</p>
			</div>
			<label className="block min-w-0 space-y-1 text-xs text-muted-foreground" htmlFor="backup-password">
				<span className="block">Backup password (at least 12 characters)</span>
				<input
					id="backup-password"
					type="password"
					autoComplete="new-password"
					maxLength={1024}
					value={password}
					onChange={(event) => { setPassword(event.currentTarget.value); setError(""); }}
					className="min-h-12 w-full max-w-md rounded-sm border border-border bg-background px-3 py-2 text-sm text-foreground"
					data-testid="backup-password"
				/>
			</label>
			<div className="flex flex-wrap gap-2">
				<button type="button" className="inline-flex min-h-12 max-w-full items-center rounded-sm border border-border px-4 py-2 text-sm hover:bg-accent disabled:opacity-50" onClick={() => void createBackup()} disabled={busy || password.length < 12} data-testid="backup-create">
					Create encrypted backup
				</button>
				{downloadUrl && <a href={downloadUrl} download="learnharness-backup.lhbackup" className="inline-flex min-h-12 max-w-full items-center rounded-sm border border-border px-4 py-2 text-sm hover:bg-accent" data-testid="backup-export">Save encrypted backup</a>}
				<input
					ref={input}
					type="file"
					accept=".lhbackup,application/octet-stream"
					className="sr-only"
					aria-label="Choose a LearnHarness backup"
					data-testid="backup-file"
					onChange={(event) => chooseFile(event.currentTarget.files?.[0] ?? null)}
				/>
				<button type="button" className="inline-flex min-h-12 max-w-full items-center rounded-sm border border-border px-4 py-2 text-sm hover:bg-accent disabled:opacity-50" onClick={() => { if (input.current) { input.current.value = ""; input.current.click(); } }} disabled={busy}>
					Choose backup file
				</button>
			</div>
			{file && (
				<div className="min-w-0 space-y-2">
					<p className="break-all text-xs text-muted-foreground">Selected: {file.name}</p>
					<button type="button" className="inline-flex min-h-12 max-w-full items-center rounded-sm border border-border px-4 py-2 text-sm hover:bg-accent disabled:opacity-50" onClick={() => void transfer("preview")} disabled={busy || password.length < 12} data-testid="backup-check">Check backup</button>
				</div>
			)}
			{summary && (
				<div className="min-w-0 space-y-3 rounded-sm border border-border p-3" data-testid="backup-preview">
					<p className="break-words text-xs text-muted-foreground">
						{summary.sessions} sessions · {summary.graphRecords} knowledge records · {summary.promptOverrides} prompt overrides · {summary.includesSettings ? "settings included" : "no settings"}
					</p>
					<div className="flex flex-wrap gap-2">
						<button type="button" className="min-h-12 rounded-sm bg-foreground px-4 py-2 text-sm text-background disabled:opacity-50" onClick={() => void transfer("merge")} disabled={busy} data-testid="backup-merge">Merge, keeping this phone’s records</button>
						<button type="button" className="min-h-12 rounded-sm border border-border px-4 py-2 text-sm hover:bg-accent disabled:opacity-50" onClick={() => setConfirmReplace(true)} disabled={busy} data-testid="backup-replace">Replace learning data</button>
					</div>
					{confirmReplace && (
						<div className="min-w-0 space-y-2 border-t border-border pt-3" role="group" aria-label="Confirm replacing learning data">
							<p className="break-words text-xs text-muted-foreground">This replaces the sessions, knowledge, settings and prompt overrides currently on this phone. Credentials and temporary jobs remain.</p>
							<div className="flex flex-wrap gap-2">
								<button type="button" className="min-h-12 rounded-sm border border-red-500/50 px-4 py-2 text-sm text-red-500 disabled:opacity-50" onClick={() => void transfer("replace")} disabled={busy} data-testid="backup-confirm-replace">Confirm replace</button>
								<button type="button" className="min-h-12 rounded-sm border border-border px-4 py-2 text-sm" onClick={() => setConfirmReplace(false)} disabled={busy}>Cancel</button>
							</div>
						</div>
					)}
				</div>
			)}
			{message && <p role="status" className="break-words text-xs text-muted-foreground">{message}</p>}
			{error && <p role="alert" className="break-words text-xs text-red-500">{error}</p>}
		</div>
	);
}
