import type { CoverageEntry, GraphChain, GraphNode, NotesSource, Position, SessionCheckpoint } from "./contracts.ts";

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);
const isString = (v: unknown): v is string => typeof v === "string";
const isProvenancePoint = (v: unknown) => isRecord(v) && isString(v.chatId) && isString(v.messageId);

export const isGraphNode = (v: unknown): v is GraphNode => isRecord(v)
	&& isString(v.id) && isString(v.name) && isString(v.statement)
	&& Array.isArray(v.aliases) && v.aliases.every(isString)
	&& isString(v.createdAt) && Array.isArray(v.provenance) && v.provenance.every(isProvenancePoint);

export const isGraphChain = (v: unknown): v is GraphChain => isRecord(v)
	&& isString(v.id) && Array.isArray(v.premises) && v.premises.length > 0
	&& v.premises.every(isString) && isString(v.conclusion)
	&& Array.isArray(v.provenance) && v.provenance.every(isProvenancePoint) && isString(v.createdAt);

export const isCheckpoint = (v: unknown): v is SessionCheckpoint => isRecord(v) && isString(v.chatId) && isString(v.foldedAt);
export const isCoverageEntry = (v: unknown): v is CoverageEntry => isRecord(v)
	&& isString(v.chatId) && isString(v.fp) && typeof v.tokens === "number" && Number.isFinite(v.tokens) && isString(v.foldedAt);
export const isPosition = (v: unknown): v is Position => isRecord(v)
	&& typeof v.x === "number" && Number.isFinite(v.x) && typeof v.y === "number" && Number.isFinite(v.y);
export const isNotesSource = (v: unknown): v is NotesSource => isRecord(v)
	&& isString(v.id) && isString(v.filePath) && isString(v.importedAt);

export function isValidGraphBackupRecord(kind: "nodes" | "chains" | "sessions" | "coverage", value: unknown): boolean {
	switch (kind) {
		case "nodes": return isGraphNode(value);
		case "chains": return isGraphChain(value);
		case "sessions": return isCheckpoint(value);
		case "coverage": return isCoverageEntry(value);
	}
}
