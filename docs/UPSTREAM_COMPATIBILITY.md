# Upstream Compatibility Report — Freebuff Android

Governs every adapted contract between the Android host and upstream Freebuff/Codebuff.
Update this file whenever upstream changes (see `scripts/check-upstream.sh`).

## Pinned upstream

| Field | Value |
|---|---|
| Repository | https://github.com/CodebuffAI/freebuff |
| Branch audited | default (`main` snapshot) |
| Commit | `25f1d61530c6d58db394b59db12e97c113e9322d` |
| Commit date | 2026-09-27 20:19:46 +0000 |
| Commit subject | "Sync public snapshot from freebuff-private" |
| Runtime | Bun 1.3.14, TypeScript 5.5.4, ESM monorepo (zod v4, `ai` SDK) |

Repo map (from upstream `AGENTS.md`): `cli/` TUI client · `sdk/` JS/TS SDK · `common/` shared
types/tools/schemas · `agents/` public agent defs · `packages/agent-runtime/` runtime+tool handling ·
`packages/code-map/` source parsing · `packages/llm-providers/` provider shims (currently
`openai-compatible`) · `freebuff/` CLI+release+e2e · `evals/` buffbench.

## Canonical tool surface (source: `common/src/tools/constants.ts` @ pinned commit)

Upstream defines **37 named tools** plus 4 Composio meta tools. The Master Spec's §6 parity matrix is
*incomplete relative to this commit* — this table is the ground truth and lists where they diverge.

| Tool (upstream) | In spec §6? | Client-side (`clientToolCallSchema`)? | Android disposition |
|---|---|---|---|
| apply_patch | ✅ | ✅ host | Implement (file write via FileChangeSchema/patch) |
| add_subgoal | ✅ | ❌ server | Server-side; Android renders subgoal events |
| add_message | ✅ | ❌ server | Server-side; render |
| ask_user | ✅ | ✅ host | Implement (question UI + response) |
| browser_logs | ✅ | ✅ host | Implement (WebView/preview log capture) |
| code_search | ✅ | ✅ host | Implement (ripgrep-equivalent / tree-sitter) |
| cloud_plan_ready | ❌ **NEW** | ❌ server | **Spec gap** — accept + render, don't drop |
| create_plan | ✅ | ✅ host | Implement (FileChangeSchema plan) |
| end_turn | ✅ | ❌ server | Server lifecycle |
| find_files | ✅ | ❌ server | Server-side; Android provides file context |
| glob | ✅ | ✅ host | Implement |
| gravity_index | ✅ | ❌ server | Provider/hosted feature — capability-gate |
| list_directory | ✅ | ✅ host | Implement |
| lookup_agent_info | ❌ **NEW** | ❌ server | **Spec gap** — accept + render |
| propose_str_replace | ✅ | ❌ server | Server proposes; host applies via diff review |
| propose_write_file | ✅ | ❌ server | Server proposes; host applies |
| read_docs | ✅ | ❌ server | Server-side research |
| read_files | ✅ | ❌ server | Server requests → host returns read-files-response |
| read_subtree | ✅ | ❌ server | Server-side code-map |
| read_url | ✅ | ✅ host | Implement (network fetch + sanitize) |
| render_ui | ✅ | ❌ server | Server; host renders |
| report_project_profile | ❌ **NEW** | ❌ server | **Spec gap** — accept + render |
| run_file_change_hooks | ✅ | ✅ host | Implement (trust-gated) |
| run_terminal_command | ✅ | ✅ host | Implement (PTY/runtime — hardest; capability layer) |
| set_messages | ✅ | ❌ server | Server |
| set_output | ✅ | ❌ server | Server |
| skill | ✅ | ❌ server | Server; host surfaces skills |
| spawn_agents | ✅ | ❌ server | Server orchestration; host renders subagent tree |
| spawn_agent_inline | ✅ | ❌ server | **Upstream disabled** (commented out of `publishedTools`) |
| str_replace | ✅ | ✅ host | Implement (FileChangeSchema) |
| suggest_followups | ✅ | ❌ server | Server; render followups |
| task_completed | ✅ | ❌ server | Server lifecycle |
| think_deeply | ✅ | ❌ server | Server; render reasoning/status |
| update_subgoal | ✅ | ❌ server | Server; render |
| web_search | ✅ | ❌ server | Server-side research |
| write_file | ✅ | ✅ host | Implement (FileChangeSchema) |
| write_todos | ✅ | ❌ server | Server; render todo list |
| composio_manage_connections | ✅ | ✅ host | Trust-gated integration |
| composio_multi_execute_tool | ✅ | ✅ host | Trust-gated integration |
| composio_search_tools | ✅ | ✅ host | Trust-gated integration |
| composio_get_tool_schemas | ✅ | ✅ host | Trust-gated integration |

