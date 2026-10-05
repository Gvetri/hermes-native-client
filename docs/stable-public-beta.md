# Milestone-based Stable Public Beta

A **Stable Public Beta** is a human-approved GitHub prerelease, not a new build.
It has **community support only**. There is no uptime or response-time commitment,
and no compatibility promise outside that release's published compatibility statement.
This process never runs a live-provider test in Actions and never puts provider
credentials in GitHub or the APK. [Nightlies](nightly-releases.md) remain development
snapshots, not stable releases.

## Review and declare the milestone

The reviewer merges a source-controlled declaration at
`.github/stable-public-beta/declaration.json` and its *complete, redacted*
`.github/stable-public-beta/live-smoke.json` on protected `main` **before** closing
that exact GitHub milestone. These files are deliberately absent until a real
operator plans a release; this repository does not claim a real milestone, smoke
result, or compatibility statement. An unrelated closed milestone with no
installed declaration does nothing. Do not add manual workflow inputs, a manually
chosen candidate, or a manually edited Semantic Version.

The declaration has exactly these fields (the values below are **illustrative
syntax only**, not a release decision or usable commit IDs):

```json
{
  "schema": "stable-public-beta-declaration-v1",
  "milestone_number": 123,
  "start_exclusive": "1111111111111111111111111111111111111111",
  "end_inclusive": "2222222222222222222222222222222222222222",
  "compatibility": "Android 8.0 and newer on ARM64.",
  "change_notes": ["Added Session list updates."]
}
```

Both boundaries are **full, exact, first-parent `main` commit SHAs**, not a date,
branch name, milestone issue list, or a mutable tag. `start_exclusive` is the
prior Stable Public Beta's source SHA. For the **first** Stable Public Beta,
`start_exclusive` is the reviewed initial boundary and the starting Semantic
Version is `0.0.0`; the starting commit is *not* counted. `end_inclusive` is the
reviewed milestone completion boundary. The chosen Nightly source must lie after
the start and at or before the end. Commits after the chosen source do not count
toward that release's version. A later declaration must start at the exact prior
stable source, so it cannot silently skip the interval since the last stable.
Review the compatibility statement and every user-facing change note; they are
single-line plain public text, not copied from commits or smoke evidence.

Before merging the declaration, run the [local live smoke gate](local-live-smoke.md)
with the **downloaded, signed Nightly APK that selection will choose**. Review the
redacted evidence for private paths, identifiers, video content, and credentials
*before committing it to the public repository*. Never commit raw logs, video,
provider keys, or real Gateway addresses. The validator requires its full audited
fixture/image/source pin and all teardown and redaction checks; this declaration
cannot override the audited pin. Smoke failure, absent evidence, or a checksum for
another APK blocks publication. The encrypted video remains in the operator's
restricted, expiring local store, never this repository or a release asset.

## Selection and approval

`.github/workflows/stable-public-beta.yml` listens only for `milestone.closed`
on protected `main` in this repository. It verifies that the event and the API
agree on the milestone number, identity, state, and closure time, that the checked
out revision is still the protected main tip, and that both declared boundaries
follow main's first-parent ancestry. Main moving during the human approval wait
stops publication; a new reviewed declaration and milestone closure is required.

Read-only preparation chooses the **newest source commit**, by first-parent
ancestry within the declared range, with one published `Nightly` prerelease.
A later rerun of an older source never outranks a newer source. An incomplete
newer Nightly is an error, not permission to fall back to older APK/smoke. The
candidate's exact tag, target SHA, release state, signing run and attempt, all
protected signing jobs and main-only Nightly signing environment, validated source and full
quality-gate jobs/attempt, actual Android version code/name and ABI, pinned
signing certificate, APK signature and checksum, three public asset sizes, and
complete exact-APK Local Live Smoke are checked. Only the existing Nightly APK
bytes are promoted; its embedded `nightly-*` versionName and versionCode are
**retained**. The stable Semantic Version is release metadata and tag, not a
modified APK or a newly signed artifact.

