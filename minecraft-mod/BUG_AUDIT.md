# MineLedger bug audit

Reviewed the domain models, both graders, selection and progression services, session ownership,
assignment and journal screens, app/desktop navigation, block interaction callbacks, build config,
and mixin configuration. Existing user changes were retained. No computer assets or registration changed.

## Verified issues fixed

1. **High: zoomed controls and unzoomed background used different coordinate systems.**
   Minecraft 26.3's final screen rendering wrapper invokes `extractBackground` separately before
   `extractRenderState`; the latter renders widgets only. The scaled screen previously drew its
   panel at logical coordinates, then painted its backdrop over it. Both hooks now apply the same
   `WindowViewport` transform, with the backdrop drawn before the panel. Native bytecode inspection
   established the lifecycle; interactive rendering verification remains outstanding.
2. **High: removed buttons could retain keyboard focus after mouse callbacks rebuilt the screen.**
   Native click dispatch assigns focus after executing the button callback. `rebuildWidgets` clears
   and replaces controls during that callback, so native dispatch could refocus the removed button.
   `AppScreen` clears orphaned focus and dragging after dispatch. The three app screens use it.
   Submission/advance callbacks also reject actions inconsistent with the current question state.
3. **Medium: mastery tooltips used logical coordinates in an unscaled deferred pass.**
   Native tooltips render after the window pose is popped. Progress tooltips now convert both mouse
   coordinates and widget bounds to screen space using the same tested viewport math.
4. **Medium: ambiguous question banks accepted duplicate visible choices.**
   Four unique IDs could still display the same answer twice. Question construction now rejects
   identical option labels after whitespace and case normalization.
5. **Medium: some journal answer keys were impossible to enter.**
   Questions could require more than two decimal places or more than the input's 32 characters.
   Question construction now rejects unrepresentable amounts, while accepting equivalent amounts
   such as 60.000. Validation, grading and the widget share the same input-length constant.
6. **Medium: replaying a credited question changed selection history.**
   It awarded no extra XP but could alter which missed question came next. Such submissions now
   leave the entire progress snapshot unchanged, even when the replayed answer is incorrect.
7. **Low: Escape from the app skipped its desktop parent.**
   `MineLedgerScreen` inherited the default screen close behavior. It now returns to its existing
   MineOS desktop, matching assignment back-navigation. Escape from MineOS still closes the computer.

## Validation

Run the Java 25 Gradle wrapper build. Regression coverage includes viewport fitting and centering,
round-trip mouse/tooltip transforms across GUI sizes, invalid dimensions, ambiguous answer labels,
unrepresentable answer keys, duplicate submission idempotence, mastery progression, XP and retries.
Native render-hook and click-dispatch behavior was checked against the installed 26.3 classes.
No interactive Minecraft client or dedicated-server smoke test was run for this audit.

## Remaining risks and boundaries

- **Small-window readability:** automatic fitting intentionally has no minimum font scale, so
  very small viewports or unusually long content can become hard to read. This is the tradeoff for
  the requested no-scrollbar behavior; a future accessibility option could offer larger text with paging.
- **No GUI automation:** regression tests cover pure logic and geometry, not actual native widget
  dispatch/rendering. Manually test selection -> Submit -> Space/Enter, Continue -> Space/Enter,
  journal dropdowns after keyboard input, window resizing, bar tooltips and nested Escape navigation.
- **Build reproducibility:** Loom is configured as `1.19-SNAPSHOT`; future dependency resolution
  can change tooling without a source edit. Pinning a stable tooling version should be a separate,
  tested upgrade. Minecraft/Fabric versions were kept unchanged.
- **Unused template mixins:** `ExampleMixin` and `ExampleClientMixin` inject no-op code into required
  Minecraft methods. They add unnecessary coupling to those methods during a future upgrade. They
  were left unchanged; removing both classes and their config references together is a cleanup task.
- **Future content updates:** `LearningSession` intentionally rejects different definitions with
  the same assignment ID during a client session. A future dynamic-content backend needs an explicit
  content revision/migration policy. No backend integration was added.
- **Local-only learning:** the later persistence milestone now saves per-profile progress across restarts
  (see `LOCAL_PROGRESS.md`). It remains shared across worlds for that profile. Client-side answer keys
  and editable local XP are appropriate for practice, not trusted assessment records.
