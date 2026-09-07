#!/usr/bin/env bash
#
# Reconciles this repository's `main` against the container registry and reports every commit whose
# Release ran and produced no image.
#
# This file is BYTE-IDENTICAL in hc-admin-gateway, hc-admin-service and hc-admin-app. Nothing checks
# that, so if you change one, change all three — it derives everything it needs from
# GITHUB_REPOSITORY rather than carrying a per-repo constant, precisely so that staying identical
# costs nothing. See the note on IMAGE_NAME below for the one coincidence that makes that work.
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
# THE COMMAND-SUBSTITUTION RULE, WHICH THIS FILE IS THE WRONG PLACE TO GET WRONG
# ===================================================================================================
#
# Under `set -euo pipefail`, a bare `x=$(cmd)` makes cmd's exit status the script's exit status. In
# hc-admin-quality/startup.sh that shape meant one failed curl killed --verify outright, discarding
# every earlier finding and skipping the cleanup (CLAUDE.md records the sweep across sixteen of
# them). Here it would be worse in kind rather than in degree: this script's whole purpose is to be
# the thing that speaks up, so dying quietly on a transient read is the one failure mode it must not
# have. Every substitution that can fail goes through must() or is explicitly tested.
#
set -euo pipefail

# --- Configuration ---------------------------------------------------------------------------------

REGISTRY="${REGISTRY:-ghcr.io}"
BRANCH="${BRANCH:-main}"

# Minutes a run is allowed to be in flight before its commit is expected to have an image. Below
# this, an in-progress release is not a gap; above it, a run that never completed is. That upper
# half is deliberate — it is what catches the two runs that have been `queued` for a month.
GRACE_MINUTES="${GRACE_MINUTES:-90}"

ACCEPT_FILE="${ACCEPT_FILE:-.github/release-audit-accepted.txt}"
ISSUE_TITLE="${ISSUE_TITLE:-Release audit: commits on main with no image}"

: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY must be set (owner/name)}"
OWNER="${GITHUB_REPOSITORY%%/*}"
REPO="${GITHUB_REPOSITORY##*/}"

# GHCR rejects an upper-case path component and an account's registered case is not guaranteed, so
# it is folded rather than assumed — the same reason release.yml folds it.
OWNER_LC="$(printf '%s' "$OWNER" | tr '[:upper:]' '[:lower:]')"

# The image is named after the repository in all three of these repos, so deriving it is what lets
# this file stay identical across them. That is a COINCIDENCE THAT WAS CHECKED, not a rule: the api
# repo is hc-admin-service and its image is hc-admin-service, but it is named after the service and
# its directory in the workspace is api/, so the three names agreeing here is luck rather than
# convention. If it ever stops being true, set IMAGE_NAME in the workflow rather than editing this.
# The consequence of getting it wrong is loud and not silent — see the sanity check below.
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

note() { printf '%s\n' "$*"; }

# --- A pull token for the registry -----------------------------------------------------------------
#
# The standard OCI token flow, with the workflow's GITHUB_TOKEN as the credential. Anonymous works
# too while these packages are public, but authenticating means the audit keeps working if one is
# ever made private — where the anonymous form would start reporting every commit as missing.

registry_token() {
  local body
  body="$(must "get a GHCR pull token for ${IMAGE_PATH}" \
    curl -fsS -u "token:${GH_TOKEN}" \
    "https://${REGISTRY}/token?service=${REGISTRY}&scope=repository:${IMAGE_PATH}:pull")"
  printf '%s' "$body" | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])'
}

# 200 = the tag resolves, 404 = it does not, anything else = do not guess. A 5xx must NOT be read as
# a missing image: that would turn a registry blip into a page of false gaps, and the credibility of
# this report is the only thing making anyone act on it.
#
# A 401 is retried ONCE against a fresh token rather than reported. Registry pull tokens are
# short-lived and this sweep is O(commits with a run) — 88 in hc-admin-service today and only ever
# growing — so a token minted at the top will eventually expire partway down. Without this, the
# symptom of that is `exit 2` on an arbitrary commit, which reads as a registry fault and is a clock.
_manifest_raw() {
  curl -sS -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer ${RT}" \
    -H 'Accept: application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.docker.distribution.manifest.v2+json,application/vnd.oci.image.manifest.v1+json' \
    "https://${REGISTRY}/v2/${IMAGE_PATH}/manifests/$1"
}

manifest_status() {
  local code
  code="$(_manifest_raw "$1")" || code=000
  if [ "$code" = 401 ]; then
    note "release-audit: registry token expired mid-sweep, renewing"
    RT="$(registry_token)"
    code="$(_manifest_raw "$1")" || code=000
  fi
  printf '%s' "$code"
}

