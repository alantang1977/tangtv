#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  task_guard.sh start --id ID --mode MODE --scope PATH [--scope PATH ...]
                      [--adopt-dirty PATH ...]
  task_guard.sh check [--cycle]
  task_guard.sh replan --reason TEXT --next TEXT
  task_guard.sh checkpoint [--document PATH] --message TEXT --next TEXT
  task_guard.sh finish --verified EVIDENCE --commit-message MESSAGE
  task_guard.sh status

Modes: quick-fix, standard, assessment, upstream

Exit codes: 0 pass, 2 usage/setup error, 3 checkpoint required,
4 safety gate (scope, dirty-file, branch, or HEAD violation),
5 replan required; continue the same task after checkpoint/replan,
6 commit created but recovery-tag creation needs a direct fix and one retry.
EOF
}

fail_usage() {
  printf 'ERROR: %s\n' "$1" >&2
  usage >&2
  exit 2
}

safety_gate() {
  printf 'SAFETY_GATE: %s\n' "$1" >&2
  exit 4
}

replan_required() {
  printf 'REPLAN_REQUIRED_CONTINUE: %s\n' "$1" >&2
  printf 'ACTION_REQUIRED: checkpoint if needed, run replan with a materially different next action, then continue the same task.\n' >&2
  exit 5
}

repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || fail_usage "not inside a Git worktree"
cd "$repo_root"

state_root="$repo_root/.codex/task-state"
state_dir="$state_root/current"

normalize_path() {
  local value="$1"
  while [[ "$value" == ./* ]]; do value="${value#./}"; done
  while [[ "$value" == */ && "$value" != "/" ]]; do value="${value%/}"; done
  case "$value" in
    ""|.|/|/*|..|../*|*/../*|*/..)
      return 1
      ;;
  esac
  printf '%s\n' "$value"
}

