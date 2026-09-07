#!/usr/bin/env bash
#
# Reconciles this repository's `main` against the container registry and reports every commit whose
# Release ran and produced no image.
#
# This file is BYTE-IDENTICAL in hc-admin-gateway, hc-admin-service and hc-admin-app. Nothing checks
# that, so if you change one, change all three — it carries no per-repo constant, precisely so that
# staying identical costs nothing. IMAGE_NAME is the one value that differs between them and each
# workflow supplies it; see the note on IMAGE_NAME below.
#
# ===================================================================================================
# WHY THIS IS A RECONCILIATION AND NOT A FAILURE HANDLER
# ===================================================================================================
#
# The obvious shape for "tell someone when a Release does not publish" is a job inside release.yml
# guarded by `if: failure()`. That shape cannot work here, and the reason is measured rather than
# argued. Of the 24 Release runs across the three repositories that ended `cancelled`, ALL 24 have
# zero jobs:
#
#   gh api repos/kojoampia/hc-admin-gateway/actions/runs/31476350790/jobs --jq '.total_count'  # 0
#
# A run cancelled while still PENDING never creates a job, so no step in that workflow ever
# executes — including a notification step. A failure job would have caught the ~10 runs that
# genuinely failed and none of the 24 that were dropped, which is the larger half of the problem.
#
# `on: workflow_run: [completed]` in a second workflow does better, and still not well enough: two
# runs in this estate have been sitting at status `queued` with a null conclusion since 2026-08-06
# (gateway 31121545882, service 31121543251). They will never complete, so nothing keyed on
# completion will ever fire for them, and one of the two has no image.
#
# So this asks the registry instead. It measures the property that is actually wanted — is there an
# image for this commit — rather than a proxy for it, and it therefore catches every way of losing
# one, including the ways nobody has thought of yet: a run that failed, a run cancelled before it
# started, a run stuck queued forever, a push that triggered nothing because the workflow file was
# malformed, and a tag deleted out of the registry after the fact. That is the same measurement the
# defect was originally found by (backlog item 44 was verified by manifest request, not from run
# status), and a check that reads what the diagnosis read is a check that cannot disagree with it.
#
# ===================================================================================================
# WHAT IT CONSIDERS A GAP, WHICH IS NARROWER THAN "A COMMIT ON MAIN"
# ===================================================================================================
#
# The rollback set was never "any commit on main". release.yml triggers `on: push`, and a push
# carrying several commits — a merge, a rebase, a squash landing a stack — creates ONE run, at the
# tip. The commits underneath it never had a run and were never meant to have an image. Of the
# gateway's last 25 commits on main, 17 have no image and 15 of those never had a run at all; that
# is correct behaviour and reporting it would bury the two that are not.
#
# The rule this enforces is therefore exactly the rollback contract stated in hc-admin-ci's
# deploy.sh: A COMMIT IS A ROLLBACK TARGET IF ITS RELEASE RAN AND SUCCEEDED. A commit whose Release
# ran and left no image is the violation, and it is the only thing reported here.
#
# ===================================================================================================
# EXIT CODES, WHICH ARE FOUR AND NOT TWO
# ===================================================================================================
#
#   0  Every in-scope commit has an image. Any open audit issue is closed.
#   1  Gaps found. The issue is filed or updated first, so the record survives the red run.
#   2  The audit could not run, or could not be trusted: a failed API read, an unresolvable
#      origin/<branch>, an ancestry filter that dropped everything, a registry that answered nothing
#      readable at all, or a wrong IMAGE_NAME. NEVER reported as gaps — see the guards below.
#   3  The sweep finished, found no gaps among the commits it could read, and could NOT read some.
#      Distinct from 0 because "no gaps found" and "no gaps" are different claims, and distinct from
#      1 because nothing here is evidence of a missing image. On a 3 the issue is left exactly as it
#      was: not opened, not closed. Closing on a partial sweep is the failure this whole item is
#      about, one level up.
#
# ===================================================================================================
# THE COMMAND-SUBSTITUTION RULE, WHICH THIS FILE IS THE WRONG PLACE TO GET WRONG
# ===================================================================================================
#
# Under `set -euo pipefail`, a bare `x=$(cmd)` makes cmd's exit status the script's exit status. In
# hc-admin-quality/startup.sh that shape meant one failed curl killed --verify outright, discarding
# every earlier finding and skipping the cleanup (CLAUDE.md records the sweep across sixteen of
# them). Here it would be worse in kind rather than in degree: this script's whole purpose is to be
# the thing that speaks up, so dying quietly on a transient read is the one failure mode it must not
# have. Every substitution that can fail goes through must(), or is explicitly tested on the next
# line, or is one of the two that cannot fail (a `printf | tr` and a `printf | grep -v` whose only
# non-zero is "matched nothing", both marked where they appear).
#
# There is a second half to that rule and it cost the 401 retry below its whole purpose. A function
# whose result is CAPTURED runs in a subshell, so it must not (a) write anything to stdout that is
# not the result — note() therefore writes to stderr, always — and must not (b) assign a variable
# the caller needs, because the assignment dies at the closing paren. manifest_status() sets a
# global instead of printing, for exactly that reason.
#
set -euo pipefail

