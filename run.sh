#!/usr/bin/env bash
# Launch the shaded jar with realtime-audio GC settings so a GC pause cannot
# exceed the ~300 ms audio buffer and underrun playback. Build first with:
#   mvn -q clean package
#
# Generational ZGC needs JDK 21+. If your JVM rejects -XX:+ZGenerational, use the
# G1 fallback line below instead.
set -euo pipefail

GC_OPTS=(-XX:+UseZGC -XX:+ZGenerational -Xms512m -Xmx512m -XX:+AlwaysPreTouch)
# GC_OPTS=(-XX:+UseG1GC -XX:MaxGCPauseMillis=50 -Xms512m -Xmx512m -XX:+AlwaysPreTouch)

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec java "${GC_OPTS[@]}" -jar "$DIR/target/java-allin1-editor.jar" "$@"
