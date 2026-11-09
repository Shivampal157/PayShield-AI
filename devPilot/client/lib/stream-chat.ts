import {
  getApiBaseUrl,
  isLiveBackendConfigured,
  ApiError,
  type ChatMessage,
} from "@/lib/api";
import { demoEngine } from "@/lib/demo-engine";

export type StreamChatHandlers = {
  onUserMessage?: (message: ChatMessage) => void;
  onToken?: (token: string) => void;
  onAssistantMessage?: (message: ChatMessage) => void;
  onDone?: () => void;
  onError?: (error: Error) => void;
  signal?: AbortSignal;
};

async function streamDemoMessage(
  sessionId: string,
  content: string,
  handlers: StreamChatHandlers
): Promise<void> {
  const userMessage: ChatMessage = {
    id: `msg-u-${Date.now()}`,
    role: "USER",
    content,
    citations: [],
    createdAt: new Date().toISOString(),
  };

  demoEngine.saveMessage(sessionId, userMessage);
  handlers.onUserMessage?.(userMessage);

  const { answer, citations } = demoEngine.buildRagResponse(sessionId, content);
  const tokens = answer.match(/\S+\s*|\n+/g) ?? [answer];

  for (const token of tokens) {
    if (handlers.signal?.aborted) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 16));
    if (handlers.signal?.aborted) {
      return;
    }
    handlers.onToken?.(token);
  }

  const assistantMessage: ChatMessage = {
    id: `msg-a-${Date.now()}`,
    role: "ASSISTANT",
    content: answer,
    citations,
    createdAt: new Date().toISOString(),
  };

  demoEngine.saveMessage(sessionId, assistantMessage);
  handlers.onAssistantMessage?.(assistantMessage);
  handlers.onDone?.();
}

export async function streamChatMessage(
  sessionId: string,
  content: string,
  handlers: StreamChatHandlers = {}
): Promise<void> {
  if (!isLiveBackendConfigured()) {
    await streamDemoMessage(sessionId, content, handlers);
    return;
  }

  let res: Response;
  try {
    res = await fetch(
      `${getApiBaseUrl()}/api/chat/sessions/${sessionId}/messages`,
      {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ content }),
        signal: handlers.signal,
      }
    );
  } catch {
    await streamDemoMessage(sessionId, content, handlers);
    return;
  }

  if (!res.ok) {
    let message = res.statusText;
    try {
      const data = await res.json();
      message = data.message ?? data.error ?? message;
    } catch {
      // ignore
    }
    throw new ApiError(res.status, message);
  }

  if (!res.body) {
    throw new Error("No response body for SSE stream");
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const parts = buffer.split("\n\n");
    buffer = parts.pop() ?? "";

    for (const part of parts) {
      if (!part.trim()) continue;

      const lines = part.split("\n");
      let event = "message";
      const dataLines: string[] = [];

      for (const line of lines) {
        if (line.startsWith("event:")) {
          event = line.slice(6).trim();
        } else if (line.startsWith("data:")) {
          dataLines.push(line.slice(5).trimStart());
        }
      }

      const data = dataLines.join("\n");
      if (!data) continue;

      try {
        if (event === "token") {
          handlers.onToken?.(JSON.parse(data) as string);
        } else if (event === "user_message") {
          handlers.onUserMessage?.(JSON.parse(data) as ChatMessage);
        } else if (event === "assistant_message") {
          handlers.onAssistantMessage?.(JSON.parse(data) as ChatMessage);
        } else if (event === "done") {
          handlers.onDone?.();
        } else if (event === "error") {
          throw new Error(data);
        }
      } catch (err) {
        const error =
          err instanceof Error ? err : new Error("Failed to parse SSE event");
        handlers.onError?.(error);
        if (event === "error") throw error;
      }
    }
  }

  handlers.onDone?.();
}
