import { NextResponse } from "next/server";

export async function POST(req: Request) {
  try {
    const apiKey = process.env.OPENROUTER_API_KEY;
    if (!apiKey) {
      return NextResponse.json(
        { fallback: true, reason: "No OPENROUTER_API_KEY configured" },
        { status: 200 }
      );
    }

    const body = (await req.json()) as {
      repoFullName?: string;
      repoDescription?: string;
      question?: string;
      contextHint?: string;
    };

    const repoFullName = body.repoFullName || "Aestheticsuraj234/devPilot";
    const question = body.question || "Explain the architecture of this repo.";

    const systemPrompt = [
      `You are DevPilot, an expert AI codebase assistant for the GitHub repository "${repoFullName}".`,
      body.repoDescription ? `Repository description: ${body.repoDescription}` : "",
      body.contextHint ? `Retrieved RAG context:\n${body.contextHint}` : "",
      "Provide a clear, well-structured Markdown answer with concise code examples and file/class references.",
    ]
      .filter(Boolean)
      .join("\n\n");

    const response = await fetch(
      "https://openrouter.ai/api/v1/chat/completions",
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${apiKey}`,
          "Content-Type": "application/json",
          "HTTP-Referer": "http://localhost:3000",
          "X-Title": "DevPilot RAG Assistant",
        },
        body: JSON.stringify({
          model: "openrouter/auto",
          messages: [
            { role: "system", content: systemPrompt },
            { role: "user", content: question },
          ],
          max_tokens: 700,
          temperature: 0.3,
        }),
      }
    );

    if (!response.ok) {
      return NextResponse.json(
        { fallback: true, reason: `OpenRouter status ${response.status}` },
        { status: 200 }
      );
    }

    const data = (await response.json()) as {
      choices?: Array<{ message?: { content?: string } }>;
    };
    const answer = data.choices?.[0]?.message?.content?.trim();

    if (!answer) {
      return NextResponse.json(
        { fallback: true, reason: "Empty completion" },
        { status: 200 }
      );
    }

    return NextResponse.json({ fallback: false, answer });
  } catch {
    return NextResponse.json(
      { fallback: true, reason: "Request error" },
      { status: 200 }
    );
  }
}
