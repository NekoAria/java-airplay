#!/usr/bin/env sh
set -eu

WORKSPACE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JAR_PATH=${JAR_PATH:-"$WORKSPACE/player/app/build/libs/java-airplay-server-1.0.7.jar"}

JAVA_VERSION=$(java -version 2>&1 | sed -n '1p')
case "$JAVA_VERSION" in
  *'version "25'*) ;;
  *) echo "Java 25 is required. Current output: $JAVA_VERSION" >&2; exit 1 ;;
esac

for plugin in appsrc h264parse avdec_h264 avdec_aac avdec_alac autovideosink autoaudiosink; do
  if ! gst-inspect-1.0 "$plugin" >/dev/null 2>&1; then
    echo "Missing GStreamer plugin: $plugin" >&2
    exit 1
  fi
done

if [ ! -f "$JAR_PATH" ]; then
  "$WORKSPACE/gradlew" :player:app:bootJar
fi

exec java --enable-native-access=ALL-UNNAMED -jar "$JAR_PATH" "$@"
