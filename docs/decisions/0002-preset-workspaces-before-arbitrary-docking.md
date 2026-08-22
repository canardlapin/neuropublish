# ADR 0002: Use preset workspaces before arbitrary docking

Status: accepted (2026-08-22)

Date: 2026-08-22

Scope clarified: 2026-08-22 (volume-only MVP versus later preset additions)

## Context

The scientific workspace needs volume, surface, hybrid, and eventually compare
layouts. Panels must resize, saved views must restore their arrangement, and
canvas/WebGL resources must survive ordinary interaction. Arbitrary tab
docking, floating panels, and popout windows are attractive, but they add a
second state machine, drag and focus semantics, accessibility work, browser
window lifecycle, and renderer reparenting risk.

The Scala workspace already contains `scaladock`, whose immutable layout tree,
pure edits, and persistence model are a good conceptual fit. Its current host
is JavaFX and its build is JVM-only. It is not yet a browser dependency.

The vendor observations below are as of 2026-08-22 and will age; the
durable rationale is lifecycle, accessibility, and scope, not any library's
current release state.

GoldenLayout is a credible JavaScript layout manager, not a bad library. Its
current stable line supports saved layouts, virtual components, and popouts.
However, its repository also describes the future v3 development branch as
unstable, and its popout documentation leaves application state propagation
between windows to the application. That is additional risk around two
stateful scientific renderers.

Dockview's current documentation offers a framework-neutral JavaScript package,
serialization, floating and popout groups, and explicit keyboard and
screen-reader support. It is the stronger vendor candidate if full docking is
needed before a Scala DOM interpreter exists.

## Decision

The workspace vocabulary commits to three Neuropublish-owned layout presets:

- Volume;
- Surface;
- Hybrid.

The volume-only MVP implements the Volume preset. Surface and Hybrid use the
same versioned layout model and arrive in Stage 5 after the first vertical
slice. Compare (two linked result or revision views) is the first extension
after their shared workspace state has been proven to reuse cleanly.

The implementation uses semantic DOM, CSS Grid, and accessible resizable
dividers. DOM order remains stable and meaningful. A versioned open record
(`org.neuropublish.view/workspace-layout@1`, domain type `WorkspaceLayout`)
stores the preset and pane proportions. It does not store a
GoldenLayout or Dockview config.

Arbitrary docking is a post-MVP experiment. The evaluation order is:

1. cross-compile `scaladock-core` and add a DOM/Laminar interpreter if the
   feature deserves ownership in the Scala ecosystem;
2. otherwise wrap Dockview's vanilla API behind a narrow Scala.js facade and
   translate to and from `WorkspaceLayout`;
3. reconsider GoldenLayout after a fresh maintenance and lifecycle spike.

No docking engine may own scientific layer state, result selection, saved-view
identity, or ScalaFIM renderer state. It only arranges long-lived pane hosts.

## Consequences

### Benefits

- The first release tests the scientific product rather than a miniature
  window manager.
- Saved views remain independent of a vendor's serializer and upgrade path.
- Keyboard order, focus, narrow layouts, and presentation mode are easier to
  make coherent.
- ScalaFIM controllers can retain caches and GPU resources in stable hosts.
- Real usage can determine whether tabs, floating groups, or popouts are
  actually needed.

### Costs

- Power users cannot invent arbitrary layouts in the initial releases.
- The application must implement good split resizing and pane maximization.
- A later docking engine requires a deliberate adapter and migration from the
  preset layout document.

## Revisit criteria

Reopen the decision when at least one observed workflow needs one of these:

- more than the preset number of simultaneous result panes;
- persistent user-created tab groups;
- multi-monitor popout windows;
- analysis-specific panels that cannot fit the inspector or lower drawer;
- repeated manual switching that a saved custom layout would remove.

## Required docking spike

Any later candidate must prove:

- volume and surface panes are reparented or rearranged without losing viewer
  state;
- resize events reach both renderers exactly once per committed layout update;
- no WebGL context, listener, or animation-frame leak occurs after drag,
  close, restore, popout, and route change;
- keyboard navigation and focus restoration work without a pointer;
- serialized vendor state can be translated to the Neuropublish layout model;
- unknown future pane types survive a saved-layout round trip;
- popout failure and popup blocking produce a recoverable in-page state.

## References

- [GoldenLayout repository](https://github.com/golden-layout/golden-layout)
- [GoldenLayout popout behavior and limitations](https://golden-layout.github.io/golden-layout/popouts/)
- [Dockview overview](https://dockview.dev/docs/overview/introduction/)
- [Dockview keyboard and screen-reader support](https://dockview.dev/docs/releases/whats-new/whats-new-v7/)