# --- Configuration ---------------------------------------------------------------------------------

REGISTRY="${REGISTRY:-ghcr.io}"
BRANCH="${BRANCH:-main}"

# Minutes a run is allowed to be in flight before its commit is expected to have an image. Below
# this, an in-progress release is not a gap; above it, a run that never completed is. That upper
# half is deliberate — it is what catches the two runs that have been `queued` for a month.
GRACE_MINUTES="${GRACE_MINUTES:-90}"
export GRACE_MINUTES

# Seconds any one registry read may take. Without a bound, a hung connection stalls the job until
# the 6-hour Actions limit and reports nothing at all — the silence this exists to end.
CURL_MAX_TIME="${CURL_MAX_TIME:-30}"
CURL_CONNECT_TIMEOUT="${CURL_CONNECT_TIMEOUT:-10}"

ACCEPT_FILE="${ACCEPT_FILE:-.github/release-audit-accepted.txt}"
ISSUE_TITLE="${ISSUE_TITLE:-Release audit: commits on main with no image}"

# The issue is identified by its LABEL, not by its title. Two reasons, both of which cost a
# duplicate. `gh issue list --search '"<title>" in:title'` goes through the search index, which lags
# by minutes for a freshly-opened issue; the listing endpoint behind `--label` is immediately
# consistent. And this workflow fires on `workflow_run: [completed]`, so two audits minutes apart are
# routine rather than exotic — which is exactly the window the index has not caught up in. The label
# is reserved for this script; do not apply it by hand.
ISSUE_LABEL="${ISSUE_LABEL:-release-audit}"

: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY must be set (owner/name)}"
OWNER="${GITHUB_REPOSITORY%%/*}"
REPO="${GITHUB_REPOSITORY##*/}"

# GHCR rejects an upper-case path component and an account's registered case is not guaranteed, so
# it is folded rather than assumed — the same reason release.yml folds it. `printf | tr` over a
# value that is already in hand has no failure mode; it is the one bare substitution here.
OWNER_LC="$(printf '%s' "$OWNER" | tr '[:upper:]' '[:lower:]')"

# ALL THREE WORKFLOWS SET IMAGE_NAME EXPLICITLY, so this default is a fallback for running the
# script by hand and not the normal path. It is the repository name because the image is named after
# the repository in all three — a COINCIDENCE THAT WAS CHECKED, not a rule: the api repo is
# hc-admin-service and its image is hc-admin-service, but it is named after the service and its
# directory in the workspace is api/. Keeping the value in the workflow rather than relying on the
# coincidence is what lets this file stay identical across the three; if a fourth repo ever adopts
# it, set IMAGE_NAME there too rather than editing this. The consequence of getting it wrong is loud
# and not silent — see the sanity check below.
IMAGE_NAME="${IMAGE_NAME:-$REPO}"
IMAGE_PATH="${OWNER_LC}/${IMAGE_NAME}"

# --- Plumbing --------------------------------------------------------------------------------------

