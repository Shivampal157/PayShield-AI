import type {
  ChatMessage,
  ChatSession,
  Citation,
  IndexStatusResponse,
  Repository,
  User,
} from "@/lib/api";

const STORAGE_KEYS = {
  REPOS: "devpilot_demo_repos_v1",
  SESSIONS: "devpilot_demo_sessions_v1",
  MESSAGES: "devpilot_demo_messages_v1",
  INDEX_TIMERS: "devpilot_demo_index_timers_v1",
};

export const DEMO_USER: User = {
  id: "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  githubId: 157001,
  githubUsername: "Shivampal157",
  displayName: "Shivam Pal",
  avatarUrl: "https://avatars.githubusercontent.com/u/9919?v=4",
};

const INITIAL_REPOS: Repository[] = [
  {
    id: "11111111-2222-3333-4444-555555555501",
    githubRepoId: 901001,
    owner: "Aestheticsuraj234",
    name: "devPilot",
    fullName: "Aestheticsuraj234/devPilot",
    isPrivate: false,
    defaultBranch: "main",
    language: "Java",
    htmlUrl: "https://github.com/Aestheticsuraj234/devPilot",
    description:
      "An AI-powered GitHub repository assistant for exploring codebases through context-aware RAG chat, pgvector embeddings, and SSE source citations.",
    indexStatus: "READY",
    indexedAt: new Date(Date.now() - 1000 * 60 * 14).toISOString(),
    chunkCount: 342,
    filesTotal: 54,
    filesProcessed: 54,
    errorMessage: null,
  },
  {
    id: "11111111-2222-3333-4444-555555555502",
    githubRepoId: 901002,
    owner: "Shivampal157",
    name: "PayShield-AI",
    fullName: "Shivampal157/PayShield-AI",
    isPrivate: false,
    defaultBranch: "main",
    language: "Java",
    htmlUrl: "https://github.com/Shivampal157/PayShield-AI",
    description:
      "Real-time Fraud Detection & ACID Payment Ledger built with Spring Boot, SHA-256 tamper-evident hash chain, and risk velocity scoring.",
    indexStatus: "READY",
    indexedAt: new Date(Date.now() - 1000 * 60 * 42).toISOString(),
    chunkCount: 268,
    filesTotal: 41,
    filesProcessed: 41,
    errorMessage: null,
  },
  {
    id: "11111111-2222-3333-4444-555555555503",
    githubRepoId: 901003,
    owner: "spring-projects",
    name: "spring-ai",
    fullName: "spring-projects/spring-ai",
    isPrivate: false,
    defaultBranch: "main",
    language: "Java",
    htmlUrl: "https://github.com/spring-projects/spring-ai",
    description:
      "Spring AI framework for AI engineering: ChatClient, VectorStore (PGVector), Embeddings, Advisors, and tool calling.",
    indexStatus: "PENDING",
    indexedAt: null,
    chunkCount: 0,
    filesTotal: 45,
    filesProcessed: 0,
    errorMessage: null,
  },
  {
    id: "11111111-2222-3333-4444-555555555504",
    githubRepoId: 901004,
    owner: "vercel",
    name: "next.js",
    fullName: "vercel/next.js",
    isPrivate: false,
    defaultBranch: "canary",
    language: "TypeScript",
    htmlUrl: "https://github.com/vercel/next.js",
    description:
      "The React Framework for the Web — App Router, React 19 Server Components, Proxy middleware, and streaming UI.",
    indexStatus: "PENDING",
    indexedAt: null,
    chunkCount: 0,
    filesTotal: 60,
    filesProcessed: 0,
    errorMessage: null,
  },
];

const INITIAL_SESSION_ID = "aaaa1111-bbbb-2222-cccc-333333333301";

const INITIAL_SESSIONS: ChatSession[] = [
  {
    id: INITIAL_SESSION_ID,
    repositoryId: "11111111-2222-3333-4444-555555555501",
    title: "RAG Pipeline & Architecture Overview",
    createdAt: new Date(Date.now() - 1000 * 60 * 10).toISOString(),
  },
];

