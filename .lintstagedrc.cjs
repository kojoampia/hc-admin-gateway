// Prettier's scope is declared twice — here, and in package.json's `prettier:check` /
// `prettier:format`. **The two globs are deliberately identical**, and deliberately reach
// everything Prettier can format, generator-owned files under `.jhipster/` included.
// Keep them that way; they disagreed until 2026-09-03, and the disagreement is what
// upstream JHipster ships (its `.lintstagedrc.cjs` template globs `{,**/}*.{…}` while its
// package.json template globs `{,src/**/,.blueprint/**/}*.{…}`), so it is not a local edit
// anyone made and it will not fix itself.
//
// While they disagreed, an entity config sat in whatever style it was last written in
// until the first commit that touched it — at which point this hook reformatted the whole
// file. A one-line addition to `api/.jhipster/Vendor.json` produced a 56-line staged diff,
// which makes entity-config changes unreviewable.
//
// **Widened rather than narrowed, because Prettier's output *is* the generator's output.**
// JHipster pipes every file it writes through Prettier using this repo's own `.prettierrc`
// (`createPrettierTransform` in generator-jhipster's bootstrap generator, queued after
// `createSortConfigFilesTransform`, whose pattern names `.jhipster/*.json` explicitly).
// Confirmed by running `jhipster entities` in a scratch clone of `api/`: it rewrote the two
// entity configs that were not Prettier-clean, to the byte, and restored one that had been
// expanded by hand. Narrowing this glob would have left those files formatted by nothing
// and free to drift from the only style a regeneration will ever produce — the noisy diff
// would just have arrived later, from the generator instead of from this hook.
//
// `jhipster entities` does not rewrite this file, but a full app regeneration can restore
// the stock glob in package.json; if a regeneration ever shrinks it, widen it back.
// docs/backlog.md item 10 carries the full reasoning and the measurements.
module.exports = {
  '{,**/}*.{md,json,yml,html,java}': ['prettier --write'],
};
