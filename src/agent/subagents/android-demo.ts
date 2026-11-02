import { createConnection } from "node:net";
import type { DemoRunResult, RunDemoOptions } from "./headless.ts";

/** The Android host owns an isolated WebView; its private socket never enters app HTML. */
export function runAndroidDemo(socketName: string, html: string, opts: RunDemoOptions = {}): Promise<DemoRunResult> {
	if (!/^lh-demo-[a-f0-9]{64}$/.test(socketName)) return Promise.reject(new Error("Invalid Android demo socket"));
	const payload = JSON.stringify({ html, ...opts });
	if (Buffer.byteLength(payload) > 4 * 1024 * 1024) return Promise.reject(new Error("Demo exceeds the 4 MB execution limit"));
	return new Promise((resolve, reject) => {
		const socket = createConnection({ path: `\0${socketName}` });
		const chunks: Buffer[] = [];
		let bytes = 0;
		socket.setTimeout(45_000, () => socket.destroy(new Error("Android demo timed out")));
		socket.once("error", reject);
		socket.once("connect", () => socket.end(payload));
		socket.on("data", (chunk: Buffer) => {
			bytes += chunk.length;
			if (bytes > 1024 * 1024) socket.destroy(new Error("Android demo response is too large"));
			else chunks.push(chunk);
		});
		socket.once("end", () => {
			try {
				const result = JSON.parse(Buffer.concat(chunks).toString("utf8"));
				if (!result || typeof result.ok !== "boolean" || !["consoleErrors", "pageErrors", "failedRequests"].every(key => Array.isArray(result[key]) && result[key].every((value: unknown) => typeof value === "string"))) throw new Error("Invalid Android demo response");
				resolve(result as DemoRunResult);
			} catch (error) { reject(error); }
		});
	});
}