const INITIAL_MESSAGES: Record<string, ChatMessage[]> = {
  [INITIAL_SESSION_ID]: [
    {
      id: "msg-init-user-1",
      role: "USER",
      content: "How does DevPilot index a GitHub repository and stream RAG answers?",
      citations: [],
      createdAt: new Date(Date.now() - 1000 * 60 * 9).toISOString(),
    },
    {
      id: "msg-init-assistant-1",
      role: "ASSISTANT",
      content: `**DevPilot** uses a two-phase **Retrieval-Augmented Generation (RAG)** pipeline built with **Spring Boot 4.1**, **Spring AI 2.0**, and **PostgreSQL + pgvector**:

### 1. Asynchronous Code Indexing (\`IndexingService\`)
When you trigger \`POST /api/repos/{repoId}/index\`, \`IndexingService.startIndexing()\` marks the repository as \`INDEXING\` and runs \`indexAsync()\` on the \`indexingExecutor\` thread pool:
- Fetches the repository Git tree via \`GithubApiClient.getRepoTree()\`.
- Filters eligible source code files using \`CodeFileFilter\` (skipping binaries, lockfiles, \`node_modules\`, and files over \`100 KB\`).
- Splits each file into line-aware overlapping chunks (\`800\` chars) with \`CodeChunker.chunkFile()\`, attaching \`repoId\`, \`filePath\`, \`startLine\`, \`endLine\`, and \`language\` metadata.
- Stores vector embeddings in batches of \`32\` into **PostgreSQL \`pgvector\`** via \`VectorStore.add(batch)\`.

\`\`\`java
var filter = new FilterExpressionBuilder()
        .eq(RagSettings.METADATA_REPO_ID, repositoryId.toString())
        .build();

var search = SearchRequest.builder()
        .query(question)
        .topK(RagSettings.TOP_K_CHUNKS)
        .filterExpression(filter)
        .build();
\`\`\`

### 2. Context Retrieval & SSE Token Streaming (\`ChatService\` & \`ChatStreamHandler\`)
When you send a message to \`POST /api/chat/sessions/{sessionId}/messages\`:
1. \`CodeContextRetriever.retrieve()\` performs a cosine similarity search in \`pgvector\` filtered by \`repoId\` (\`TOP_K_CHUNKS = 8\`).
2. \`ChatPromptBuilder\` injects the matched code chunks and file line numbers into the system/user prompts.
3. \`ChatStreamHandler.stream()\` streams tokens over **Server-Sent Events (\`SseEmitter\`)** with \`user_message\`, \`token\`, \`assistant_message\` (including deduplicated \`CitationDto\` chips), and \`done\` events.`,
      citations: [
        {
          filePath:
            "backend/src/main/java/devPilot/backend/services/indexing/IndexingService.java",
          startLine: 64,
          endLine: 118,
          language: "java",
        },
        {
          filePath:
            "backend/src/main/java/devPilot/backend/services/ai/CodeContextRetriever.java",
          startLine: 22,
          endLine: 48,
          language: "java",
        },
        {
          filePath:
            "backend/src/main/java/devPilot/backend/services/ai/ChatStreamHandler.java",
          startLine: 32,
          endLine: 70,
          language: "java",
        },
      ],
      createdAt: new Date(Date.now() - 1000 * 60 * 9 + 4000).toISOString(),
    },
  ],
};

function isBrowser() {
  return typeof window !== "undefined";
}

function readStorage<T>(key: string, fallback: T): T {
  if (!isBrowser()) return fallback;
  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) {
      window.localStorage.setItem(key, JSON.stringify(fallback));
      return fallback;
    }
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

function writeStorage<T>(key: string, value: T): void {
  if (!isBrowser()) return;
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // ignore storage quota errors
  }
}

function advanceIndexingProgress(repos: Repository[]): Repository[] {
  if (!isBrowser()) return repos;
  const timers = readStorage<Record<string, number>>(
    STORAGE_KEYS.INDEX_TIMERS,
    {}
  );
  let changed = false;
  const now = Date.now();

  const updated = repos.map((repo) => {
    if (repo.indexStatus !== "INDEXING") return repo;

    const startedAt = timers[repo.id] ?? now - 1500;
    if (!timers[repo.id]) {
      timers[repo.id] = startedAt;
    }

    const elapsedMs = Math.max(500, now - startedAt);
    const durationMs = 5200; // ~5.2 seconds full indexing animation
    const ratio = Math.min(1, elapsedMs / durationMs);
    const totalFiles = repo.filesTotal > 0 ? repo.filesTotal : 48;
    const processedFiles = Math.min(
      totalFiles,
      Math.max(1, Math.round(totalFiles * ratio))
    );
    const chunks = processedFiles * 6;

    changed = true;
    if (ratio >= 1) {
      delete timers[repo.id];
      return {
        ...repo,
        indexStatus: "READY" as const,
        filesTotal: totalFiles,
        filesProcessed: totalFiles,
        chunkCount: chunks,
        indexedAt: new Date().toISOString(),
        errorMessage: null,
      };
    }

    return {
      ...repo,
      filesTotal: totalFiles,
      filesProcessed: processedFiles,
      chunkCount: chunks,
    };
  });

  if (changed) {
    writeStorage(STORAGE_KEYS.INDEX_TIMERS, timers);
    writeStorage(STORAGE_KEYS.REPOS, updated);
  }

  return updated;
}

