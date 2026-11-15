#!/usr/bin/env bash
# يجمّع ويشغّل اختبارات المنطق النقي (حزمة core) بدون Android SDK، باستخدام Kotlin المدمج في توزيعة Gradle.
# الاستخدام: GRADLE_HOME=/opt/gradle-8.14.3 ./scripts/run_core_tests.sh
set -euo pipefail
GRADLE_HOME=${GRADLE_HOME:-/opt/gradle-8.14.3}
L="$GRADLE_HOME/lib"
ROOT="$(cd "$(dirname "$0")/.." && pwd)/app/src"
OUT="$(mktemp -d)"
KCP=$(ls "$L"/kotlin-compiler-embeddable-*.jar "$L"/kotlin-stdlib-2*.jar "$L"/kotlin-reflect-*.jar \
  "$L"/kotlin-script-runtime-*.jar "$L"/kotlin-daemon-embeddable-*.jar "$L"/trove4j*.jar \
  "$L"/annotations-2*.jar "$L"/kotlinx-coroutines-core-jvm-*.jar | tr '\n' ':')
STD=$(ls "$L"/kotlin-stdlib-2*.jar | head -1)
TC="$STD:$(ls "$L"/junit-4*.jar | head -1):$(ls "$L"/hamcrest-core-*.jar | head -1)"
java -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -Werror \
  -cp "$TC" -d "$OUT" "$ROOT/main/java/com/example/arabicvideotranslator/core" "$ROOT/test/java"
cd "$OUT"
CLASSES=$(find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | tr '\n' ' ')
java -cp ".:$TC" org.junit.runner.JUnitCore $CLASSES
