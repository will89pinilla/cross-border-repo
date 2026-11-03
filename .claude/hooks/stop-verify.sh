#!/usr/bin/env bash
# Stop: Claude no termina su turno con el build en rojo (spec §12.4: terminado = ./gradlew build).
# Éxito silencioso; fallo con el resumen justo. Comando configurable con HARNESS_VERIFY_CMD.
input=$(cat)
printf '%s' "$input" | grep -q '"stop_hook_active"[[:space:]]*:[[:space:]]*true' && exit 0

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
[ -x ./gradlew ] || exit 0          # antes de T01 no hay nada que verificar

# Solo si cambió código, build o recursos (no en turnos de conversación)
if git rev-parse --git-dir >/dev/null 2>&1; then
  [ -z "$(git status --porcelain -- src build.gradle.kts settings.gradle.kts gradle docker-compose.yml 2>/dev/null)" ] && exit 0
fi

cmd=${HARNESS_VERIFY_CMD:-./gradlew build --quiet}
out=$($cmd 2>&1); rc=$?

mkdir -p docs/ai-usage
task=$(python3 -c 'import json;d=json.load(open("docs/progress.json"));print(next((t["id"] for t in d["tasks"] if t["status"]=="in_progress"),"-"))' 2>/dev/null || echo "-")
printf '| %s | %s | `%s` | %s |\n' "$(date '+%Y-%m-%d %H:%M')" "$task" "$cmd" "$([ $rc -eq 0 ] && echo PASS || echo FAIL)" >> docs/ai-usage/build-log.md

if [ $rc -ne 0 ]; then
  echo "El build falla; corrígelo antes de terminar (sin debilitar tests). Resumen:" >&2
  printf '%s\n' "$out" | grep -E 'FAILED|error:|Exception|expected|but was|Could not|Docker' | head -40 >&2
  printf '%s\n' "$out" | grep -qi 'docker' && echo "Si el error es de Docker/Testcontainers: pide al humano que inicie Docker." >&2
  exit 2
fi
exit 0
