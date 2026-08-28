# WebHTV Agent Contract

This file applies to the whole repository. Keep work correct, narrow, reversible, and fast. A nested `AGENTS.md` may add path-specific rules but may not weaken the safety, scope, time, or rollback rules here.

## 1. Start with a bounded lane

Before the first edit, state one completion sentence, the allowed paths, protected pre-existing dirty paths, and the cheapest decisive verification. Use the smallest applicable lane:

### Governance-maintenance fast path

When the task only edits `AGENTS.md`, `.codex/skills/**`, `.codex/scripts/**`, or their review document:

- Diagnose from the user's observed failure and the current diff only. Do not search the web, reread general best-practice sources, forward-test, create a temporary repository, or expand the methodology unless the user explicitly asks or one concrete unresolved fact blocks the edit.
- Select one root cause, apply one bounded patch, then run exactly one combined validation pass covering only changed artifacts. If it passes, stop immediately and hand off; do not perform reassurance checks.
- Do not update the same rule in every layer by default. Put the decision rule in `AGENTS.md`, domain-only detail in the Skill, and deterministic behavior in the script. Update another layer only when its behavior would otherwise contradict the fix.
- The repository task guard is not required for maintenance of the guard itself or its instruction files. Preserve unrelated dirty files and do not commit/tag unless the user explicitly requests it or this maintenance is already an isolated task-owned change.
- Maximum normal tool sequence: one inspection call, one patch call, one combined validation call. A fourth call requires a failed validation or a concrete blocker, and the reason must be stated before running it.

| Lane | Efficiency-review cadence | File limit per logical unit | Same-route cycle threshold |
| --- | ---: | ---: | --- |
| `quick-fix` | 15 minutes | 4 task-owned files | 2 diagnosis cycles |
| `standard` | 30 minutes | 8 task-owned files | 3 diagnosis cycles |
| `assessment` | 45 minutes per batch | 6 task-owned files | one bounded evidence question or commit cluster |
| `upstream` | 45 minutes per approved unit | 16 task-owned files | one approved functional unit |

- A small bug is `quick-fix` by default. Do not promote it to architecture, broad research, or native work merely because more investigation is possible.
- A simple rule/document/Skill edit should finish in a few minutes; 5 minutes triggers immediate removal of every optional step, and 10 minutes is already abnormal. A localized bug should also take the shortest evidence-backed path. These are efficiency escalation points, not permission to abandon incomplete work: after escalation perform only the necessary edit, one cheapest decisive verification, commit/tag when authorized, and hand off. Continue longer only when a named required operation inherently takes longer or a concrete blocker prevents completion; state that reason immediately.
- These cadences are progress-review reminders, never task budgets, deadlines, or caps on total duration/review count. At a boundary, checkpoint and judge convergence: continue a productive route with one bounded next action, or replace an unproductive/repeated route. The task remains active until complete or genuinely blocked.
- Optimize for shortest elapsed time by removing redundant exploration, repeated commands, speculative scope, and low-value validation. Never gain speed by dropping required behavior, risk-driven verification, rollback, or the requested completion target.
- Do not widen declared behavior or paths without explicit user approval. Split genuinely large work into independently useful units while preserving the original completion target.
- Except for the governance-maintenance fast path above, run `bash .codex/scripts/task_guard.sh start --id <task-id> --mode <lane> --scope <path>...` before code edits. Run `check` after each diagnosis/edit cycle and before any expensive command.
- A nonzero guard result is a gate, not task termination. On `CHECKPOINT_REQUIRED`, persist state, then continue. On `REPLAN_REQUIRED_CONTINUE`, record why the current route is not converging, choose a materially different shortest path, run `replan`, and immediately continue the same task. There is no maximum number of replans.
- A timeout may bound one potentially hung command; it must not bound the task. Diagnose the timeout and continue with a safer, narrower, or resumable command.

## 2. Scope is closed by default

- Modify only the declared paths and behavior needed for the completion sentence. No unrelated refactor, cleanup, formatting sweep, dependency upgrade, generated-file churn, or speculative abstraction.
- A failing unrelated test, warning, or nearby defect is not part of the task. Report it; do not fix it unless it blocks the requested result and the user approves the expansion.
- Preserve every file dirty before task start. Never include an initially dirty path in task scope or a task commit unless the user explicitly assigns that work and it has been isolated safely.
- If the proposed fix crosses a new module, changes public behavior/API, changes dependency or binary ownership, or exceeds the current logical-unit limits, checkpoint and replan the smallest next unit. Ask the user only when that next unit expands the authorized behavior or paths.