### The Android host contract = `clientToolCallSchema` (source: `common/src/tools/list.ts`)

These **16** tools are dispatched *to the client* and therefore MUST be implemented/executed by the
Android host (everything else runs in the server/runtime): `apply_patch`, `ask_user`, `browser_logs`,
`code_search`, `create_plan`, `glob`, `list_directory`, `run_file_change_hooks`, `read_url`,
`run_terminal_command` (adds `mode: 'assistant' | 'user'`), `str_replace`, `write_file`
(the last three use `FileChangeSchema`), plus the four `composio_*` meta tools.

`FileChangeSchema` (`common/src/actions.ts`): `{ type: 'patch' | 'file', path: string, content: string }`.

### Nuance found while implementing the host executor (2026-09-28)
`read_files` appears in the Master Spec §6 parity matrix and in `toolNames`, but it is **not** a member
of `clientToolCallSchema`. The host never receives a `read_files` *tool call*; instead the server emits
a `read-files-response` **ClientAction** (`common/src/actions.ts`: `{type:'read-files-response', files, requestId}`)
and the host supplies contents. The Android host therefore answers reads through
`HostToolExecutor.readFilesForAgent(...)`, and an invariant test asserts `read_files` is absent from
the client tool enum so upstream drift here fails loudly.

Tool-*param* shapes (what the model emits) also differ from the *client* shapes delivered to the host;
both are honored:
- `str_replace` params: `{path, replacements: [{oldString, newString, allowMultiple?}]}` (host receives a
  `FileChangeSchema`).
- `apply_patch` params: `{operation: {type: create_file|update_file|delete_file, path, diff}}` with
  Codex-style unified diff; result `{message, applied:[{file, action: add|update|delete}]}` or `{errorMessage}`.
- `run_terminal_command.timeout_seconds` clamped to `MAX=600s`, `-1` indefinite, `<=0` → `30`.

### Spec-vs-upstream divergences (must be reconciled in the Android build)
1. **Tools in upstream but missing from spec §6**: `cloud_plan_ready`, `lookup_agent_info`,
   `report_project_profile`. Android must recognize and render these — never silently drop (§26).
2. **`spawn_agent_inline`** is defined upstream but commented out of `publishedTools`. Android must
   not advertise it as a shipped capability; treat as not-published upstream (§19 "never fabricate").
3. **`gravity_index`** and provider features are hosted/backend concerns — Android exposes them via
   the RuntimeCapability layer (§11) rather than assuming local availability.
4. **`run_terminal_command.timeout_seconds`** is clamped server-side to `MAX=600s`, `-1` = indefinite,
   `<=0` → default `30`. The Android terminal adapter must honor identical semantics (§11).

## Client↔server protocol (source: `common/src/actions.ts`)

Transport is a bidirectional action stream (upstream: WebSocket). ClientAction discriminated union
observed at this commit includes: `prompt`, `init`, `read-files-response`, `tool-call-response`,
`cancel-user-input`, and (continued below in `docs/agent-protocol.md`). The AgentState/session-state
contract (`common/src/types/session-state.ts`) carries `messageHistory`, `subagents`, `childRunIds`,
`stepsRemaining`, `creditsUsed`, `contextTokenCount`, `contextTokenBaseline`, `toolDefinitions`.

