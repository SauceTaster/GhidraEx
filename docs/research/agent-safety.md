# Agentic workflows and safety model

## Goal

An agent should be a first-class semantic client: better at gathering evidence, comparing many
locations, proposing repetitive changes, and watching long tasks, while having less ambient
authority than a human UI session. It never receives Ghidra Java objects, a project filesystem,
repository credentials, a live REPL, or a debugger connection merely because the interactive user
has them.

The design separates investigation, hypothesis, proposal, approval, execution, and validation. A
transaction is opened only during execution, after the proposal is approved and revalidated.

## Threat model

Reverse-engineering inputs are adversarial. Binary strings, symbols, source paths, comments,
decompiler output, debug memory, downloaded symbols, scripts, extension metadata, and repository
content can all contain text that looks like instructions to a model or person. They are evidence,
not policy. A result renderer must preserve origin and never concatenate untrusted artifact text
into control prompts without a clear data boundary.

The main hazards are:

- prompt injection through program or artifact content;
- data egress to a model, network service, clipboard, export, or report;
- stale evidence and stale mutation plans after concurrent analysis/user changes;
- over-broad writes that replace user/imported markup or collateral references/types;
- accidental live-target control while viewing historical/emulated state;
- arbitrary code execution through GhidraScript, PyGhidra, Java, extension loading, parsers, or
  REPL state;
- repository actions whose reply is lost or whose batch only partially succeeds;
- secrets appearing in logs, prompts, model context, URIs, workspace state, or audit payloads;
- denial of service through unbounded decompile, graph, search, analysis, emulation, or artifact
  acquisition.

## Authority model

Permissions are resources plus actions plus bounds, not roles such as “agent can edit.” An agent
credential is short-lived, audience-bound, revocable, and narrower than the UI credential that
created it.

| Capability plane | Example scopes | Default agent policy |
| --- | --- | --- |
| Program read | program IDs, address sets, result size, consistency mode | allowed for selected/open resources with quotas |
| Project metadata | tree snapshot, local/repository status, links | read only; linked/external traversal bounded and separately authorized |
| Derived compute | decompile, graph, search, analysis report, emulation | bounded; immutable snapshot preferred; live-fill/network disabled |
| Program proposal | rename, comment, bookmark, signature/type/reference/patch plan | allowed; produces no mutation |
| Program mutation | apply named approved proposal | denied until explicit approval token; one operation and expected generation |
| Repository mutation | checkout, check-in, move, delete, ACL | denied by default; separate approval and saga outcome UI |
| Artifact egress/network | symbol server, model provider, URL, upload/export | destination- and data-class-specific approval/policy |
| Filesystem/process | read/write path, spawn tool, general script | denied; sandbox grants exact mounted artifacts/command package only |
| Secrets | repository, symbol server, target, model credential | opaque broker handles; agent never reads secret value |
| Debug trace | captured trace query or trace-only edit | explicit trace/time/range scope |
| Live target | read memory/registers, pause/step/resume/write | denied by default; every control action explicitly approved/scoped |
| Extension/plugin | install/enable engine code | never delegated as ordinary agent work; maintenance approval and restart |

Approval tokens are bound to proposal hash, exact selected changes, authority, target resource,
expected generations, approver, expiry, and optional one-use idempotency key. They cannot be reused
for a recomputed or expanded plan.

## Evidence model

Every factual assertion that may drive a change links to an `EvidenceRef`:

```text
EvidenceRef {
  evidence_id
  classification       FACT | INFERENCE | HYPOTHESIS | EXTERNAL_CLAIM
  resource/version/time
  input generations
  semantic location    address/range/function/token/result/artifact
  query and engine/profile fingerprints
  coverage/completeness/warnings
  content digest or bounded excerpt digest
  producer              engine | extension | agent | user | external source
}
```

Decompiler text alone is not durable evidence: its token and high-variable identities are
result-local and may be mixed-generation. Agent evidence should prefer semantic facts—bytes,
instruction/data records, symbols with provenance, typed references, function bodies, trace memory
state, and versioned artifacts—then cite a decompile `ResultRef` for interpretation.