## 3. Search and diagnosis must earn their cost

- For a bug, follow: reproduce or establish evidence -> inspect the exact path and callers -> form one falsifiable cause -> make the smallest fix -> run one targeted verification.
- Search locally first with exact symbols/errors. Internet, upstream history, papers, broad issue searches, and whole-repository archaeology are escalation steps, not defaults for `quick-fix`.
- Expand search only when the current evidence cannot decide the next action. Use at most two query reformulations for a quick fix and three for one material upstream question; prefer primary code/docs/issues over repetitive posts.
- Capture a command's full output once and filter/read that saved result. Do not rerun the same test or build with different output filters.
- Run each final syntax/lint/test check once. Repeat it only after a relevant edit or when the first result is inconclusive; never re-check an unchanged successful result for reassurance.
- After two failed attempts with the same hypothesis, discard it and replan a materially different path. Repetition without new evidence is prohibited; task completion remains required.

## 4. Verification is risk-based and minimal

- Start with the cheapest deterministic check that can falsify the change: focused unit test, compilation target, static check, or one representative device scenario.
- One conclusive pass is enough unless the change concerns flakiness, concurrency, performance variance, native lifecycle, ABI, or device-specific behavior.
- Do not run full Gradle matrices, all ABIs, native rebuilds, broad device suites, fuzzing, or unrelated tests unless the changed contract requires them or the user requests them.
- Do not weaken a failing gate. Classify the failure as regression, environment, or stale expectation. Expand work only if fixing it is within the declared scope.
- Quality floor: do not trade away existing behavior, correctness, security, compatibility, material performance, or task completion merely to meet the clock. Time pressure removes redundant work; it never authorizes an unverified shortcut.

## 5. Context and elapsed-time recovery

- Codex cannot reliably inspect its exact remaining context tokens. Use hard proxies: elapsed time, completed cycles, changed-file count, and anticipated output size.
- Checkpoint after every verified logical unit, at 70% of a review cadence, before a command likely to run over 10 minutes or emit over roughly 200 lines, and before changing functional stage/repository.
- A checkpoint records objective, lane/scope, branch/HEAD, protected dirty paths, completed evidence, files changed, verification/result, unresolved risk, rollback anchor, and exactly one next action.
- For long-lived work, update the relevant tracked document with `apply_patch`, then run `bash .codex/scripts/task_guard.sh checkpoint --document <path> --message <summary> --next <action>`.
- After compaction or resumption, read the latest checkpoint and reconcile it with `git status`, branch, and HEAD before doing new work. Never reconstruct exact hashes or decisions from memory.

## 6. Commit and recovery tag are part of code completion

- One task guard session equals one atomic logical change. If a task needs another logical commit, finish this unit and start a new guard session.
- A code change is not complete until its targeted verification is recorded, its task-owned files are committed atomically, and that commit has a unique annotated local recovery tag.
- Finish with `bash .codex/scripts/task_guard.sh finish --verified <evidence> --commit-message <message>`. The script must stage only task-owned paths and create `recovery/<task-id>/<timestamp>`; never hand-stage unrelated dirty work.
- After the commit succeeds, create its recovery tag immediately with one local, non-interactive `git tag -a` command with tag signing disabled. The tag phase should normally finish within 5 seconds; do not insert builds, tests, searches, network calls, repeated diff reviews, or redundant tag checks between commit and tag.
- Treat a successful tag command as sufficient. If it fails, capture the error once, fix its direct cause, and retry only after the command materially changes; never loop an unchanged tag command.
- If verification fails, scope overlaps pre-existing dirty work, or a safe atomic commit cannot be formed, do not commit/tag. Checkpoint and report the exact blocker.
- Assessment-only work does not authorize production edits. Tags point only to committed, verified states. Never push commits/tags, move a published tag, rewrite history, or publish artifacts without explicit authorization.

## 7. Best-practice design research

The mandatory design-research gate below applies repository-wide to every material new feature, optimization, architecture, compatibility, security, dependency, or cross-module requirement, as well as every upstream merge candidate. Use the relevant domain Skill when one exists. Simple text/configuration changes and narrowly localized fixes with an already established design are exempt unless the user explicitly requests research.