# Run a command, and abort with a message naming it if it fails. The point is that the abort SAYS
# something: a bare assignment under `set -e` exits with a status and no output at all.
must() {
  local what="$1"
  shift
  local out status
  set +e
  out="$("$@" 2>&1)"
  status=$?
  set -e
  if [ "$status" -ne 0 ]; then
    printf 'release-audit: FAILED to %s (exit %d)\n%s\n' "$what" "$status" "$out" >&2
    exit 2
  fi
  printf '%s' "$out"
}

# stderr, ALWAYS. Half of this script's own output is produced inside command substitutions, and a
# note() on stdout there is captured into the caller's variable instead of being read by anybody:
# the 401 renewal notice landed inside $code, matched neither 200 nor 404, and fell through to the
# abort the retry exists to prevent. In Actions both streams are the same log, so nothing is lost.
note() { printf '%s\n' "$*" >&2; }

# --- A pull token for the registry -----------------------------------------------------------------
#
# The standard OCI token flow, with the workflow's GITHUB_TOKEN as the credential. Anonymous works
# too while these packages are public, but authenticating means the audit keeps working if one is
# ever made private — where the anonymous form would start reporting every commit as missing.

registry_token() {
  local body token
  body="$(must "get a GHCR pull token for ${IMAGE_PATH}" \
    curl -fsS --connect-timeout "$CURL_CONNECT_TIMEOUT" --max-time "$CURL_MAX_TIME" \
    -u "token:${GH_TOKEN}" \
    "https://${REGISTRY}/token?service=${REGISTRY}&scope=repository:${IMAGE_PATH}:pull")"
  # Not `must`: this runs inside a command substitution, so must()'s message would be the only thing
  # a reader gets and the parse failure would otherwise be a bare non-zero with no output at all.
  token="$(printf '%s' "$body" | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')" || {
    printf 'release-audit: FAILED to parse the GHCR token response for %s\n' "$IMAGE_PATH" >&2
    exit 2
  }
  printf '%s' "$token"
}

# 200 = the tag resolves, 404 = it does not, anything else = do not guess. A 5xx must NOT be read as
# a missing image: that would turn a registry blip into a page of false gaps, and the credibility of
# this report is the only thing making anyone act on it.
_manifest_raw() {
  curl -sS -o /dev/null -w '%{http_code}' \
    --connect-timeout "$CURL_CONNECT_TIMEOUT" --max-time "$CURL_MAX_TIME" \
    -H "Authorization: Bearer ${RT}" \
    -H 'Accept: application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.docker.distribution.manifest.v2+json,application/vnd.oci.image.manifest.v1+json' \
    "https://${REGISTRY}/v2/${IMAGE_PATH}/manifests/$1"
}

# Sets MANIFEST_CODE rather than printing it, and the difference is the whole point. A 401 is retried
# ONCE against a fresh token rather than reported: registry pull tokens are short-lived and this
# sweep is O(commits with a run) — 88 in hc-admin-service today and only ever growing — so a token
# minted at the top will eventually expire partway down. Written as `code="$(manifest_status …)"`
# that retry could not work at all: `RT="$(registry_token)"` ran inside the command substitution, so
# the renewed token died at the closing paren and every later commit paid for a fresh one. Assigning
# a global from the caller's own shell is what makes the renewal stick.
MANIFEST_CODE=""
manifest_status() {
  MANIFEST_CODE="$(_manifest_raw "$1")" || MANIFEST_CODE=000
  if [ "$MANIFEST_CODE" = 401 ]; then
    note "release-audit: registry token expired mid-sweep, renewing"
    RT="$(registry_token)"
    MANIFEST_CODE="$(_manifest_raw "$1")" || MANIFEST_CODE=000
  fi
}

# --- GitHub issue plumbing ---------------------------------------------------------------------------

ensure_label() {
  # --force creates or updates, so this is idempotent and needs no read first — a read-then-create
  # would race the second of two audits minutes apart, which this workflow's triggers make routine.
  must "ensure the '${ISSUE_LABEL}' label exists on ${GITHUB_REPOSITORY}" \
    gh label create "$ISSUE_LABEL" --repo "$GITHUB_REPOSITORY" --force \
    --color 'B60205' --description 'Opened by .github/workflows/release-audit.yml — do not apply by hand.' >/dev/null
}