See `docs/agent-protocol.md` for the full enumerated action set and `protocol-ref/` for an executable,
Node-runnable reference validator of `clientToolCallSchema`.

## Live backend contract (verified against the real server, 2026-09-28)

Source of truth: `cli/src/utils/freebuff-session-api.ts`, `common/src/constants/freebuff-models.ts`,
`sdk/src/impl/*` at `25f1d61`. Reimplemented in `:core:network`.

| Item | Upstream value | Android mirror |
|---|---|---|
| Base URL | `NEXT_PUBLIC_CODEBUFF_APP_URL` ‖ `https://codebuff.com` | `FreebuffProtocol.DEFAULT_BASE_URL` |
| Session status | `GET /api/v1/freebuff/session` → `{status}` | `FreebuffClient.sessionStatus()` |
| Session admission | `POST /api/v1/freebuff/session/admission` | `FreebuffClient.admitSession()` |
| Session reuse | `/api/v1/freebuff/session/reuse` | `SESSION_REUSE_PATH` |
| Agent turn | `POST /api/v1/chat/completions`, **SSE** stream | `FreebuffClient.streamChat()` + `SseParser` |
| Identity probe | `GET /api/v1/me` | `FreebuffClient.whoami()` |
| Auth | `Authorization: Bearer <api key>` | same; Keystore-stored, never logged |
| Wire headers | `x-freebuff-instance-id`, `x-freebuff-reuse-instance-id`, `x-freebuff-model`, `x-freebuff-wallet-spend-limit`, `x-freebuff-takeover-instance-id`, `x-freebuff-heartbeat`, `x-freebuff-compact-session` | `FreebuffProtocol.*_HEADER` |
| Session timeout | 20 000 ms | `SESSION_TIMEOUT_MS` |

**Retry-safety asymmetry preserved (upstream `classifyFreebuffSessionRequestFailure`)**: an
admission `POST` that returns no response may already have rotated the active instance, so it is
retried **only** on 408/429/503; other 4xx stop; 5xx-with-response is *unknown* (not blind-retried).
`GET` retries 408/429/5xx. Ported verbatim and covered by `RetryPolicyTest`.

**Live reachability evidence** (unauthenticated probes, no credentials used):
`GET /api/v1/freebuff/session` → `401 {"error":"unauthorized","message":"Missing or invalid Authorization header"}`;
`GET /api/v1/me` → `401`; `GET /api/v1/chat/completions` → `405`; `POST /api/v1/chat/completions` → `401`.
The Android client's error→state mapping (`RetryPolicy.stateFor`) matches these documented shapes.
Freebuff's built-in models are free/ad-supported, so the app needs no paid key for the hosted path.

## Unavoidable Android-specific implementations (to be kept current)
| Area | Android approach | Upstream equivalent | Risk |
|---|---|---|---|
| Terminal/PTY | JNI/NDK PTY + bundled runtime (proot/Termux-bridge) | node `child_process`/bash | High — capability detection required, never fake |
| Filesystem | SAF `DocumentFile` + WorkspaceFileSystem abstraction | Node `fs` | Med — desktop path semantics differ, canonicalization needed |
| Git | libgit2/JGit or isolated git CLI in runtime | shell `git` | Med |
| Secrets | Android Keystore | process env / api-keys dir | Med — must never leak to subprocess (§16) |
| WebView preview | contained WebView | browser-use agent | Med — isolate bridge |

## Change-detection
`scripts/check-upstream.sh` diffs the pinned commit's `common/src/tools/constants.ts`,
`common/src/tools/list.ts`, `common/src/types/session-state.ts`, and `common/src/actions.ts` against
upstream HEAD and fails loudly on added/removed tools or schema drift.