export const demoEngine = {
  getUser(): User {
    return DEMO_USER;
  },

  async listRepos(refresh = false): Promise<Repository[]> {
    let repos = readStorage<Repository[]>(STORAGE_KEYS.REPOS, INITIAL_REPOS);
    repos = advanceIndexingProgress(repos);

    if (refresh && isBrowser()) {
      try {
        const ghRes = await fetch(
          "https://api.github.com/users/Shivampal157/repos?sort=updated&per_page=8"
        );
        if (ghRes.ok) {
          const ghRepos = (await ghRes.json()) as Array<{
            id: number;
            name: string;
            full_name: string;
            private: boolean;
            default_branch: string;
            language: string | null;
            html_url: string;
            description: string | null;
            owner: { login: string };
          }>;
          const existingNames = new Set(
            repos.map((r) => r.fullName.toLowerCase())
          );
          for (const gh of ghRepos) {
            if (!existingNames.has(gh.full_name.toLowerCase())) {
              repos.push({
                id: `gh-${gh.id}`,
                githubRepoId: gh.id,
                owner: gh.owner?.login || "Shivampal157",
                name: gh.name,
                fullName: gh.full_name,
                isPrivate: Boolean(gh.private),
                defaultBranch: gh.default_branch || "main",
                language: gh.language || "Java",
                htmlUrl: gh.html_url,
                description:
                  gh.description ||
                  `Synced from GitHub repository ${gh.full_name}`,
                indexStatus: "PENDING",
                indexedAt: null,
                chunkCount: 0,
                filesTotal: 36,
                filesProcessed: 0,
                errorMessage: null,
              });
            }
          }
          writeStorage(STORAGE_KEYS.REPOS, repos);
        }
      } catch {
        // Ignore rate-limit or offline errors on public GitHub sync
      }
    }

    return repos;
  },

  getRepo(id: string): Repository {
    const repos = advanceIndexingProgress(
      readStorage<Repository[]>(STORAGE_KEYS.REPOS, INITIAL_REPOS)
    );
    return (
      repos.find((r) => r.id === id) ?? {
        ...INITIAL_REPOS[0],
        id,
      }
    );
  },

  startIndex(id: string): Repository {
    const repos = readStorage<Repository[]>(STORAGE_KEYS.REPOS, INITIAL_REPOS);
    const timers = readStorage<Record<string, number>>(
      STORAGE_KEYS.INDEX_TIMERS,
      {}
    );
    timers[id] = Date.now();
    writeStorage(STORAGE_KEYS.INDEX_TIMERS, timers);

    const updated = repos.map((repo) => {
      if (repo.id !== id) return repo;
      const total = repo.filesTotal > 0 ? repo.filesTotal : 45;
      return {
        ...repo,
        indexStatus: "INDEXING" as const,
        filesTotal: total,
        filesProcessed: 3,
        chunkCount: 18,
        errorMessage: null,
      };
    });

    writeStorage(STORAGE_KEYS.REPOS, updated);
    return updated.find((r) => r.id === id) ?? INITIAL_REPOS[0];
  },

  indexStatus(id: string): IndexStatusResponse {
    const repo = this.getRepo(id);
    return {
      repositoryId: repo.id,
      indexStatus: repo.indexStatus,
      filesTotal: repo.filesTotal,
      filesProcessed: repo.filesProcessed,
      chunkCount: repo.chunkCount,
      indexedAt: repo.indexedAt,
      errorMessage: repo.errorMessage,
    };
  },

  listSessions(repositoryId: string): ChatSession[] {
    const sessions = readStorage<ChatSession[]>(
      STORAGE_KEYS.SESSIONS,
      INITIAL_SESSIONS
    );
    return sessions
      .filter((s) => s.repositoryId === repositoryId)
      .sort(
        (a, b) =>
          new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
      );
  },

  createSession(repositoryId: string, title?: string): ChatSession {
    const repo = this.getRepo(repositoryId);
    const sessions = readStorage<ChatSession[]>(
      STORAGE_KEYS.SESSIONS,
      INITIAL_SESSIONS
    );
    const newSession: ChatSession = {
      id: `sess-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      repositoryId,
      title:
        title && title.trim() ? title.trim() : `Chat with ${repo.fullName}`,
      createdAt: new Date().toISOString(),
    };
    sessions.unshift(newSession);
    writeStorage(STORAGE_KEYS.SESSIONS, sessions);
    return newSession;
  },

  getMessages(sessionId: string): ChatMessage[] {
    const allMessages = readStorage<Record<string, ChatMessage[]>>(
      STORAGE_KEYS.MESSAGES,
      INITIAL_MESSAGES
    );
    return allMessages[sessionId] ?? [];
  },

  saveMessage(sessionId: string, message: ChatMessage): void {
    const allMessages = readStorage<Record<string, ChatMessage[]>>(
      STORAGE_KEYS.MESSAGES,
      INITIAL_MESSAGES
    );
    const list = allMessages[sessionId] ?? [];
    allMessages[sessionId] = [...list, message];
    writeStorage(STORAGE_KEYS.MESSAGES, allMessages);
  },

  buildRagResponse(
    sessionId: string,
    question: string
  ): { answer: string; citations: Citation[] } {
    const sessions = readStorage<ChatSession[]>(
      STORAGE_KEYS.SESSIONS,
      INITIAL_SESSIONS
    );
    const session = sessions.find((s) => s.id === sessionId);
    const repo = session ? this.getRepo(session.repositoryId) : INITIAL_REPOS[0];
    const q = question.toLowerCase();

    if (repo.name.toLowerCase().includes("payshield")) {
      return {
        answer: `In **${repo.fullName}**, payments and fraud telemetry are processed through an **ACID-compliant ledger** and real-time risk evaluation pipeline:

### Key Implementation Highlights
1. **Risk Scoring & Velocity Rules**: Incoming transactions are scored against velocity thresholds, counterparty blacklists, and amount anomalies before settlement.
2. **SHA-256 Hash Chain Integrity**: Every ledger entry computes a cryptographic hash linking to the previous record so any tampering is immediately detected.
3. **Quarantined Alerts & Review Queue**: High-risk transactions (\`riskScore >= 0.85\`) are automatically quarantined with status \`FLAGGED_FRAUD\` or \`REVIEW_REQUIRED\`.

\`\`\`java
// Tamper-evident SHA-256 ledger verification
String payload = previousHash + ":" + transactionId + ":" + amount + ":" + currency;
String currentHash = DigestUtils.sha256Hex(payload);
\`\`\`

All REST endpoints are protected with Spring Security JWT/Cookie authentication and exposed to the executive dashboard.`,
        citations: [
          {
            filePath: "src/main/resources/static/index.html",
            startLine: 1,
            endLine: 120,
            language: "html",
          },
          {
            filePath: "Dockerfile",
            startLine: 1,
            endLine: 28,
            language: "dockerfile",
          },
        ],
      };
    }

    if (
      q.includes("auth") ||
      q.includes("oauth") ||
      q.includes("login") ||
      q.includes("security") ||
      q.includes("token")
    ) {
      return {
        answer: `Authentication in **${repo.fullName}** uses **Spring Security OAuth2 Client** with GitHub OAuth 2.0 (` +
          "`read:user,user:email,repo` scopes) and encrypted GitHub access tokens:\n\n" +
          `### 1. OAuth2 Login & Principal Creation (\`GitHubOAuth2UserService\`)
When GitHub redirects back to \`/login/oauth2/code/github\`, \`GitHubOAuth2UserService.loadUser()\` fetches the user's profile and primary email, encrypts the GitHub OAuth access token using \`TextEncryptor\` (\`CryptoConfig\`), and upserts the \`User\` entity in PostgreSQL.

\`\`\`java
@Bean
TextEncryptor tokenEncryptor(
        @Value("\${app.token-encryptor-password}") String password,
        @Value("\${app.token-encryptor-salt}") String salt) {
    return Encryptors.text(password, salt);
}
\`\`\`

### 2. Session Cookie & Frontend Callback (\`SecurityConfig\`)
Upon successful authentication, \`SecurityConfig\` redirects the browser to \`\${app.frontend.url}/auth/callback\`, where the Next.js frontend sets the \`devpilot_auth=1\` marker cookie and hydrates the current user via \`GET /api/auth/me\`.`,
        citations: [
          {
            filePath:
              "backend/src/main/java/devPilot/backend/config/SecurityConfig.java",
            startLine: 34,
            endLine: 86,
            language: "java",
          },
          {
            filePath:
              "backend/src/main/java/devPilot/backend/security/GitHubOAuth2UserService.java",
            startLine: 28,
            endLine: 92,
            language: "java",
          },
          {
            filePath:
              "backend/src/main/java/devPilot/backend/config/CryptoConfig.java",
            startLine: 10,
            endLine: 18,
            language: "java",
          },
        ],
      };
    }

    if (
      q.includes("chunk") ||
      q.includes("index") ||
      q.includes("embed") ||
      q.includes("vector") ||
      q.includes("pgvector")
    ) {
      return {
        answer: `Repository indexing in **${repo.fullName}** is handled asynchronously by \`IndexingService\`, \`CodeFileFilter\`, and \`CodeChunker\`:

### Step-by-Step Indexing Flow
1. **File Filtering (\`CodeFileFilter\`)**: Filters out binary files, build artifacts (\`target/\`, \`node_modules/\`, \`.next/\`), lockfiles, and files larger than \`app.indexing.max-file-bytes\` (\`100 KB\`).
2. **Line-Aware Chunking (\`CodeChunker\`)**: Splits each source file into chunks of up to \`800\` characters while tracking exact \`startLine\` and \`endLine\` numbers so citations can link directly to GitHub line ranges.
3. **Batch Vector Storage (\`IndexingService\`)**: Sends batches of \`32\` Spring AI \`Document\` objects to \`VectorStore.add(batch)\`, which generates embeddings and persists them in PostgreSQL \`vector_store\` (\`pgvector\` HNSW index).

\`\`\`java
Map<String, Object> metadata = Map.of(
    RagSettings.METADATA_REPO_ID, repoId,
    "filePath", filePath,
    "startLine", startLine,
    "endLine", endLine,
    "language", language
);
\`\`\``,
        citations: [
          {
            filePath:
              "backend/src/main/java/devPilot/backend/services/indexing/CodeChunker.java",
            startLine: 20,
            endLine: 95,
            language: "java",
          },
          {
            filePath:
              "backend/src/main/java/devPilot/backend/services/indexing/CodeFileFilter.java",
            startLine: 15,
            endLine: 78,
            language: "java",
          },
          {
            filePath:
              "backend/src/main/java/devPilot/backend/services/indexing/IndexingService.java",
            startLine: 74,
            endLine: 118,
            language: "java",
          },
        ],
      };
    }

    return {
      answer: `Based on the indexed codebase of **${repo.fullName}**, here is how the system handles **"${question}"**:

### Architecture & Code Flow
- **Controllers Layer**: REST endpoints in \`RepoController\`, \`ChatController\`, and \`AuthController\` validate the authenticated principal (\`@CurrentUser AppUserPrincipal\`) and delegate to domain services.
- **RAG Retrieval (\`CodeContextRetriever\`)**: Queries PostgreSQL \`pgvector\` for the top \`8\` most semantically relevant code chunks matching your question, scoped strictly to \`repoId = ${repo.id}\`.
- **Streaming Generation (\`ChatStreamHandler\`)**: Uses Spring AI's \`ChatClient\` reactive stream (\`.stream().content()\`) and forwards each token over Server-Sent Events (\`text/event-stream\`) to the Next.js \`streamChatMessage()\` reader.

\`\`\`java
ChatClient.builder(chatModel)
        .build()
        .prompt()
        .system(systemPrompt)
        .user(userPrompt)
        .stream()
        .content()
        .doOnNext(token -> appendToken(emitter, fullReply, token))
\`\`\`

Click any of the source citation chips below to inspect the exact lines on GitHub.`,
      citations: [
        {
          filePath:
            "backend/src/main/java/devPilot/backend/controllers/ChatController.java",
          startLine: 28,
          endLine: 68,
          language: "java",
        },
        {
          filePath:
            "backend/src/main/java/devPilot/backend/services/ChatService.java",
          startLine: 91,
          endLine: 120,
          language: "java",
        },
        {
          filePath: "client/lib/stream-chat.ts",
          startLine: 12,
          endLine: 90,
          language: "typescript",
        },
      ],
    };
  },
};
