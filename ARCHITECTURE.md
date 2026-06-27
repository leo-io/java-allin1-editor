# Architecture

## Shape

This project is intentionally a modular monolith. It keeps one deployable JAR
while enforcing inward dependencies:

```text
bootstrap -> Swing adapter -> application -> model/domain
bootstrap -> JSON/Java Sound adapters -> ports <- application
```

The composition root is `AudioAnalysisEditorApplication`. No framework-based
dependency injection is used because constructor injection is sufficient for
the current size.

## Responsibilities

- `model` owns the editable analysis aggregate and its invariants. Nested
  collections are read-only outside the package.
- `application.editing.ProjectEditor` is the single boundary for UI edits.
- `application.EditorSession` owns dirty state and current-document identity.
- `application.project.ProjectFileController` owns open/save workflows.
- `application.playback.PlaybackCoordinator` derives metronome and playable
  schedules from typed project changes.
- `port` contains contracts owned by the application.
- `io`, `audio`, `tools`, and `ui` are adapters. They may depend inward; inward
  packages must not import them.

`ArchitectureBoundaryTest` makes these dependency rules executable.

## Thread ownership

- Swing components and attached models are created and updated on the EDT.
- JSON and WAV loading run in `SwingWorker` background tasks.
- `PcmWavPlaybackEngine` owns one high-priority platform thread and one
  `SourceDataLine`. The pump thread alone performs blocking device writes.
- Control requests cross into the pump through volatile state and one wake-up
  monitor. Immutable arrays are published wholesale for beat/range schedules.
- Pure range mapping and PCM mixing have no thread or device dependencies.

## Change workflow

1. Add or update a characterization test.
2. Make the smallest change through an application boundary.
3. Keep adapters replaceable through ports.
4. Run `mvn clean test`; architecture tests are part of the normal suite.
5. Update this document when a dependency direction or thread owner changes.

## Module policy

Packages are the current module boundary because the codebase is still compact.
If independent release or reuse becomes necessary, the prepared split is:

- `editor-core`: model, domain, application, and ports; no external dependency.
- `editor-desktop`: Swing, Java Sound, JSON adapter, and bootstrap.
- `editor-tools`: conversion and repair command-line adapters.

Do not create these Maven modules merely to reduce file counts. Split only when
the dependency graph is already clean and a deployment or reuse boundary exists.
