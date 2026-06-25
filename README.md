# Audio Analysis JSON Editor

A Java/Swing desktop editor for `allin1`-style music-structure analysis JSON
files (BPM, beats + bar positions, downbeats, segments). **It is a full editor**
— every object is created, edited, dragged, reordered and deleted, then saved
back to JSON. Realtime audio playback is included only as an *editing aid* to
hear whether your edits line up with the music.

## Requirements
- JDK 21
- Maven 3.5.4+

## Build
```
mvn clean package
```
Produces a runnable fat-jar at `target/audio-json-editor.jar` and runs the
JSON round-trip tests.

## Run
For smooth, glitch-free playback use the launcher scripts — they start the JVM
with realtime-audio GC settings (see *Performance* below):
```
./run.sh             [optional-path-to.json]   # macOS / Linux
.\run.ps1            [optional-path-to.json]   # Windows (PowerShell)
# or, via Maven (forks a JVM with the same GC flags):
mvn exec:exec
```
Plain `java -jar target/java-allin1-editor.jar` and `mvn exec:java` also work but
run with the default collector, which can cause periodic audio dropouts.

If a JSON path is passed, it is opened on startup and the WAV referenced by its
`path` field is auto-loaded. If that WAV is missing you are prompted to Browse.

## Performance (realtime audio)
Audio is pumped on a dedicated high-priority thread with a ~300 ms line buffer.
Two things keep playback from stuttering:
- **The metronome is mixed into the primary output stream** sample-accurately by
  the pump thread — there is no second `Clip`/line to glitch the main one, and
  clicks land exactly on the beat regardless of UI load.
- **A low-pause collector with a fixed, pre-touched heap** (Generational ZGC by
  default; `-Xms=-Xmx`, `AlwaysPreTouch`) so no GC pause exceeds the audio buffer.
  Generational ZGC needs JDK 21+; the scripts contain a commented G1 fallback
  (`-XX:+UseG1GC -XX:MaxGCPauseMillis=50`) for other JVMs.

## Editing
**Timeline** (top): click to seek, drag the playhead to scrub.
- **Segments** (colored blocks): drag the body to move, drag an edge to resize.
- **Downbeats** (red band) and **beats** (lower band): drag to retime; the beat's
  bar position (1..4) is drawn on each tick.
- **Double-click** empty space in a band to add an object there.
- **Right-click** an object to delete it (or add one).
- Clicking any marker jumps playback to its time.

**Tables** (bottom tabs — Segments / Beats / Downbeats): full CRUD with
Add / Duplicate / Delete / ↑ / ↓ (reorder) / Sort. Cells are directly editable
(times, beat position, segment label via an editable dropdown). Selection is
synchronized both ways with the timeline.

**Toolbar**: Play/Pause (also Spacebar), Stop, zoom, **Metronome** (clicks on
each beat during playback), editable **BPM**, editable audio **path** + Browse.

All edits update the timeline, tables and playhead live — even during playback.

## Save
`File ▸ Save` / `Save As…` writes valid `allin1`-shaped JSON: the editable beat
list is split back into the parallel `beats` / `beat_positions` arrays, and any
unrecognized top-level keys from the original file are preserved.

## Layout
```
com.audioeditor
├─ App                 entry point
├─ model/              Beat, Segment, ProjectModel (change events + dirty flag)
├─ io/JsonIO           load/save with unknown-key preservation
├─ audio/AudioEngine   in-memory PCM playback, seek, metronome click
└─ ui/                 TimelinePanel, *TablePanel, SelectionModel, MainFrame
```