# --- Which commits are in scope --------------------------------------------------------------------

note "release-audit: ${GITHUB_REPOSITORY} -> ${REGISTRY}/${IMAGE_PATH}, branch ${BRANCH}"

RUNS_JSON="$(must "list Release runs on ${BRANCH}" \
  gh api --paginate -X GET "repos/${GITHUB_REPOSITORY}/actions/workflows/release.yml/runs" \
  -f branch="${BRANCH}" -F per_page=100 --slurp)"

CANDIDATES="$(printf '%s' "$RUNS_JSON" | GRACE_MINUTES="$GRACE_MINUTES" python3 -c '
import json, os, sys, datetime

pages = json.load(sys.stdin)
runs = [r for page in pages for r in page["workflow_runs"]]
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

for sha, e in sorted(by_sha.items(), key=lambda kv: kv[1]["newest"]):
    # In scope once the run has completed, or once it has had longer than the grace period to. The
    # second clause is the one that catches a run stuck queued: it never completes, so a rule keyed
    # on completion alone would exempt it forever.
    if not (e["completed"] or (now - e["newest"]) > grace):
        continue
    r = e["run"]
    print("\t".join([sha, r["conclusion"] or r["status"], r["created_at"], str(r["id"]), r["html_url"]]))
')"

if [ -z "$CANDIDATES" ]; then
  note "release-audit: no Release runs on ${BRANCH} are in scope yet. Nothing to reconcile."
  exit 0
fi

RT="$(registry_token)"
[ -n "$RT" ] || { note "release-audit: empty registry token" >&2; exit 2; }

# The accept list. A commit named here is a gap somebody has decided to live with, and the deciding
# is a COMMIT — dated, reviewable, and attributable — rather than a silence. That is the whole
# difference between this and the state item 44 describes, where eleven gaps went a month without
# anyone knowing they had been accepted, because nobody had.
declare -A ACCEPTED=()
if [ -f "$ACCEPT_FILE" ]; then
  while read -r sha _rest; do
    case "$sha" in '' | '#'*) continue ;; esac
    ACCEPTED["$sha"]=1
  done <"$ACCEPT_FILE"
  note "release-audit: accept list ${ACCEPT_FILE} holds ${#ACCEPTED[@]} commit(s)"
else
  note "release-audit: no accept list at ${ACCEPT_FILE}"
fi

# --- The sweep ---------------------------------------------------------------------------------------

GAPS=()
n_ok=0
n_accepted=0
n_checked=0

while IFS=$'\t' read -r sha concl created run_id run_url; do
  [ -n "$sha" ] || continue
  if [ -n "${ACCEPTED[$sha]:-}" ]; then
    n_accepted=$((n_accepted + 1))
    continue
  fi
  # A commit that is no longer an ancestor of the branch cannot be a rollback target for it, so its
  # missing image is not a gap. `git merge-base` needs the history, hence fetch-depth: 0.
  if ! git merge-base --is-ancestor "$sha" "origin/${BRANCH}" 2>/dev/null; then
    continue
  fi
  n_checked=$((n_checked + 1))
  code="$(manifest_status "$sha")"
  case "$code" in
  200) n_ok=$((n_ok + 1)) ;;
  404)
    subject="$(git log -1 --format=%s "$sha" 2>/dev/null || printf '(subject unavailable)')"
    GAPS+=("${sha}"$'\t'"${concl}"$'\t'"${created}"$'\t'"${run_id}"$'\t'"${run_url}"$'\t'"${subject}")
    ;;
  *)
    note "release-audit: registry answered ${code} for ${sha} — refusing to call that a missing image" >&2
    exit 2
    ;;
  esac
done <<<"$CANDIDATES"

# The wrong-image-name guard, and the reason it is not paranoia. If IMAGE_NAME does not name a real
# package, every single manifest read returns 404 and this script's natural output is "every commit
# on main is a gap" — an alarm so large it reads as broken tooling and gets ignored, which is the
# same end state as no alarm. A configuration error must report as a configuration error.
if [ "$n_checked" -gt 0 ] && [ "$n_ok" -eq 0 ]; then
  note "release-audit: ${n_checked} commits checked and NONE resolved in ${REGISTRY}/${IMAGE_PATH}." >&2
  note "release-audit: that is a wrong image name or a registry outage, not ${n_checked} missing images." >&2
  exit 2
fi

# --- The report --------------------------------------------------------------------------------------