path_matches_file() {
  local path="$1"
  local list_file="$2"
  local pattern
  [[ -f "$list_file" ]] || return 1
  while IFS= read -r pattern; do
    [[ -n "$pattern" ]] || continue
    if [[ "$path" == "$pattern" || "$path" == "$pattern"/* ]]; then
      return 0
    fi
  done < "$list_file"
  return 1
}

list_dirty() {
  local output="$1"
  {
    git diff --name-only
    git diff --cached --name-only
    git ls-files --others --exclude-standard
  } | LC_ALL=C sort -u > "$output"
}

list_staged() {
  git diff --cached --name-only | LC_ALL=C sort -u > "$1"
}

fingerprint_path() {
  local path="$1"
  local work_hash="MISSING"
  local index_hash
  local status_hash
  if [[ -e "$path" || -L "$path" ]]; then
    work_hash="$(git hash-object --no-filters -- "$path" 2>/dev/null || printf 'UNHASHABLE')"
  fi
  index_hash="$(git ls-files -s -- "$path" | git hash-object --stdin)"
  status_hash="$(git status --porcelain=v1 -- "$path" | git hash-object --stdin)"
  printf '%s\n%s\n%s\n' "$work_hash" "$index_hash" "$status_hash" | git hash-object --stdin
}

read_state() {
  [[ -f "$state_dir/$1" ]] || fail_usage "no active task guard state ($1 missing)"
  sed -n '1p' "$state_dir/$1"
}

write_state() {
  printf '%s\n' "$2" > "$state_dir/$1"
}

mode_defaults() {
  case "$1" in
    quick-fix) printf '%s %s %s\n' 15 4 2 ;;
    standard) printf '%s %s %s\n' 30 8 3 ;;
    assessment) printf '%s %s %s\n' 45 6 3 ;;
    upstream) printf '%s %s %s\n' 45 16 3 ;;
    *) return 1 ;;
  esac
}

show_status() {
  [[ -d "$state_dir" ]] || fail_usage "no task guard state"
  printf 'Task: %s\n' "$(read_state id)"
  printf 'Mode: %s\n' "$(read_state mode)"
  printf 'Status: %s\n' "$(read_state status)"
  printf 'Branch: %s\n' "$(read_state branch)"
  printf 'Base HEAD: %s\n' "$(read_state base_head)"
  printf 'Cycles: %s/%s\n' "$(read_state cycles)" "$(read_state max_cycles)"
  printf 'Start/interval-end: %s / %s\n' "$(read_state start_epoch)" "$(read_state interval_end_epoch)"
  printf '%s\n' 'Scope:'
  sed 's/^/  - /' "$state_dir/scope"
  if [[ -s "$state_dir/adopted" ]]; then
    printf '%s\n' 'Explicitly adopted initial dirty paths:'
    sed 's/^/  - /' "$state_dir/adopted"
  fi
}

scope_check() {
  local enforce_interval="$1"
  local count_cycle="$2"
  local expected_branch
  local expected_head
  local current_branch
  local current_head
  local current_dirty="$state_dir/current-dirty.tmp"
  local current_staged="$state_dir/current-staged.tmp"
  local task_dirty="$state_dir/task-dirty.tmp"
  local path
  local saved_hash
  local current_hash
  local violations=0

  [[ "$(read_state status)" == "active" ]] || fail_usage "task is not active"
  expected_branch="$(read_state branch)"
  expected_head="$(read_state base_head)"
  current_branch="$(git branch --show-current)"
  current_head="$(git rev-parse HEAD)"

  [[ "$current_branch" == "$expected_branch" ]] || safety_gate "branch changed: expected $expected_branch, got $current_branch"
  [[ "$current_head" == "$expected_head" ]] || safety_gate "HEAD changed outside task_guard finish: expected $expected_head, got $current_head"

  while IFS=$'\t' read -r path saved_hash; do
    [[ -n "$path" ]] || continue
    current_hash="$(fingerprint_path "$path")"
    if [[ "$current_hash" != "$saved_hash" ]]; then
      printf 'SCOPE_VIOLATION: protected initial dirty path changed: %s\n' "$path" >&2
      violations=$((violations + 1))
    fi
  done < "$state_dir/protected-fingerprints"

  list_dirty "$current_dirty"
  : > "$task_dirty"
  while IFS= read -r path; do
    [[ -n "$path" ]] || continue
    if path_matches_file "$path" "$state_dir/protected"; then
      continue
    fi
    if ! path_matches_file "$path" "$state_dir/scope"; then
      printf 'SCOPE_VIOLATION: changed path is outside declared scope: %s\n' "$path" >&2
      violations=$((violations + 1))
      continue
    fi
    printf '%s\n' "$path" >> "$task_dirty"
  done < "$current_dirty"
  LC_ALL=C sort -u "$task_dirty" -o "$task_dirty"

  list_staged "$current_staged"
  while IFS= read -r path; do
    [[ -n "$path" ]] || continue
    if path_matches_file "$path" "$state_dir/protected" || ! path_matches_file "$path" "$state_dir/scope"; then
      printf 'SCOPE_VIOLATION: staged path is not task-owned: %s\n' "$path" >&2
      violations=$((violations + 1))
    fi
  done < "$current_staged"

  if ((violations > 0)); then
    safety_gate "$violations scope/worktree violation(s); checkpoint and reconcile before continuing"
  fi

  local changed_count
  local max_files
  changed_count="$(wc -l < "$task_dirty" | tr -d ' ')"
  max_files="$(read_state max_files)"
  if ((changed_count > max_files)); then
    safety_gate "task-owned changed files $changed_count exceed mode limit $max_files"
  fi

  if [[ "$count_cycle" == "1" ]]; then
    local cycles
    cycles=$(( $(read_state cycles) + 1 ))
    write_state cycles "$cycles"
  fi

  local now
  local start
  local interval_end
  local threshold
  local cycles
  local max_cycles
  now="$(date +%s)"
  start="$(read_state start_epoch)"
  interval_end="$(read_state interval_end_epoch)"
  threshold="$(read_state checkpoint_threshold_epoch)"
  cycles="$(read_state cycles)"
  max_cycles="$(read_state max_cycles)"

  if [[ "$enforce_interval" == "1" ]]; then
    if ((cycles > max_cycles)); then
      replan_required "diagnosis/edit cycles $cycles exceed the current route limit $max_cycles; checkpoint and choose a materially different path"
    fi
    if ((now >= threshold)); then
      local checkpoint_epoch
      checkpoint_epoch="$(read_state checkpoint_epoch)"
      if ((checkpoint_epoch < threshold)); then
        printf 'CHECKPOINT_REQUIRED: 70%% of the review interval has elapsed; persist state before more exploration\n' >&2
        exit 3
      fi
    fi
  fi

  printf 'PASS: task=%s elapsed=%sm changed=%s/%s cycles=%s/%s\n' \
    "$(read_state id)" "$(( (now - start) / 60 ))" "$changed_count" "$max_files" "$cycles" "$max_cycles"
}

start_task() {
  local id=""
  local mode=""
  local -a scopes=()
  local -a adopted=()
  local value

  while (($# > 0)); do
    case "$1" in
      --id) (($# >= 2)) || fail_usage "--id needs a value"; id="$2"; shift 2 ;;
      --mode) (($# >= 2)) || fail_usage "--mode needs a value"; mode="$2"; shift 2 ;;
      --scope) (($# >= 2)) || fail_usage "--scope needs a value"; scopes+=("$2"); shift 2 ;;
      --adopt-dirty) (($# >= 2)) || fail_usage "--adopt-dirty needs a value"; adopted+=("$2"); shift 2 ;;
      *) fail_usage "unknown start option: $1" ;;
    esac
  done

  [[ "$id" =~ ^[A-Za-z0-9._-]+$ ]] || fail_usage "task id must use letters, digits, dot, underscore, or hyphen"
  [[ ${#scopes[@]} -gt 0 ]] || fail_usage "at least one --scope is required"
  local defaults
  defaults="$(mode_defaults "$mode")" || fail_usage "unknown mode: $mode"

  if [[ -d "$state_dir" && -f "$state_dir/status" ]]; then
    local previous_status
    previous_status="$(sed -n '1p' "$state_dir/status")"
    if [[ "$previous_status" == "active" || "$previous_status" == "commit_needs_tag" ]]; then
      fail_usage "another task guard is unfinished: $(sed -n '1p' "$state_dir/id") ($previous_status)"
    fi
  fi
  mkdir -p "$state_root"
  if [[ -d "$state_dir" ]]; then
    local archive_dir
    local previous_id="partial"
    if [[ -f "$state_dir/id" ]]; then
      previous_id="$(sed -n '1p' "$state_dir/id")"
    fi
    archive_dir="$state_root/archive/$previous_id-$(TZ=Asia/Shanghai date +%Y%m%d%H%M%S)-$$"
    mkdir -p "$state_root/archive"
    mv -- "$state_dir" "$archive_dir"
  fi
  mkdir -p "$state_dir"
  : > "$state_dir/scope"
  : > "$state_dir/adopted"

  for value in "${scopes[@]}"; do
    value="$(normalize_path "$value")" || fail_usage "unsafe scope path: $value"
    printf '%s\n' "$value" >> "$state_dir/scope"
  done
  LC_ALL=C sort -u "$state_dir/scope" -o "$state_dir/scope"

  for value in "${adopted[@]-}"; do
    [[ -n "$value" ]] || continue
    value="$(normalize_path "$value")" || fail_usage "unsafe adopted path: $value"
    if ! path_matches_file "$value" "$state_dir/scope"; then
      fail_usage "adopted path is outside scope: $value"
    fi
    printf '%s\n' "$value" >> "$state_dir/adopted"
  done
  LC_ALL=C sort -u "$state_dir/adopted" -o "$state_dir/adopted"

  list_dirty "$state_dir/initial-dirty"
  list_staged "$state_dir/initial-staged"
  local adopted_match
  while IFS= read -r value; do
    [[ -n "$value" ]] || continue
    if path_matches_file "$value" "$state_dir/scope" && ! path_matches_file "$value" "$state_dir/adopted"; then
      fail_usage "scope overlaps pre-existing dirty path without --adopt-dirty: $value"
    fi
  done < "$state_dir/initial-dirty"

  while IFS= read -r value; do
    [[ -n "$value" ]] || continue
    adopted_match=0
    local dirty_path
    while IFS= read -r dirty_path; do
      if [[ "$dirty_path" == "$value" || "$dirty_path" == "$value"/* ]]; then
        adopted_match=1
        break
      fi
    done < "$state_dir/initial-dirty"
    ((adopted_match == 1)) || fail_usage "--adopt-dirty path has no initial dirty match: $value"
  done < "$state_dir/adopted"

  while IFS= read -r value; do
    [[ -n "$value" ]] || continue
    if ! path_matches_file "$value" "$state_dir/adopted"; then
      fail_usage "pre-existing staged path would contaminate an automatic commit: $value"
    fi
  done < "$state_dir/initial-staged"

  : > "$state_dir/protected"
  : > "$state_dir/protected-fingerprints"
  while IFS= read -r value; do
    [[ -n "$value" ]] || continue
    if ! path_matches_file "$value" "$state_dir/adopted"; then
      printf '%s\n' "$value" >> "$state_dir/protected"
      printf '%s\t%s\n' "$value" "$(fingerprint_path "$value")" >> "$state_dir/protected-fingerprints"
    fi
  done < "$state_dir/initial-dirty"

  local minutes max_files max_cycles now
  read -r minutes max_files max_cycles <<< "$defaults"
  now="$(date +%s)"
  write_state id "$id"
  write_state mode "$mode"
  write_state status active
  write_state branch "$(git branch --show-current)"
  write_state base_head "$(git rev-parse HEAD)"
  write_state start_epoch "$now"
  write_state interval_end_epoch "$((now + minutes * 60))"
  write_state checkpoint_threshold_epoch "$((now + minutes * 60 * 70 / 100))"
  write_state checkpoint_epoch 0
  write_state max_files "$max_files"
  write_state max_cycles "$max_cycles"
  write_state cycles 0

  show_status
  printf 'PASS: task guard started; protected %s pre-existing dirty path(s)\n' "$(wc -l < "$state_dir/protected" | tr -d ' ')"
}

checkpoint_task() {
  local document=""
  local message=""
  local next_action=""
  while (($# > 0)); do
    case "$1" in
      --document) (($# >= 2)) || fail_usage "--document needs a value"; document="$2"; shift 2 ;;
      --message) (($# >= 2)) || fail_usage "--message needs a value"; message="$2"; shift 2 ;;
      --next) (($# >= 2)) || fail_usage "--next needs a value"; next_action="$2"; shift 2 ;;
      *) fail_usage "unknown checkpoint option: $1" ;;
    esac
  done
  [[ -n "$message" && -n "$next_action" ]] || fail_usage "checkpoint message and next action are required"
  if [[ -n "$document" ]]; then
    document="$(normalize_path "$document")" || fail_usage "unsafe checkpoint document path"
    path_matches_file "$document" "$state_dir/scope" || safety_gate "checkpoint document is outside declared scope: $document"
    [[ -f "$document" ]] || safety_gate "checkpoint document does not exist: $document"
    rg -Fq -- "$message" "$document" || safety_gate "checkpoint document does not contain the supplied completion message"
    rg -Fq -- "$next_action" "$document" || safety_gate "checkpoint document does not contain the supplied next action"
  else
    document="-"
  fi
  scope_check 0 0
  local now
  local minutes
  now="$(date +%s)"
  case "$(read_state mode)" in
    quick-fix) minutes=15 ;;
    standard) minutes=30 ;;
    assessment|upstream) minutes=45 ;;
    *) fail_usage "invalid stored mode" ;;
  esac
  write_state checkpoint_epoch "$now"
  write_state start_epoch "$now"
  write_state interval_end_epoch "$((now + minutes * 60))"
  write_state checkpoint_threshold_epoch "$((now + minutes * 60 * 70 / 100))"
  printf '%s\t%s\t%s\t%s\n' "$now" "$document" "$message" "$next_action" >> "$state_dir/checkpoints.log"
  printf 'PASS: checkpoint recorded from %s; review cadence reset and task remains active\n' "$document"
}

replan_task() {
  local reason=""
  local next_action=""
  while (($# > 0)); do
    case "$1" in
      --reason) (($# >= 2)) || fail_usage "--reason needs a value"; reason="$2"; shift 2 ;;
      --next) (($# >= 2)) || fail_usage "--next needs a value"; next_action="$2"; shift 2 ;;
      *) fail_usage "unknown replan option: $1" ;;
    esac
  done
  [[ -n "$reason" && -n "$next_action" ]] || fail_usage "replan reason and next action are required"
  scope_check 0 0
  local now
  local minutes
  now="$(date +%s)"
  case "$(read_state mode)" in
    quick-fix) minutes=15 ;;
    standard) minutes=30 ;;
    assessment|upstream) minutes=45 ;;
    *) fail_usage "invalid stored mode" ;;
  esac
  printf '%s\t%s\t%s\n' "$now" "$reason" "$next_action" >> "$state_dir/replans.log"
  write_state start_epoch "$now"
  write_state interval_end_epoch "$((now + minutes * 60))"
  write_state checkpoint_threshold_epoch "$((now + minutes * 60 * 70 / 100))"
  write_state checkpoint_epoch "$now"
  write_state cycles 0
  printf 'PASS: review interval reset after replan; next=%s\n' "$next_action"
}

create_recovery_tag() {
  local commit="$1"
  local verified="$2"
  local stamp
  local tag
  local tag_started
  local tag_finished
  local tag_elapsed

  stamp="$(TZ=Asia/Shanghai date +%Y%m%d%H%M%S)"
  tag="recovery/$(read_state id)/$stamp-${commit:0:12}"
  tag_started="$(date +%s)"
  write_state pending_tag "$tag"
  if ! GIT_OPTIONAL_LOCKS=0 git -c tag.gpgSign=false tag -a "$tag" "$commit" -m "Recovery point for $(read_state id). Verification: $verified"; then
    write_state status commit_needs_tag
    printf 'TAG_NEEDS_DIRECT_FIX: commit %s is safe; tag command failed once and was not repeated. Fix the reported cause, then rerun finish.\n' "$commit" >&2
    exit 6
  fi
  tag_finished="$(date +%s)"
  tag_elapsed="$((tag_finished - tag_started))"
  write_state tag_elapsed_seconds "$tag_elapsed"
  write_state recovery_tag "$tag"
  write_state status finished
  if ((tag_elapsed > 5)); then
    printf 'WARN: recovery tag phase took %ss (target <=5s); tag already created, continue without repeated validation\n' "$tag_elapsed" >&2
  fi
  printf 'PASS: annotated recovery tag %s (%ss)\n' "$tag" "$tag_elapsed"
}

finish_task() {
  local verified=""
  local commit_message=""
  while (($# > 0)); do
    case "$1" in
      --verified) (($# >= 2)) || fail_usage "--verified needs a value"; verified="$2"; shift 2 ;;
      --commit-message) (($# >= 2)) || fail_usage "--commit-message needs a value"; commit_message="$2"; shift 2 ;;
      *) fail_usage "unknown finish option: $1" ;;
    esac
  done
  [[ -n "$verified" && -n "$commit_message" ]] || fail_usage "verification evidence and commit message are required"

  if [[ "$(read_state status)" == "commit_needs_tag" ]]; then
    create_recovery_tag "$(read_state committed_head)" "$(read_state verification)"
    return
  fi
  scope_check 0 0

  local task_dirty="$state_dir/task-dirty.tmp"
  [[ -s "$task_dirty" ]] || safety_gate "no task-owned changes to commit"
  local path
  while IFS= read -r path; do
    [[ -n "$path" ]] || continue
    git add -A -- "$path"
  done < "$task_dirty"

  git diff --cached --check || safety_gate "staged task diff failed git diff --cached --check"
  local staged="$state_dir/finish-staged.tmp"
  list_staged "$staged"
  [[ -s "$staged" ]] || safety_gate "task changes did not produce a staged diff"
  while IFS= read -r path; do
    [[ -n "$path" ]] || continue
    if ! path_matches_file "$path" "$state_dir/scope" || path_matches_file "$path" "$state_dir/protected"; then
      safety_gate "automatic commit would include non-task path: $path"
    fi
  done < "$staged"

  local message_file="$state_dir/commit-message.txt"
  {
    printf '%s\n\n' "$commit_message"
    printf 'Verification: %s\n' "$verified"
    printf 'Task-Guard: %s\n' "$(read_state id)"
  } > "$message_file"
  git commit -F "$message_file"
  local commit
  commit="$(git rev-parse HEAD)"
  write_state committed_head "$commit"
  write_state verification "$verified"
  write_state status commit_needs_tag
  printf 'PASS: committed %s\n' "$commit"
  create_recovery_tag "$commit" "$verified"
}

command_name="${1:-}"
[[ -n "$command_name" ]] || { usage; exit 2; }
shift

case "$command_name" in
  start) start_task "$@" ;;
  check)
    count_cycle=0
    if (($# > 0)); then
      [[ "$1" == "--cycle" && $# == 1 ]] || fail_usage "check accepts only --cycle"
      count_cycle=1
    fi
    scope_check 1 "$count_cycle"
    ;;
  checkpoint) checkpoint_task "$@" ;;
  replan) replan_task "$@" ;;
  finish) finish_task "$@" ;;
  status) (($# == 0)) || fail_usage "status accepts no options"; show_status ;;
  -h|--help|help) usage ;;
  *) fail_usage "unknown command: $command_name" ;;
esac
