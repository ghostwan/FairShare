#!/usr/bin/env bash
set -euo pipefail

APP_ID="com.fairshare.app"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_VERSION="8.11.1"
GRADLE_DIR="$ROOT_DIR/.gradle/local/gradle-$GRADLE_VERSION"
MODE="${2:-debug}"
ACTION="${1:-help}"

usage() {
  printf '%s\n' "Usage: scripts/android.sh <compile|install|deploy|clean> [debug|release]"
  printf '%s\n' ""
  printf '%s\n' "Exemples:"
  printf '%s\n' "  scripts/android.sh compile debug"
  printf '%s\n' "  scripts/android.sh install debug"
  printf '%s\n' "  scripts/android.sh deploy debug"
  printf '%s\n' "  FAIRSHARE_KEYSTORE=/path/release.jks FAIRSHARE_KEYSTORE_PASSWORD=... FAIRSHARE_KEY_ALIAS=... FAIRSHARE_KEY_PASSWORD=... scripts/android.sh deploy release"
}

gradle_cmd() {
  if [ -x "$ROOT_DIR/gradlew" ]; then
    "$ROOT_DIR/gradlew" "$@"
  elif command -v gradle >/dev/null 2>&1; then
    gradle -p "$ROOT_DIR" "$@"
  else
    ensure_local_gradle
    "$GRADLE_DIR/bin/gradle" -p "$ROOT_DIR" "$@"
  fi
}

ensure_local_gradle() {
  if [ -x "$GRADLE_DIR/bin/gradle" ]; then
    return
  fi

  if ! command -v curl >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1; then
    printf '%s\n' "curl et unzip sont requis pour telecharger Gradle automatiquement." >&2
    exit 1
  fi

  mkdir -p "$ROOT_DIR/.gradle/local"
  local archive="$ROOT_DIR/.gradle/local/gradle-$GRADLE_VERSION-bin.zip"
  printf '%s\n' "Gradle introuvable, telechargement de Gradle $GRADLE_VERSION..."
  curl -L "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$archive"
  unzip -q "$archive" -d "$ROOT_DIR/.gradle/local"
}

require_adb() {
  if ! command -v adb >/dev/null 2>&1; then
    printf '%s\n' "adb introuvable. Installe Android Platform Tools ou lance depuis Android Studio." >&2
    exit 1
  fi
}

require_release_signing() {
  if [ "$MODE" != "release" ]; then
    return
  fi

  : "${FAIRSHARE_KEYSTORE:?Variable FAIRSHARE_KEYSTORE requise pour installer/deployer une release}"
  : "${FAIRSHARE_KEYSTORE_PASSWORD:?Variable FAIRSHARE_KEYSTORE_PASSWORD requise pour installer/deployer une release}"
  : "${FAIRSHARE_KEY_ALIAS:?Variable FAIRSHARE_KEY_ALIAS requise pour installer/deployer une release}"
  : "${FAIRSHARE_KEY_PASSWORD:?Variable FAIRSHARE_KEY_PASSWORD requise pour installer/deployer une release}"
}

variant_task() {
  case "$MODE" in
    debug) printf '%s' "Debug" ;;
    release) printf '%s' "Release" ;;
    *) printf '%s\n' "Mode invalide: $MODE. Utilise debug ou release." >&2; exit 1 ;;
  esac
}

gradle_props() {
  if [ "$MODE" = "release" ] && [ -n "${FAIRSHARE_KEYSTORE:-}" ]; then
    printf '%s\n' \
      "-PFAIRSHARE_KEYSTORE=$FAIRSHARE_KEYSTORE" \
      "-PFAIRSHARE_KEYSTORE_PASSWORD=$FAIRSHARE_KEYSTORE_PASSWORD" \
      "-PFAIRSHARE_KEY_ALIAS=$FAIRSHARE_KEY_ALIAS" \
      "-PFAIRSHARE_KEY_PASSWORD=$FAIRSHARE_KEY_PASSWORD"
  fi
}

run_gradle() {
  local task="$1"
  if [ "$MODE" = "release" ] && [ -n "${FAIRSHARE_KEYSTORE:-}" ]; then
    gradle_cmd "$task" \
      "-PFAIRSHARE_KEYSTORE=$FAIRSHARE_KEYSTORE" \
      "-PFAIRSHARE_KEYSTORE_PASSWORD=$FAIRSHARE_KEYSTORE_PASSWORD" \
      "-PFAIRSHARE_KEY_ALIAS=$FAIRSHARE_KEY_ALIAS" \
      "-PFAIRSHARE_KEY_PASSWORD=$FAIRSHARE_KEY_PASSWORD"
  else
    gradle_cmd "$task"
  fi
}

compile_app() {
  local variant
  variant="$(variant_task)"
  run_gradle ":app:assemble$variant"
}

install_app() {
  local variant
  require_adb
  require_release_signing
  variant="$(variant_task)"
  run_gradle ":app:install$variant"
}

launch_app() {
  require_adb
  adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null
}

case "$ACTION" in
  compile)
    compile_app
    ;;
  install)
    install_app
    ;;
  deploy)
    install_app
    launch_app
    ;;
  clean)
    gradle_cmd clean
    ;;
  help|-h|--help)
    usage
    ;;
  *)
    usage >&2
    exit 1
    ;;
esac