summary_file="${GITHUB_STEP_SUMMARY:-/dev/stdout}"

{
  printf '## Release audit — `%s`\n\n' "$GITHUB_REPOSITORY"
  printf 'Registry: `%s/%s` · branch `%s`\n\n' "$REGISTRY" "$IMAGE_PATH" "$BRANCH"
  printf '| Commits with a Release run | With an image | **Missing an image** | Accepted |\n'
  printf '| --- | --- | --- | --- |\n'
  printf '| %d | %d | **%d** | %d |\n\n' "$n_checked" "$n_ok" "${#GAPS[@]}" "$n_accepted"
} >>"$summary_file"

if [ "${#GAPS[@]}" -eq 0 ]; then
  note "release-audit: OK — every commit on ${BRANCH} whose Release ran has an image."
  printf 'Every commit whose Release ran has an image. Nothing to do.\n' >>"$summary_file"

  # Close a previously-filed issue rather than leaving it open once it is untrue. An alert that
  # stays up after the condition clears is an alert people learn to skip.
  existing="$(gh issue list --repo "$GITHUB_REPOSITORY" --state open --search "\"${ISSUE_TITLE}\" in:title" \
    --json number,title --jq ".[] | select(.title == \"${ISSUE_TITLE}\") | .number" || true)"
  for num in $existing; do
    note "release-audit: closing #${num} — the gap it reported is gone"
    gh issue comment "$num" --repo "$GITHUB_REPOSITORY" \
      --body "Reconciled at $(date -u +%Y-%m-%dT%H:%M:%SZ): every commit on \`${BRANCH}\` whose Release ran now has an image in \`${REGISTRY}/${IMAGE_PATH}\`. Closing." >/dev/null
    gh issue close "$num" --repo "$GITHUB_REPOSITORY" >/dev/null
  done
  exit 0
fi

# --- Gaps: file or update the issue ------------------------------------------------------------------

body_file="$(mktemp)"
{
  printf '**%d commit(s) on `%s` had a Release run that produced no image in `%s/%s`.**\n\n' \
    "${#GAPS[@]}" "$BRANCH" "$REGISTRY" "$IMAGE_PATH"
  printf 'Each one is on `%s` and each one is **not a rollback target**: `TAG=<sha> ./deploy.sh --skip-build`\n' "$BRANCH"
  printf 'will fail to resolve rather than deploying something older. Nothing is broken by this until\n'
  printf 'somebody needs to roll back to one of them, which is when it is least affordable to find out.\n\n'
  printf '| Commit | Run ended | When | Subject |\n| --- | --- | --- | --- |\n'
  for g in "${GAPS[@]}"; do
    IFS=$'\t' read -r sha concl created run_id run_url subject <<<"$g"
    printf '| [`%s`](%s/%s/commit/%s) | [%s](%s) | %s | %s |\n' \
      "${sha:0:12}" "${GITHUB_SERVER_URL:-https://github.com}" "$GITHUB_REPOSITORY" "$sha" \
      "$concl" "$run_url" "$created" "$subject"
  done
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
  printf 'If a gap is not worth closing, say so in `%s` — one line, sha and reason. This\n' "$ACCEPT_FILE"
  printf 'issue will stop reporting it and the decision will be in the history with a date on it.\n\n'
  printf -- '---\n_Filed by `.github/workflows/release-audit.yml`. Updated in place on every run; closed automatically when the last gap clears._\n'
} >"$body_file"

cat "$body_file" >>"$summary_file"

existing="$(gh issue list --repo "$GITHUB_REPOSITORY" --state open --search "\"${ISSUE_TITLE}\" in:title" \
  --json number,title --jq ".[] | select(.title == \"${ISSUE_TITLE}\") | .number" | head -n1 || true)"

if [ -n "$existing" ]; then
  note "release-audit: updating existing issue #${existing} (${#GAPS[@]} gaps)"
  gh issue edit "$existing" --repo "$GITHUB_REPOSITORY" --body-file "$body_file" >/dev/null
else
  note "release-audit: opening an issue (${#GAPS[@]} gaps)"
  gh issue create --repo "$GITHUB_REPOSITORY" --title "$ISSUE_TITLE" --body-file "$body_file" >/dev/null
fi

# Non-zero so the run itself is red as well. The issue is the durable half — it survives the run,
# it can be closed, and its ABSENCE is checkable — but a red run is what makes the Actions tab agree
# with it. Deliberately after the issue is filed, so a gap is recorded even if this line is what a
# reader reacts to.
note "release-audit: ${#GAPS[@]} commit(s) on ${BRANCH} have no image"
exit 1