## 8. Upstream and player dependency work

For FFmpeg, media/Media3/nextlib, Exo, mpv, mpv-android, libplacebo, JNI, native binaries, locks, patches, or binary packaging, read and follow `.codex/skills/upstream-integration-governor/SKILL.md` and only the references it marks for the current lane.

- Assessment and implementation are separate authorities. Enumerate every in-scope upstream commit with a full 40-character ID and a disposition; never implement an unapproved stage.
- Group related cross-repository behavior into independently reversible `Exo`, `MPV`, or `common` stages. Preserve the decision/implementation order `Exo -> MPV`.
- Continue the active assessment from `docs/upstream-player-dependency-merge-assessment-2026-08-20.md`; do not redo completed analysis unless source heads or material evidence changed.
- Resolve every actionable stage to the stable task ID recorded in the assessment index before creating or updating task documentation. Use the existing families `E*`/`E*-*` for Exo, `E-SP*` for Exo performance, `P*`/`P*-*` for MPV, and `C*`/`C*-*` for common work. Never renumber, recycle, or replace an assigned ID with a generic sequence.
- One started upstream task has exactly one durable file named `docs/<TASK-ID>-<slug>.md`. That file owns the task's best-practice research, local-code review, decision, implementation history, repeated fixes, verification, commits/tags, rollback, current status, and next action. Append later work to it; do not create parallel `plan`, `assessment`, `implementation`, `fix`, or dated follow-up files for the same task.
- The assessment document is the sole exception: it remains the cross-task index and exhaustive upstream commit ledger. It links each active task to its unique document but does not replace that document's implementation record.

### Mandatory design-research gate

Before changing code, locks, build scripts, patches, artifacts, or runtime behavior for any upstream candidate or material new requirement, complete a decision-ready best-practice review. A material requirement includes a new user-facing capability or a change to architecture, performance, playback behavior, startup/seek, decoder or renderer selection, network/proxy handling, compatibility, native/binary packaging, public API, security, data ownership, or a cross-module contract.

- Before implementation, create or update the task's unique `docs/<TASK-ID>-<slug>.md` and link it from the assessment index. Its best-practice section is the durable plan; do not create a second `plans/` document or treat a chat response as the record.
- Search every applicable evidence class: exact upstream source/commits/tests; official specifications and platform/project documentation; upstream PRs, issues, reverts, and maintainer discussions; mature related-project code and tests; and relevant papers, technical posts, blogs, benchmarks, or field reports. If a class is genuinely inapplicable, record the reason in the plan rather than silently skipping it.
- Read the actual sources, not search-result snippets. Record URL or repository path, revision/commit, access date, evidence grade, the supported claim, WebHTV applicability, caveats, and the decision impact. Use the configured proxy when network access is needed; if required research cannot be obtained, mark the stage incomplete and do not claim a best-practice conclusion.
- Review the current WebHTV implementation and call/data flow at concrete file and symbol locations. Identify existing equivalent or partial behavior, local safeguards, consumers, build reachability, and any later local fixes before selecting a design.
- The plan must compare at least `no change`, the unmodified upstream approach, and a narrow WebHTV-adapted approach (plus other credible alternatives when relevant). Explicitly decide whether to optimize, correct, supplement, or reject the upstream proposal for this project, and explain the tradeoffs for correctness, compatibility, performance, quality, lifecycle, ABI, security, provenance, maintenance, validation, rollout, and rollback.
- Do not implement until the plan contains a recommendation, acceptance criteria, and rollback path and the user explicitly approves the proposed stage. Keep research bounded to one decision-shaped question at a time: stop when additional sources no longer could change the design or decision, and record the unresolved gate instead of browsing aimlessly.

## 9. Enforcement boundary

`AGENTS.md` is an instruction layer, not a security boundary. The task guard can enforce progress reviews, declared paths, protected dirty files, atomic task commits, and recovery tags only when invoked. It must never convert elapsed time, token estimates, review count, or cycle count into permission to abandon a task or lower its completion criteria. Git hooks are not the primary mechanism because ordinary clones do not reliably enable tracked hooks and client hooks can be bypassed. CI remains the correct place for organization-wide mandatory checks.
