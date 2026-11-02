import { PATHS } from "../../config.ts";
import { activityFeed } from "./observability.ts";
import { isTurnRunning } from "./turn-liveness.ts";
import { stores } from "./stores.ts";
import { createBackup, inspectBackup, restoreBackup, type BackupSummary } from "../store/backup.ts";

export { BackupError, BackupLimitError, MAX_BACKUP_BYTES, MAX_ENCRYPTED_BACKUP_BYTES } from "../store/backup.ts";

export function exportLearningData(password: string): Buffer {
	return createBackup(PATHS.data, password);
}

export function previewLearningData(bytes: Uint8Array, password: string): BackupSummary {
	return inspectBackup(bytes, password).summary;
}

export class RestoreBusyError extends Error {}

export function restoreLearningData(bytes: Uint8Array, password: string, mode: "merge" | "replace"): BackupSummary {
	const archive = inspectBackup(bytes, password);
	const chats = stores.chatStore.listChats();
	if (chats.some((chat) => isTurnRunning(chat.id))) throw new RestoreBusyError("Finish or stop active lessons before restoring a backup.");
	const activity = activityFeed();
	if (activity.jobs.length || activity.extractions.length) throw new RestoreBusyError("Wait for background work to finish before restoring a backup.");
	return restoreBackup(PATHS.data, archive, mode);
}
