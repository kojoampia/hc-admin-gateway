// Prettier's scope is declared twice — here, and in package.json's `prettier:check` /
// `prettier:format`. **The two globs are deliberately identical**, and deliberately reach
// everything Prettier can format, generator-owned files under `.jhipster/` included.
// They disagreed until 2026-09-03: this file globbed `{,**/}*.{…}` while package.json
// globbed `{,src/**/,.blueprint/**/}*.{…}`, so an entity config sat in whatever style it
// was last written in until the first commit that touched it — at which point this hook
// reformatted the whole file. A one-line addition to `api/.jhipster/Vendor.json` produced
// a 56-line staged diff, which makes entity-config changes unreviewable.
//
// **That divergence is JHipster 8.x's, and 9.x has already resolved it — the other way.**
// 8.x shipped two unrelated strings: `common/templates/.lintstagedrc.cjs.ejs` globbed
// `{,**/}*` while `server/templates/package.json.ejs` globbed `{,src/**/,.blueprint/**/}*`.
// The 9.2.0 that `package.json`, the lockfile and `.yo-rc.json` all pin builds both from a
// single `prettierFolders` array — declared in
// `javascript-simple-application/application.js:7`, read by
// `.../generators/prettier/generator.js:91-95` for the scripts and by this file's own
// template — and that array is the **narrow** form. So upstream reconciled the two globs
// onto the string these repos have just moved away from.
//
// **Widening is a local judgement between two self-consistent answers, then, not a repair
// of a broken one.** Both directions give one rule; they differ over whether `.jhipster/`
// is in scope. It is widened here because Prettier's output *is* the generator's output —
// JHipster pipes every file it writes through Prettier using this repo's own `.prettierrc`
// (`createPrettierTransform` in the bootstrap generator, queued after
// `createSortConfigFilesTransform`, whose pattern names `.jhipster/*.json` explicitly) — so
// narrowing does not preserve "generator style", it leaves those files formatted by nothing
// local and free to drift from the only style a regeneration will ever produce. That was
// already `app/`'s state, where both globs agreed on narrow and `ServicePlan.json` had
// drifted 8 of 27 lines with nothing reporting it. The noisy diff is not avoided by
// narrowing; it arrives later, from the generator instead of from this hook.
//
// **A regeneration restores the narrow glob in both files — including this one.** The
// package.json scripts are rewritten by the prettier generator named above, and this file
// is rewritten by `.../generators/husky/generator.js:45-46`, whose `writeFiles` call is
// unconditional within its `writing` task. So this comment is itself regenerable and is the
// likelier casualty of the two. If a regeneration shrinks either glob, widen both back.
// docs/backlog.md item 10 carries the full reasoning and the measurements.
module.exports = {
  '{,**/}*.{md,json,yml,html,java}': ['prettier --write'],
};
