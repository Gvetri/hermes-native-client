# The protected main branch and the pull-request workflow

`main` is protected by the repository ruleset `Main`. Every change lands through an issue-scoped
branch and a pull request, and the protected branch accepts a merge only when the current head of
that pull request passed the aggregate `quality-gate` status.

## Rules enforced on `main`

| Rule | Value |
| --- | --- |
| Pull request before merging | Required; direct pushes are refused for every account, administrators included |
| Merge strategy | Squash only. Merge commits and rebase merges are disabled on the repository as well |
| Required status | Exactly one: `quality-gate`, the aggregate check of the workflow below |
| Required approvals | Zero, because one operator maintains the repository |
| Stale reviews | Dismissed when a new commit arrives |
| Up-to-date branch | Required, so the status describes the head that is merged and not an older result |
| Branch deletion and force push | Prohibited |
| Bypass actors | None, so no account or role can skip a rule |

A merged pull request deletes its branch. Auto-merge may be enabled on a ready pull request, and the
merge then happens only once the required status is green for the exact current head. GitHub refuses
to merge a draft pull request at all.

## Branch names

Use one issue-scoped branch per change, in the form `<type>/<issue>-<short-description>`, for example
`feat/25-protected-main-workflow`. The type is the Conventional Commit type of the change.

## What the workflow runs

`.github/workflows/quality-gate.yml` runs on a pull request, a push to `main`, a nightly schedule at
02:00 UTC, and a manual dispatch. A pull request triggers on `opened`, `synchronize`, `reopened`,
`ready_for_review`, `converted_to_draft`, and `edited`: a draft transition and a title edit both
re-evaluate the head, so a result from an earlier state of a pull request never stands for the
current one.

The concurrency group is the pull-request number with `cancel-in-progress`, so the runs of one pull
request never race: a newer run replaces the obsolete pending one, and only the current head's own
run publishes the required status. Results are published per check, and only the aggregate job named
`quality-gate` is a required status. The aggregate fails unless every check declared in
`.github/quality-gate/required-checks.txt` reports the outcome the event expects.

| Run | Checks that execute | Checks that stay skipped |
| --- | --- | --- |
| Ready in-repository pull request | The complete gate: `formatting`, `static-analysis`, `unit-tests`, `fixture-descriptor`, `fixture-lifecycle`, `fixture-contract`, `android-build`, `architecture-check`, `coverage-mutation`, `compose-jvm-tests`, `conformance`, `commit-message`, `draft-validation`, `fork-guard` | `api24-instrumentation`, `maestro-journeys` |
| Draft in-repository pull request | `draft-validation` (`formatCheck` and `:app:lintDebug` only), `fork-guard` | Every other check |
| External-fork pull request | `fork-guard` | Every other check |
| Push to `main` | JVM, static, fixture, architecture, coverage/mutation and conformance validation; `android-build` compiles debug/release Kotlin without packaging the application | `api24-instrumentation`, `maestro-journeys`, `draft-validation`, `commit-message`, `fork-guard` |
| Nightly validation, manual validation | The complete gate plus `api24-instrumentation` and `maestro-journeys` | `draft-validation`, `commit-message`, `fork-guard` |

A merge does not package or publish an application APK or generate a product version.
Signing-policy tests may create minimal disposable APK fixtures; those are not application
builds or distribution artifacts. Full scheduled/manual validation is required before
[the separate Nightly publisher](nightly-releases.md) can select a source commit.

`draft-validation` runs for every in-repository pull request: on a draft it is the whole validation,
and on a ready pull request it adds a fast formatting and lint result beside the complete gate. The
aggregate also requires that a pull request targets `main`, so a pull request against another branch
fails even when its checks pass. For an external-fork pull request the aggregate fails closed: the
head never ran the complete gate, so it must never hold a passing required status.

## Merging

A pull request may be merged or auto-merged only when all of the following hold:

- it is ready for review, not a draft;
- it targets `main`;
- its exact current head passed the required `quality-gate` status, and the branch is up to date
  with `main`;
- it comes from the repository itself, because an external-fork head never holds a passing required
  status;
- it is merged with a squash merge, which is the only enabled strategy.

The squash merge writes the pull-request title onto `main`, so the title and every commit the pull
request carries must be a Conventional Commit. `.github/scripts/verify-conventional-commits.py`
checks both and accepts the declared types `build`, `chore`, `ci`, `docs`, `feat`, `fix`, `perf`,
`refactor`, `revert`, and `test`, with an optional lower-case scope, an optional `!`, a non-empty
description, no trailing period, and a subject within 100 characters. Merge, revert, `fixup!`, and
`squash!` subjects are exempt.

## External forks

An external-fork pull request is untrusted input, so it never runs the complete gate:

- its run is read-only and secret-free, and the workflow declares no secret for any event;
- the repository requires maintainer approval before a run from an external contributor starts, so no
  external change consumes runner time on its own;
- only `fork-guard` executes, and it reports which handling applies;
- the aggregate fails closed, so an external head never holds a passing required status and neither
  an automatic nor a manual merge of the fork head is possible;
- external-fork artifacts are not release or compatibility inputs;
- a maintainer validates the change by carrying it onto an in-repository branch, where the complete
  gate runs against a commit-verified head, and merges that pull request explicitly. That explicit
  merge is the only merge an external change can lead to.

## Verifying the configuration

The rules above are configuration rather than code, so `.github/scripts/verify-repository-conformance.py`
reads the enforced rules, the active rulesets and their bypass actors, and the workflow declaration
back from the GitHub API, and fails when a rule is missing, weakened, or replaced by a bypass. The
`conformance` check runs it on every push, ready pull request, nightly run, and manual run.

The same check also runs `.github/scripts/release-signing.py environment` with read-only
`actions` access. It verifies the `release-signing` environment's required human reviewer,
disabled administrator bypass, main-only branch policy, and protected `main` branch. Missing,
weakened, or unreadable settings fail the check; no signing secret is exposed to validation.
See [Protected Android release signing](release-signing.md) for the separate signing workflow.

The repository Actions settings need the Administration permission, which a workflow token cannot
hold, so the owner verifies them explicitly:

```text
GITHUB_REPOSITORY=Gvetri/hermes-native-client GITHUB_TOKEN="$(gh auth token)" \
  python3 .github/scripts/verify-repository-conformance.py --owner
```

Without `--owner` the check verifies everything a read-only credential can read: the enforced rules
and the rulesets' bypass actors, the required status, the required-check declaration, the declared
read-only token scope, and the absence of secrets.

## The contracts are themselves tested

`buildSrc/src/test/kotlin/org/hermesnative/client/buildlogic/QualityGateConfigurationTest.kt` pins
the workflow contracts, and `.github/scripts/tests/test_protected_main.py` and
`.github/scripts/tests/test_test_jobs.py` pin the routing, the aggregate gate, the commit-message
check, and the conformance check. Both suites run in the `architecture-check` job, so a change to the
workflow that contradicts a documented rule fails the gate. See
[Quality gates](quality-gates.md) for the declared checks and thresholds.