# The gap path. A failed read is exit 2 and NOT "there is none": `|| true` here turned "could not
# determine" into "there is no open issue" and opened a duplicate beside the one already open.
open_audit_issues() {
  must "list open '${ISSUE_LABEL}' issues on ${GITHUB_REPOSITORY}" \
    gh issue list --repo "$GITHUB_REPOSITORY" --state open --label "$ISSUE_LABEL" \
    --json number --jq '.[].number'
}

# The close path, which may fail soft where the gap path may not — and the asymmetry is deliberate.
# Failing to close leaves a stale alert up, which is visible and self-correcting on the next run;
# aborting instead would turn a transient read into a red run on a repository that is actually
# clean. Unlike the `|| true` it replaces, it says that it could not tell. A missing label is not an
# error here: it means no audit issue has ever been filed, which is the common case.
open_audit_issues_soft() {
  local out
  if ! out="$(gh issue list --repo "$GITHUB_REPOSITORY" --state open --label "$ISSUE_LABEL" \
    --json number --jq '.[].number' 2>&1)"; then
    note "release-audit: could not list open '${ISSUE_LABEL}' issues (${out}) — leaving any open issue alone"
    return 0
  fi
  printf '%s' "$out"
}

# --- Which commits are in scope --------------------------------------------------------------------

note "release-audit: ${GITHUB_REPOSITORY} -> ${REGISTRY}/${IMAGE_PATH}, branch ${BRANCH}"

# The ancestry filter below silently answers "not an ancestor" for a ref it cannot resolve, so with
# origin/<branch> missing — a shallow checkout, a rename, a `persist-credentials` change — this
# reports 0 candidates, 0 gaps, a clean bill of health AND CLOSES THE OPEN ISSUE. That is a worse
# outcome than the defect this file exists to catch, so the ref is proved before anything is swept
# rather than assumed from a `fetch-depth: 0` in a sibling file.
# Deliberately without --quiet: git's own message is what must() prints, and "Needed a single
# revision" beats an abort with an empty body.
must "resolve origin/${BRANCH} (release-audit.yml checks out with fetch-depth: 0 for this)" \
  git rev-parse --verify "origin/${BRANCH}^{commit}" >/dev/null

RUNS_JSON="$(must "list Release runs on ${BRANCH}" \
  gh api --paginate -X GET "repos/${GITHUB_REPOSITORY}/actions/workflows/release.yml/runs" \
  -f branch="${BRANCH}" -F per_page=100 --slurp)"

# Through must() so a malformed payload aborts with a message and a traceback naming the file. Bare,
# it exited 1 with no output — the same code the workflow documents as "gaps found, issue filed",
# on a run that filed nothing.
CANDIDATES="$(must "select the commits in scope" python3 -c '
import json, os, sys, datetime

pages = json.load(sys.stdin)
runs = [r for page in pages for r in page["workflow_runs"]]
# The Actions API caps a paginated listing at 1000 results and says nothing when it truncates. The
# runs come back newest-first, so a truncated sweep loses the OLDEST commits — silently shrinking the
# set exactly as an unresolvable origin/main would. Reported rather than guarded: this repository is
# two orders of magnitude below the cap, and a warning that arrives before it matters is worth more
# than a failure that arrives after.
total = pages[0].get("total_count", len(runs)) if pages else 0
grace = datetime.timedelta(minutes=int(os.environ["GRACE_MINUTES"]))
now = datetime.datetime.now(datetime.timezone.utc)

def ts(s):
    return datetime.datetime.fromisoformat(s.replace("Z", "+00:00"))

by_sha = {}
for r in runs:
    e = by_sha.setdefault(r["head_sha"], {"completed": False, "newest": None, "run": None})
    if r["status"] == "completed":
        e["completed"] = True
    if e["newest"] is None or ts(r["created_at"]) > e["newest"]:
        e["newest"] = ts(r["created_at"])
        e["run"] = r

print("\t".join(["#meta", str(len(runs)), str(total)]))
for sha, e in sorted(by_sha.items(), key=lambda kv: kv[1]["newest"]):
    # In scope once the run has completed, or once it has had longer than the grace period to. The
    # second clause is the one that catches a run stuck queued: it never completes, so a rule keyed
    # on completion alone would exempt it forever.
    if not (e["completed"] or (now - e["newest"]) > grace):
        continue
    r = e["run"]
    print("\t".join([sha, r["conclusion"] or r["status"], r["created_at"], str(r["id"]), r["html_url"]]))
