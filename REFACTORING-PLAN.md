# Refactoring Plan: Add Bar Abstraction

## New Model Hierarchy

```
ProjectModel
├── List<Segment> segments
│   └── Segment
│       └── List<Bar> bars
│           └── Bar
│               └── List<Beat> beats
│                   └── Beat
│                       ├── isDownbeat: boolean
│                       ├── start: double (seconds)
│                       └── end: double (seconds)
└── (downbeatTimeList REMOVED)
└── (flat beatList REMOVED)
```

## New JSON Format (already matches the model hierarchy)

```json
{
  "path": "...",
  "bpm": 125,
  "segments": [
    {
      "label": "intro",
      "bars": [
        {
          "beats": [
            {"isDownbeat": true,  "start": 0.0,  "end": 0.47},
            {"isDownbeat": false, "start": 0.47, "end": 0.94},
            {"isDownbeat": false, "start": 0.94, "end": 1.42},
            {"isDownbeat": false, "start": 1.42, "end": 1.9}
          ]
        }
      ]
    }
  ]
}
```

## Phase 1: Create New Model Classes ✓ DONE

| Class | Status |
|-------|--------|
| `Beat.java` | Done — `downbeat`, `startTime`, `endTime` fields |
| `Bar.java` | Done — exists, wraps `List<Beat>` |
| `Segment.java` | Done — `label`, `List<Bar> bars`, computed start/end |

## Phase 2: Modify `ProjectModel.java` ✓ DONE

Old flat-list API removed, new hierarchy API in place:
- `getAllBeatsFlat()`, `getAllDownbeatBeatsFlat()`, `findSegmentIndexForBeat()`
- `addSegment()`, `removeSegment()`, `moveSegment()`, `sortSegments()`
- `getMaxTime()` iterates segments → bars → beats
- `copyFrom()` deep-copies segment hierarchy

## Phase 3: Rewrite `AllIn1JsonFileRepository.java`

The old flat-array JSON format (`beats[]`, `beat_positions[]`, `downbeats[]`) is **removed**. Only the new nested format is supported.

### Loading (new format only)
```java
for (JsonNode segNode : root.get("segments")) {
    Segment seg = new Segment(segNode.get("label").asText());
    for (JsonNode barNode : segNode.get("bars")) {
        Bar bar = new Bar();
        for (JsonNode beatNode : barNode.get("beats")) {
            bar.addBeat(new Beat(
                beatNode.get("isDownbeat").asBoolean(),
                beatNode.get("start").asDouble(),
                beatNode.get("end").asDouble()));
        }
        seg.addBar(bar);
    }
    model.addSegment(seg);
}
```

### Saving (new format only)
```java
for (Segment s : segments) {
    ObjectNode segNode = segArr.addObject();
    segNode.put("label", s.getLabel());
    ArrayNode barsArr = segNode.putArray("bars");
    for (Bar bar : s.getBars()) {
        ObjectNode barNode = barsArr.addObject();
        ArrayNode beatsArr = barNode.putArray("beats");
        for (Beat b : bar.getBeats()) {
            ObjectNode beatNode = beatsArr.addObject();
            beatNode.put("isDownbeat", b.isDownbeat());
            beatNode.put("start", roundToMs(b.getStart()));
            beatNode.put("end", roundToMs(b.getEnd()));
        }
    }
}
```

**Update `EXPLICITLY_MODELLED_JSON_KEYS`:** `{"path", "bpm", "segments"}` (remove old flat keys).

## Phase 4: Update `TimelinePanel.java` (1115 lines — largest change)

### 4a. Segment start/end → computed

All 28 reads of `s.getStart()` / `s.getEnd()` already use the computed versions from Segment (which delegate to first/last bar/beat). No changes needed because model already uses computed start/end.

### 4b. Beat/Downbeat drawing

**Current:** `drawBeatsInSegment()` iterates global flat list, filters by `t >= s.getStart() && t <= s.getEnd()`

**New:** Iterate `segment.getBars()` → `bar.getBeats()` — no filtering needed, beats are already nested.

```java
// OLD:
List<Beat> beats = model.getBeats();
for (int i = 0; i < beats.size(); i++) {
    Beat b = beats.get(i);
    if (b.getTime() < s.getStart() || b.getTime() > s.getEnd()) continue;
    // draw
}

// NEW:
for (Bar bar : s.getBars()) {
    for (Beat b : bar.getBeats()) {
        // draw — guaranteed to be in this segment
    }
}
```

### 4c. Downbeat drawing

**Current:** `drawDownbeatsInSegment()` iterates global `model.getDownbeats()`, filters by time range

**New:** `segment.getBars()` → each bar's first beat with `isDownbeat == true` → draw its start time

```java
for (Bar bar : s.getBars()) {
    if (!bar.getBeats().isEmpty() && bar.getBeats().get(0).isDownbeat()) {
        double t = bar.getBeats().get(0).getStart();
        // draw downbeat marker
    }
}
```

### 4d. `buildSegmentCounter()`

**Current:** Counts downbeats and beats in segment range by linear scan

**New:** Direct counts:
```java
int totalBars = segment.getBars().size();
int totalBeats = segment.getBars().stream().mapToInt(b -> b.getBeats().size()).sum();
```