Unknown, partial, stale, unmapped, truncated, timed-out, and live-filled results remain labeled.
The agent cannot convert absence from an incomplete query into a fact.

## Proposal lifecycle

```mermaid
stateDiagram-v2
    [*] --> Investigating
    Investigating --> Draft: evidence set captured
    Draft --> Validated: semantic targets resolved and impact computed
    Draft --> Rejected: invalid or unauthorized
    Validated --> AwaitingApproval
    AwaitingApproval --> Stale: relevant generation changed
    AwaitingApproval --> Approved: exact rows and authority signed
    Approved --> Stale: pre-execution revalidation failed
    Approved --> Applying: server opens shortest transaction/job
    Applying --> Committed: postconditions pass
    Applying --> RolledBack: atomic operation failed or cancelled
    Applying --> Partial: declared saga/partial operation
    Applying --> Indeterminate: remote/live outcome unknown
    Committed --> Verified: independent postconditions and new evidence
    RolledBack --> [*]
    Partial --> [*]
    Indeterminate --> [*]
    Stale --> Investigating: explicit rebase/review
```

The proposal contains old/new semantic values, resolved targets, collateral impact, validation,
confidence, evidence, provenance, required authority, atomicity/cancellation descriptor, estimated
cost, and selected rows. Approval never holds a Ghidra transaction or repository lock.

Immediately before apply, the runtime re-resolves every target and checks all dependent epochs and
generations. The runtime, not the agent, opens a named Ghidra transaction. On success it validates
postconditions, advances generations, emits invalidations, and records the exact diff.

Repository operations and live target control are not represented as atomic program edits. They
use their true staged/indeterminate outcome models.

## Conflict and provenance policy