' <<<"$RUNS_JSON")"

# Split the metadata line off the candidate list. `grep -v` returning 1 means "every line matched",
# i.e. there were no candidates — tested rather than guarded, since that is not a failure.
RUNS_META="$(printf '%s\n' "$CANDIDATES" | sed -n 's/^#meta	//p')" || RUNS_META=""
CANDIDATES="$(printf '%s\n' "$CANDIDATES" | grep -v '^#meta	')" || CANDIDATES=""
RUNS_SEEN=0
RUNS_TOTAL=0
IFS=$'\t' read -r RUNS_SEEN RUNS_TOTAL <<<"$RUNS_META" || true
TRUNCATED_NOTE=""
if [ "${RUNS_TOTAL:-0}" -gt "${RUNS_SEEN:-0}" ] 2>/dev/null; then
  TRUNCATED_NOTE="The Actions API returned ${RUNS_SEEN} of ${RUNS_TOTAL} Release runs (it caps a paginated listing at 1000). Runs come back newest-first, so the OLDEST commits were not swept and a gap among them would not be reported here."
  note "release-audit: WARNING — ${TRUNCATED_NOTE}"
fi

if [ -z "$CANDIDATES" ]; then
  note "release-audit: no Release runs on ${BRANCH} are in scope yet. Nothing to reconcile."
  exit 0
fi

RT="$(registry_token)"
[ -n "$RT" ] || { note "release-audit: empty registry token"; exit 2; }

# The accept list. A commit named here is a gap somebody has decided to live with, and the deciding
# is a COMMIT — dated, reviewable, and attributable — rather than a silence. That is the whole
# difference between this and the state item 44 describes, where eleven gaps went a month without
# anyone knowing they had been accepted, because nobody had.
declare -A ACCEPTED=()
ACCEPT_UNRESOLVED=()
if [ -f "$ACCEPT_FILE" ]; then
  # `|| [ -n "$sha" ]` keeps the last line of a file that does not end in a newline. `read` returns
  # non-zero on a partial line even though it has populated the variables, so without this a
  # hand-edited accept list silently drops whichever entry was added last — which is always the one
  # somebody just made a decision about.
  while read -r sha _rest || [ -n "$sha" ]; do
    case "$sha" in '' | '#'*) continue ;; esac
    # Every entry goes through git rather than being trusted as written, and there are two distinct
    # reasons. The issue body renders `${sha:0:12}` as the link text, so copying a commit out of the
    # report yields a 12-character prefix — and the sweep compares full 40-character SHAs, so a short
    # entry used to be counted as loaded, match nothing, and leave the gap reported: accepted in the
    # file and still on the alert, with no message either way. And an entry naming no commit at all
    # (a typo, or a commit that has since been gc'd) has exactly the same silent no-op shape. Expand
    # what can be expanded, and say out loud what cannot.
    full="$(git rev-parse --verify --quiet "${sha}^{commit}" 2>/dev/null)" || full=""
    if [ -z "$full" ]; then
      note "release-audit: accept list entry '${sha}' is not a commit in this repository — it accepts nothing"
      ACCEPT_UNRESOLVED+=("$sha")
      continue
    fi
    [ "$full" = "$sha" ] || note "release-audit: accept list entry ${sha} expands to ${full}"
    ACCEPTED["$full"]=1
  done <"$ACCEPT_FILE"
  note "release-audit: accept list ${ACCEPT_FILE} holds ${#ACCEPTED[@]} commit(s)"
else
  note "release-audit: no accept list at ${ACCEPT_FILE}"
fi

# --- The sweep ---------------------------------------------------------------------------------------

GAPS=()
UNREADABLE=()
n_ok=0
n_accepted=0
n_checked=0
n_candidates=0
n_not_ancestor=0