### 4e. `currentBeatInterval()` / `currentDownbeatInterval()`

**Current:** Linear scan of global list with time-range filtering

**New:** Navigate the bar/beat hierarchy within the current segment:
```java
// Find which beat contains playhead time
for (Bar bar : currentSegment.getBars()) {
    for (Beat b : bar.getBeats()) {
        if (playheadTime >= b.getStart() && playheadTime < b.getEnd()) {
            // current beat found
        }
    }
}
```

### 4f. `findSegmentContainingTime()`

**Current:** Linear scan testing `time >= s.getStart() && time <= s.getEnd()`

**New:** Same logic but using computed start/end from segment (no change in algorithm, just the source of start/end)

### 4g. Hit-testing (`hitBeatInSegment`, `hitDownbeatInSegment`)

**Current:** Linear scan of global list, filtered by segment time range

**New:** Iterate `segment.getBars()` → `bar.getBeats()` — no filtering needed

### 4h. Mouse drag handlers

**Current (line 898):** `model.getBeats().get(draggedItemIndex).setTime(t)`

**New:** Need to update `beat.setStart()` and `beat.setEnd()` (maintaining duration). When a beat is dragged across a bar boundary, move it between bars.

**Critical:** Dragging a beat may require moving it from one bar to another (or from one segment to another). The drag release handler must reassign bar membership.

### 4i. Context menu "Add beat here"

**Current:** `model.addBeat(new Beat(t, 1))`

**New:** Determine which bar the click falls in, create `new Beat(false, t, t + avgBeatDuration)`, add to that bar. If no bar exists, create one.

### 4j. Context menu "Add downbeat here"

**Current:** `model.addDownbeat(t)`

**New:** Create a new Bar with a single Beat(isDownbeat=true, start=t, end=t+duration), add to segment.

## Phase 5: Update Table Panels

### 5a. `BeatsTablePanel.java`

**Columns change from:** `#`, `Time (s)`, `Position`
**To:** `#`, `Bar #`, `Start (s)`, `End (s)`, `Downbeat`

Need to navigate Segment → Bar → Beat for display. Editing a beat's start/end must update the model and potentially adjacent beats' end times.

### 5b. `DownbeatsTablePanel.java`

**Rename to `BarsTablePanel.java`**

**Columns:** `#`, `Downbeat (s)`, `# Beats`, `Duration`

Each row represents a Bar. The downbeat time is `bar.getBeats().get(0).getStart()`.

### 5c. `SegmentsTablePanel.java`

**Columns change from:** `Start (s)`, `End (s)`, `Label`
**To:** `#`, `Label`, `# Bars`, `# Beats`, `Start (s)`, `End (s)` (last 3 computed, read-only)

Start/End are now computed fields (read-only in table). Remove calls to `s.setStart()` / `s.setEnd()`.

## Phase 6: Update Selection Model

**`SelectionModel.java`:**
- `SelectableItemType` enum: add `BAR`, keep `BEAT`, `SEGMENT`
- `DOWNBEAT` type can be removed (replaced by checking `beat.isDownbeat()`)
- Selection now needs to track: segment index + bar index + beat index (or use global indices)

## Phase 7: Update Audio Engine

**`MainFrame.pushMetronomeBeatsToEngine()`** (line 274-282):

**Current:** `times[i] = beats.get(i).getTime()`

**New:** Build flat array from hierarchy:
```java
List<Double> times = new ArrayList<>();
for (Segment s : model.getSegments()) {
    for (Bar bar : s.getBars()) {
        for (Beat b : bar.getBeats()) {
            times.add(b.getStart());
        }
    }
}
```

No changes needed to `PcmWavPlaybackEngine` or `MetronomeClickSynthesizer`.

## Phase 8: Update `JsonIORoundTripTest.java`

1. Update `loadSaveLoadPreservesData()` to use new nested model construction and verify the segment → bars → beats structure
2. Update `editsPersist()` to construct model with segments → bars → beats
3. Remove backward-compat loading test (old format no longer supported)

## Implementation Order

| Step | Phase | Files Changed | Risk |
|------|-------|---------------|------|
| 1 | 3 | `AllIn1JsonFileRepository.java` | Medium — rewrite I/O only |
| 2 | 8 | `JsonIORoundTripTest.java` | Low — verify persistence |
| 3 | 6 | `SelectionModel.java` | Low — add BAR type |
| 4 | 5b | `DownbeatsTablePanel.java` → `BarsTablePanel.java` | Medium |
| 5 | 5c | `SegmentsTablePanel.java` | Medium |
| 6 | 5a | `BeatsTablePanel.java` | Medium |
| 7 | 4 | `TimelinePanel.java` | **High** — 1115 lines, 75% of call sites |
| 8 | 7 | `MainFrame.java` | Low — simple extraction |

## Key Risks

| Risk | Mitigation |
|------|------------|
| Drag beat across bar/segment boundary | On mouse release, recalculate membership from beat time |
| Segment resize (edge drag) now means adding/removing bars/beats | Edge drag becomes "add/remove bar at edge" |
| Beat end times must be contiguous (beat N end = beat N+1 start) | Enforce invariant on save; normalize on load |
| TimelinePanel is 1115 lines with ~40 call sites | Migrate incrementally: computed start/end first, then restructure iteration |