From the prior stable source (or first baseline) through the selected source,
`fix` yields a patch bump, `feat` a minor bump, and `!` or `BREAKING CHANGE:` a
major bump (which resets lower digits). No version-bearing commit in the
milestone's selected range means **no release**, even if an older stable version
exists. Invalid Conventional Commit subjects also fail closed. Duplicate version,
milestone, candidate release, draft, or orphan destination tag requires operator
inspection; publication never overwrites a draft or uses `--clobber`.

Preparation freezes the declaration/evidence byte hashes, source, selected
Nightly release identity and asset IDs, names, sizes and digests, APK hashes,
validation and signing identities, version, event, run and attempt. The separate publication job waits on the
**existing** `release` environment. Its one required reviewer is Gvetri
(user ID 8773754); administrators cannot bypass, and only protected `main` is
allowed. Publication rechecks that configuration, the **current promotion run's
fresh approval** (not the unattended Nightly's success), and recomputes the
frozen candidate after the wait. Download counters and user-profile metadata are
not identity fields; ordinary downloads cannot invalidate the candidate.
GitHub's review history identifies an approved run but has no approval timestamp
or attempt identifier. Therefore, promotion accepts **only attempt 1**: both full
and partial reruns require a new milestone closure, a new run ID, and a new human
environment approval. The unattended Nightly's exact successful signing attempt
and signed APK are verified separately; Nightly success never authorizes promotion.
A promotion rerun, new Nightly/rerun, altered smoke/declaration, moved main,
weakened environment, or approval belonging only to another run fails before
any GitHub release write. The workflow has no signing secrets, build, re-signing,
provider credentials, or live smoke invocation. Only the publisher has
`contents: write`; publisher concurrency is serialized across milestones.

## Publication, public metadata, and recovery

After full preflight the publisher creates a draft `stable-beta-vX.Y.Z` prerelease
for the selected source. It reads the exact release ID back; uploads only
`hermes-native-client.apk`, `SHA256SUMS`, `signing-metadata.json`; reads the asset
inventory, downloads and hashes every uploaded byte, and verifies the APK again.
Only then does it publish and read back title, body, prerelease/draft flags,
assets, and tag target. The name is **Stable Public Beta vX.Y.Z**. The notes
contain the Semantic Version, source SHA, APK SHA-256, milestone number, retained
Android versions, certificate, compatibility, public user changes, validation and
signing run links, community-only policy, and a hash of the source-controlled
redacted smoke evidence. Raw private smoke logs, video, local paths, device IDs,
Gateway Session/Run IDs, provider identity, credentials, and arbitrary commit bodies
are **not public release assets or notes**. The redacted smoke JSON is committed to
this public repository for source review; keep it out of `gh release upload`.

A failed upload, missing asset, or hash mismatch leaves an incomplete **draft**
and refuses every retry. Inspect the exact release ID, source tag, asset inventory,
and run/attempt before any manually reviewed recovery. Remove only a confirmed
*unpublished* partial draft and its orphaned tag if appropriate; never delete a
published release to force a retry. A failed final read-back may mean publication
succeeded: read the published release and its exact tag before attempting anything
else. A closed milestone event cannot simply be replayed as a manual release;
review a new declaration and explicit milestone closure for a new attempt. Do not
weaken the environment, substitute a candidate, or bypass failed evidence.

Local verification uses synthetic GitHub API responses, a disposable signed APK,
and synthetic smoke evidence; it neither tests a live provider nor publishes to
GitHub:

```text
python3 .github/scripts/tests/test_stable_public_beta.py
python3 .github/scripts/tests/test_nightly_release.py
python3 -m unittest discover -s .github/scripts/tests -p 'test_*sign*.py'
actionlint .github/workflows/stable-public-beta.yml
./gradlew -p buildSrc test --tests org.hermesnative.client.buildlogic.QualityGateConfigurationTest
```

The Python suites require JDK 21, Android SDK Platform 35, Build Tools 35.0.0,
`JAVA_HOME`, and `ANDROID_HOME`. The buildSrc configuration test runs the beta
suite in the required architecture-check lane. A local fixture pass is not a real
human approval or a production release.