while IFS=$'\t' read -r sha concl created run_id run_url; do
  [ -n "$sha" ] || continue
  n_candidates=$((n_candidates + 1))
  if [ -n "${ACCEPTED[$sha]:-}" ]; then
    n_accepted=$((n_accepted + 1))
    continue
  fi
  # A commit that is no longer an ancestor of the branch cannot be a rollback target for it, so its
  # missing image is not a gap. `git merge-base` needs the history, hence fetch-depth: 0 — and the
  # rev-parse above, because this call cannot tell "not an ancestor" from "cannot resolve the ref".
  if ! git merge-base --is-ancestor "$sha" "origin/${BRANCH}" 2>/dev/null; then
    n_not_ancestor=$((n_not_ancestor + 1))
    continue
  fi
  n_checked=$((n_checked + 1))
  manifest_status "$sha"
  case "$MANIFEST_CODE" in
  200) n_ok=$((n_ok + 1)) ;;
  404)
    subject="$(git log -1 --format=%s "$sha" 2>/dev/null || printf '(subject unavailable)')"
    # A tab would break the field split on the way back out and a `|` breaks the markdown table row
    # the report renders, turning one gap into a mangled line that reads as a bug in the audit.
    subject="${subject//$'\t'/ }"
    subject="${subject//|/\\|}"
    GAPS+=("${sha}"$'\t'"${concl}"$'\t'"${created}"$'\t'"${run_id}"$'\t'"${run_url}"$'\t'"${subject}")
    ;;
  *)
    # NOT a missing image, and NOT a reason to stop. Aborting here discarded every gap found before
    # it and filed nothing at all, so a 429 on the 40th of 88 reads wedged the audit permanently —
    # and its own wedging was invisible in the medium it had chosen to speak in. Collect it, finish
    # the sweep, and report it as its own thing.
    note "release-audit: registry answered ${MANIFEST_CODE} for ${sha} — cannot tell whether it has an image"
    UNREADABLE+=("${sha}"$'\t'"${MANIFEST_CODE}"$'\t'"${run_url}")
    ;;
  esac
done <<<"$CANDIDATES"

# The ancestry filter dropped everything it was given. `git merge-base --is-ancestor` answers "no"
# for a ref it cannot resolve as readily as for a commit that is genuinely gone, and the rev-parse
# above proves only that origin/<branch> exists — not that it is the branch these runs are from. A
# whole set filtered out is the shape of a misconfiguration, not of a repository.
if [ "$n_candidates" -gt 0 ] && [ "$n_not_ancestor" -eq "$n_candidates" ]; then
  note "release-audit: all ${n_candidates} candidate commits were filtered out as not ancestors of origin/${BRANCH}."
  note "release-audit: that is a checkout or branch misconfiguration, not a clean repository. Nothing was checked."
  exit 2
fi

