# How to write a new-PC handoff (GitHub-only agent)

Use this when the next agent is on another machine, can clone the repo from GitHub, and **cannot** see local-only files (`Roadmap/`, logs, screenshots, unpushed edits).

`Roadmap/` is gitignored. Anything left only there must be **pasted into chat** (or committed somewhere the agent can read).

---

## Goal

One pasteable markdown note that lets the agent:

1. Understand the problem without opening local logs/images.
2. Find the right code on GitHub.
3. Know what was already tried and failed.
4. Propose a fix (or plan) without assuming prior chat conclusions.

**Hard limit:** **≤ 5000 characters** for the pasteable handoff (many agent/chat limits). Aim ~4000–4900 so you have headroom.

---

## Checklist before writing

- [ ] Confirm what is on GitHub (`git status`, `git log -1`, remote URL). Unpushed work is invisible to them unless you paste a diff.
- [ ] Confirm failed commits: still on a branch, or force-pushed away? If gone, summarize the diff in the handoff.
- [ ] List evidence only you have: logs, screenshots, device notes.
- [ ] Decide redaction: real folder/media names, emails, tokens → placeholders (`<FolderA>`, `<Video1.mp4>`).
- [ ] Pick baseline commit hash the agent should open.

---

## Required sections (pasteable handoff)

### 1. Header (who / what)

- Date, repo name, branch/commit tip.
- Audience: “GitHub code only; no local logs.”
- Instruction: facts + failed attempts only; **decide the fix yourself**; re-verify in code.

### 2. History

| Item | Include |
|------|---------|
| Baseline tip | Full commit hash + short subject |
| Failed / reverted | Hash, subject, force-push note if history was rewritten |
| Scope of failed diff | Which files (+/− lines) |

Explicit: “Do not re-apply X as-is.”

### 3. Symptoms (user words)

Numbered problems (A/B/C…). For each:

- What they saw.
- When it happens (scroll, restore, cold start…).
- What recovers it (if anything).

### 4. Inlined evidence (this replaces log/image access)

**Logs:** 5–15 redacted lines that show the pattern (bind branch, recycle/`holderHash`, errors, stale callbacks). Prefer real event shapes from the app (`event=bind`, `branch=…`, HTTP status).

**Screenshots:** one sentence each (“cell X blank dark square; neighbors loaded”). Do not rely on file paths the agent cannot open.

**Do not** write “see `log_….log`” as the only evidence.

### 5. Code to open on GitHub

- Full-enough paths under the repo (or unambiguous `…/Package/File.java`).
- Key symbols: methods/classes to read first.
- One-line behavior notes that matter (e.g. “`cancel()` only clears Handler runnable”).

### 6. Failed attempts

For each try:

- Intent.
- What changed (behavioral bullets, not a novel).
- User outcome (fixed / worse / unchanged).
- Label prior theories **Unverified — re-check** so they are not treated as truth.

### 7. Constraints

- Hardware (e.g. no local Gradle on Surface Go → CI APK).
- Privacy (redact names in replies).
- Product scope flags (“also wants folder thumbs”).
- “Propose solution from code + evidence above.”

---

## Character budget tips

| Spend on | Cut |
|----------|-----|
| Symptoms + inlined logs + failed attempt | Long architecture essays |
| Exact commit hashes | Duplicate explanations |
| Code entry points | Full file dumps |
| Redacted log lines | “See screenshot folder” |

If over 5000: move narrative to a local `*_detailed.md`; keep the pasteable file self-contained.

`wc -m handoff.md` — must be ≤ 5000.

---

## Transfer procedure

1. Write `handoff_….md` (local Roadmap OK).
2. Verify `wc -m` ≤ 5000.
3. **Paste entire file** into the new agent’s first message (plus “clone / use commit HASH”).
4. Optional: push a branch with only the handoff if you want it in-repo (not required if pasted).
5. Do not assume they have `Roadmap/`, device logs, or your unpushed working tree.

---

## Anti-patterns

- Handoff that only links to gitignored paths.
- Assuming they can see a force-pushed commit without a summary/diff.
- Shipping a “recommended fix” when you asked them to decide (unless you want that).
- Over-redacting until logs lose meaning (keep event names, branches, status codes).
- Pasting 20k chars of raw log — sample the pattern instead.

---

## Minimal template

```markdown
# Handoff: <title> (self-contained)

**Date:** … | **Repo:** … `@` `<commit>` | Facts + failed attempts; decide the fix.
**Audience:** GitHub only. Evidence inlined (names redacted).

## History
| master tip | `<hash>` |
| Removed / failed | `<hash>` — <subject> |

## Symptoms
**A:** …
**B:** …

## Inlined log evidence (redacted)
```
<5–15 lines>
```
Screenshots (words): …

## Code on GitHub
- `path/File.java` — `methodA`, `methodB` (note: …)

## Failed attempt
**Intent:** … **Did:** … **Result:** …
Unverified notes: … — re-check.

## Constraints
…
```

---

## Example from this project

Scroll-thumbnail blanking pasteable handoff (pattern to copy):

`Roadmap/backup restore thumbnail bug/thumbnail disappears as I scroll through, and it comes back after/handoff_scroll_thumbnail_disappear.md`

Detailed local-only archive (not for paste limits):

`…/handoff_scroll_thumbnail_disappear_detailed.md`