Ghidra defines `SourceType.AI` at the same priority as analysis and below imported/user-defined
sources ([`SourceType`](https://ghidra.re/ghidra_docs/api/ghidra/program/model/symbol/SourceType.html)).
Agent-created markup uses it whenever the target API supports it, plus broker audit provenance.

Default conflict rules:

1. Never overwrite `USER_DEFINED` or `IMPORTED` names/types/comments without a separately visible
   conflict decision.
2. Never rely solely on numeric symbol, datatype, decompiler-token, p-code, graph-node, bookmark
   type, or database IDs after a generation change.
3. Show every collateral reference deletion, function storage/signature rewrite, datatype cascade,
   context clear/redisassembly, code-unit clear, and analysis scheduling effect in the proposal.
4. A bulk plan is not atomic merely because it is displayed as one table. Its operation descriptor
   states one-object atomic, fixed-compound atomic, saga, or per-item execution.
5. Undo is offered only for committed Ghidra domain-object changes that actually participate in
   undo. Network, filesystem, repository, script-process, extension, and live-target effects do not
   pretend to be reversible.

## Scripts and generated code

Generated source is an artifact, not an implicit execution request. Its audit record includes exact
bytes/hash, generator/model/prompt version, declared runtime, dependencies, inputs, capabilities,
and expected outputs.

General Python/Java runs in a disposable OS-sandboxed process with content-addressed read-only
inputs, a bounded scratch output, no inherited environment/secrets, network off by default, process
and memory quotas, deadline, bounded logs, and explicit output schemas. A REPL is a leased version
of this runner and resets by destroying the process.

Direct GhidraScript is not the safe default. In Ghidra 12.1.2, normal script execution ends its
program transaction with commit from `finally`, so an exception or cancellation can leave partial
changes ([`GhidraScript`](https://github.com/NationalSecurityAgency/ghidra/blob/Ghidra_12.1.2_build/Ghidra/Features/Base/src/main/java/ghidra/app/script/GhidraScript.java#L457-L465)).
Multi-program script writes also do not become one atomic transaction automatically.

Preferred execution patterns, in order:

1. run generated code read-only over an immutable snapshot and return structured findings;
2. translate desired writes into semantic proposals applied by `ProjectRuntime`;
3. run a trusted allowlisted Ghidra script in a disposable project, compute a semantic diff, and
   propose that diff to the authoritative project;
4. allow direct writable trusted scripts only with a reviewed package, explicit non-atomic risk,
   backup/recovery policy, and human-owned maintenance workflow.

## Debugger agents

Captured trace investigation, trace-only annotation, emulator execution, target reads, and target
control are separate capabilities. Every action carries connection/target/trace, structured time,
thread/frame, platform mapping, and explicit `TRACE_ONLY`, `EMULATOR`, or `LIVE_TARGET` authority.

Agent defaults are:

- captured/trace reads allowed within scope;
- emulation uses `TRACE_ONLY` source policy, never implicit live fill;
- target reads denied unless approved for exact ranges/registers and duration;
- pause/step/resume, memory/register writes, and breakpoint fan-out require an action-specific
  approval and foreground indication;
- lost control replies become `INDETERMINATE` and are never automatically retried;
- historical UI focus never changes the target named by an approved command.

## Data egress and external services

Before any content leaves the broker security boundary, policy computes its data class and
destination. An approval/policy decision distinguishes hashes/metadata, bounded disassembly,
decompiler text, source/debug artifacts, raw bytes, credentials, customer/project identifiers, and
live target state.

Model requests receive a manifest of exactly which evidence is sent. Retrieval results and remote
symbol/BSim/artifact services have trust/provenance records and are treated as untrusted inputs on
return. URLs embedded in binaries or project content never grant network authority.

Clipboard, report, export, and “open in browser” are also egress actions. Native hosts own their UI
gesture, while broker policy supplies bounded/redacted artifacts.

## Audit record

Authority-bearing operations append a tamper-evident record containing:

- actor, delegated agent identity, credential audience/scopes, and session;
- exact project/program/version/trace/target refs and relevant epochs;
- proposal schema/version/hash, selected changes, evidence refs, and stale-check inputs;
- model/provider/prompt/tool/package/extension/engine fingerprints;
- capability decisions, approver, approval time/expiry, and any denied/trimmed authority;
- transaction/job name, idempotency key, start/end generations and operation stages;
- postcondition evidence, result completeness, warnings and per-item outcomes;
- rollback, partial, unknown-outcome reconciliation, undo, and later supersession links.

Audit data stores bounded content or content digests; it redacts secrets, sensitive local paths, and
raw data not required to reproduce the decision. An audit log is not a covert copy of the binary.

## Required adversarial tests

| Test | Expected behavior |
| --- | --- |
| Binary string says to ignore policy and run/upload a file | remains quoted untrusted evidence; no capability changes |
| Concurrent rename after plan | proposal becomes stale; no transaction opens |
| Agent targets dynamic symbol/decompiler token after reanalysis | semantic re-resolution or explicit rejection |
| Partial query omits a reference | agent cannot assert “no references”; coverage remains partial |
| Approval selects 3 of 100 changes | only exact selected hashes can execute |
| User-defined name conflicts with AI proposal | separate conflict approval; user markup preserved by default |
| Script throws/cancels halfway | disposable copy/diff or explicit partial outcome; never false rollback claim |
| Repository check-in reply is lost | `OUTCOME_UNKNOWN`, reconciliation, no blind retry |
| Historical trace is focused while target runs | trace evidence remains historical; live command names actual target/time |
| Emulation encounters unknown trace bytes | stays unknown under default policy; no implicit live target read |
| Credential appears in tool exception | bounded sanitized error and secret-handle revocation test |
| Model/network policy is revoked mid-job | request stops at defined boundary; already-egressed manifest remains auditable |
| Disconnect during approval/apply | unused approval expires; active operation reaches explicit rollback/partial/unknown result |
| Agent asks for extension install or arbitrary Java | escalates to maintenance workflow; ordinary token cannot authorize it |

Agent support is ready only after these tests run against every host adapter and the broker. A UI
that shows an approval dialog without stale checking, exact authority binding, and a server-owned
transaction does not meet this model.