n_read=$((n_ok + ${#GAPS[@]}))

# Every read failed. Not a report of anything, and specifically not "no gaps".
if [ "$n_checked" -gt 0 ] && [ "$n_read" -eq 0 ]; then
  note "release-audit: ${n_checked} commits checked and NOT ONE produced a readable answer from ${REGISTRY}/${IMAGE_PATH}."
  note "release-audit: that is a registry outage or a credential problem, not ${n_checked} missing images."
  exit 2
fi

# The wrong-image-name guard, and the reason it is not paranoia. If IMAGE_NAME does not name a real
# package, every single manifest read returns 404 and this script's natural output is "every commit
# on main is a gap" — an alarm so large it reads as broken tooling and gets ignored, which is the
# same end state as no alarm. A configuration error must report as a configuration error.
if [ "$n_read" -gt 0 ] && [ "$n_ok" -eq 0 ]; then
  note "release-audit: ${n_read} commits read and NONE resolved in ${REGISTRY}/${IMAGE_PATH}."
  note "release-audit: that is a wrong image name or a registry outage, not ${n_read} missing images."
  exit 2
fi

# --- The report --------------------------------------------------------------------------------------

summary_file="${GITHUB_STEP_SUMMARY:-/dev/stdout}"

{
  printf '## Release audit — `%s`\n\n' "$GITHUB_REPOSITORY"
  printf 'Registry: `%s/%s` · branch `%s`\n\n' "$REGISTRY" "$IMAGE_PATH" "$BRANCH"
  printf '| Commits with a Release run | With an image | **Missing an image** | Could not tell | Accepted |\n'
  printf '| --- | --- | --- | --- | --- |\n'
  printf '| %d | %d | **%d** | %d | %d |\n\n' \
    "$n_checked" "$n_ok" "${#GAPS[@]}" "${#UNREADABLE[@]}" "$n_accepted"
  if [ -n "$TRUNCATED_NOTE" ]; then
    printf '> ⚠ **Incomplete sweep.** %s\n\n' "$TRUNCATED_NOTE"
  fi
  if [ "${#ACCEPT_UNRESOLVED[@]}" -gt 0 ]; then
    printf '> ⚠ **%d entr(y/ies) in `%s` name nothing in this repository and accept nothing:** `%s`\n\n' \
      "${#ACCEPT_UNRESOLVED[@]}" "$ACCEPT_FILE" "${ACCEPT_UNRESOLVED[*]}"
  fi
} >>"$summary_file"

# Rendered into both the step summary and the issue, because "the audit could not read these" has to
# travel with the gap list rather than living only in a log nobody opens.
unreadable_section() {
  [ "${#UNREADABLE[@]}" -gt 0 ] || return 0
  printf '\n### Could not tell (%d)\n\n' "${#UNREADABLE[@]}"
  printf 'The registry answered something other than 200 or 404 for these, so the audit does **not**\n'
  printf 'know whether they have an image and deliberately does not guess — a 5xx read as a missing\n'
  printf 'image would turn a registry blip into a page of false gaps. They are almost always transient;\n'
  printf 'the next scheduled run re-reads them.\n\n'
  printf '| Commit | Registry said | Run |\n| --- | --- | --- |\n'
  local u usha ucode uurl
  for u in "${UNREADABLE[@]}"; do
    IFS=$'\t' read -r usha ucode uurl <<<"$u"
    printf '| [`%s`](%s/%s/commit/%s) | `%s` | %s |\n' \
      "${usha:0:12}" "${GITHUB_SERVER_URL:-https://github.com}" "$GITHUB_REPOSITORY" "$usha" "$ucode" "$uurl"
  done
  printf '\n'
}

if [ "${#GAPS[@]}" -eq 0 ] && [ "${#UNREADABLE[@]}" -gt 0 ]; then
  note "release-audit: no gaps among the ${n_read} commits that could be read, but ${#UNREADABLE[@]} could not be read"
  {
    printf 'No gaps among the %d commits that could be read.\n' "$n_read"
    unreadable_section
    printf '\n_Any open `%s` issue is left as it was: a partial sweep is not evidence that a gap has cleared._\n' \
      "$ISSUE_LABEL"
  } >>"$summary_file"
  exit 3
fi

if [ "${#GAPS[@]}" -eq 0 ]; then
  note "release-audit: OK — every commit on ${BRANCH} whose Release ran has an image or is accepted."
  printf 'Every commit whose Release ran has an image, or is accepted in `%s`. Nothing to do.\n' \
    "$ACCEPT_FILE" >>"$summary_file"

  # Close a previously-filed issue rather than leaving it open once it is untrue. An alert that
  # stays up after the condition clears is an alert people learn to skip.
  existing="$(open_audit_issues_soft)"
  for num in $existing; do
    case "$num" in '' | *[!0-9]*) continue ;; esac
    note "release-audit: closing #${num} — the gap it reported is gone"
    # Deliberately not "every commit now has an image": with a non-empty accept list some of them do
    # not, and somebody reading the closure a month later should not be told they do.
    gh issue comment "$num" --repo "$GITHUB_REPOSITORY" \
      --body "Reconciled at $(date -u +%Y-%m-%dT%H:%M:%SZ): every commit on \`${BRANCH}\` whose Release ran now has an image in \`${REGISTRY}/${IMAGE_PATH}\`, or is accepted in \`${ACCEPT_FILE}\`. Closing." >/dev/null
    gh issue close "$num" --repo "$GITHUB_REPOSITORY" >/dev/null
  done
  exit 0
fi

# --- Gaps: file or update the issue ------------------------------------------------------------------

body_file="$(mktemp)"
{
  printf '**%d commit(s) on `%s` had a Release run that produced no image in `%s/%s`.**\n\n' \
    "${#GAPS[@]}" "$BRANCH" "$REGISTRY" "$IMAGE_PATH"
  printf 'Each one is on `%s` and each one is **not a rollback target**: `TAG=<sha> ./deploy.sh --channel github`\n' "$BRANCH"
  printf 'will fail to resolve rather than deploying something older. (`--skip-build` without a channel\n'
  printf 'would fail too, but on the private registry rather than on this gap — see hc-admin-ci deploy.sh.)\n'
  printf 'Nothing is broken by this until somebody needs to roll back to one of them, which is when it\n'
  printf 'is least affordable to find out.\n\n'
  printf '| Commit | Run ended | When | Subject |\n| --- | --- | --- | --- |\n'
  for g in "${GAPS[@]}"; do
    IFS=$'\t' read -r sha concl created run_id run_url subject <<<"$g"
    printf '| [`%s`](%s/%s/commit/%s) | [%s](%s) | %s | %s |\n' \
      "${sha:0:12}" "${GITHUB_SERVER_URL:-https://github.com}" "$GITHUB_REPOSITORY" "$sha" \
      "$concl" "$run_url" "$created" "$subject"
  done
  unreadable_section
  if [ -n "$TRUNCATED_NOTE" ]; then
    printf '\n> ⚠ **Incomplete sweep.** %s\n\n' "$TRUNCATED_NOTE"
  fi
  if [ "${#ACCEPT_UNRESOLVED[@]}" -gt 0 ]; then
    printf '\n> ⚠ **%d entr(y/ies) in `%s` name nothing in this repository and accept nothing:** `%s`\n\n' \
      "${#ACCEPT_UNRESOLVED[@]}" "$ACCEPT_FILE" "${ACCEPT_UNRESOLVED[*]}"
  fi
  printf '\n### What to do with each\n\n'
  printf 'Re-run the original run, which rebuilds **that commit** with **that commit'"'"'s** workflow file:\n\n'
  printf '```bash\ngh run rerun <run-id> --repo %s\n```\n\n' "$GITHUB_REPOSITORY"
  printf '`gh workflow run release.yml --ref <sha>` is **not** an alternative: `workflow_dispatch`\n'
  printf 'takes a branch or tag, never a commit SHA, so it cannot address one of these directly.\n\n'
  printf 'Two things to know before re-running an old one. The deployment checkout in `release.yml`\n'
  printf 'carries no `ref:`, so it takes hc-admin-ci'"'"'s **current** default branch — an old commit\n'
  printf 'rebuilt today is that application inside today'"'"'s Dockerfile and nginx config, which is not\n'
  printf 'the image that would have been published at the time. And GitHub'"'"'s re-run window is bounded\n'
  printf 'by log retention, so old enough runs cannot be re-run at all.\n\n'
  printf 'If a gap is not worth closing, say so in `%s` — one line, sha and reason. A full\n' "$ACCEPT_FILE"
  printf '40-character SHA or any prefix git can resolve; the 12 characters in the links above are\n'
  printf 'enough. This issue will stop reporting it and the decision will be in the history with a\n'
  printf 'date on it.\n\n'
  printf -- '---\n_Filed by `.github/workflows/release-audit.yml`. Identified by the `%s` label, updated in place on every run, and closed automatically when the last gap clears._\n' \
    "$ISSUE_LABEL"
} >"$body_file"

cat "$body_file" >>"$summary_file"

ensure_label
existing="$(open_audit_issues | head -n1)"

if [ -n "$existing" ]; then
  note "release-audit: updating existing issue #${existing} (${#GAPS[@]} gaps)"
  gh issue edit "$existing" --repo "$GITHUB_REPOSITORY" --body-file "$body_file" >/dev/null
else
  note "release-audit: opening an issue (${#GAPS[@]} gaps)"
  gh issue create --repo "$GITHUB_REPOSITORY" --title "$ISSUE_TITLE" --label "$ISSUE_LABEL" \
    --body-file "$body_file" >/dev/null
fi

# Non-zero so the run itself is red as well. The issue is the durable half — it survives the run,
# it can be closed, and its ABSENCE is checkable — but a red run is what makes the Actions tab agree
# with it. Deliberately after the issue is filed, so a gap is recorded even if this line is what a
# reader reacts to.
note "release-audit: ${#GAPS[@]} commit(s) on ${BRANCH} have no image"
exit 1
