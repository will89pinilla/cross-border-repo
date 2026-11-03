#!/usr/bin/env python3
"""PreToolUse (Edit|Write|MultiEdit): protege lo que solo cambia un humano.

exit 2 + stderr  -> bloquea y le explica al agente qué hacer.
JSON "ask"       -> Claude Code le pide confirmación al humano.
"""
import json
import os
import re
import subprocess
import sys

data = json.load(sys.stdin)
tool_input = data.get("tool_input", {})
path = tool_input.get("file_path", "")
root = os.environ.get("CLAUDE_PROJECT_DIR") or data.get("cwd") or os.getcwd()
rel = os.path.relpath(path, root) if os.path.isabs(path) else path


def block(msg):
    print(f"BLOQUEADO ({rel}): {msg}", file=sys.stderr)
    sys.exit(2)


def ask(reason):
    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "ask",
        "permissionDecisionReason": reason}}))
    sys.exit(0)


def tracked_in_git(p):
    try:
        return subprocess.run(["git", "ls-files", "--error-unmatch", p], cwd=root,
                              capture_output=True).returncode == 0
    except OSError:
        return False


# 1. Documentos de decisión: solo el humano
if rel.startswith(("docs/spec/", "docs/design/", "docs/challenge/")):
    block("es un documento de decisión del humano. Si algo falta o se contradice, detente y "
          "propón el cambio en el chat; el humano actualiza la spec.")

# 2. El propio harness y los secretos
if rel.startswith(".claude/hooks/") or rel in (".claude/settings.json", "CLAUDE.md"):
    block("es parte del harness; solo lo cambia el humano.")
if re.search(r"(^|/)\.env(\.|$)", rel):
    block("archivo de secretos.")

# 3. Migraciones ya versionadas: no se editan, se agrega una nueva
if re.search(r"src/main/resources/db/migration/V\d+__.*\.sql$", rel) and tracked_in_git(rel):
    block("esta migración ya está en git. Crea una nueva V<n+1>__descripcion.sql.")

# 4. Tests de arquitectura versionados: protegen D12/§11
if rel.endswith("ArchitectureTest.java") and tracked_in_git(rel):
    block("las reglas ArchUnit protegen la arquitectura (§11). Corrige el código, no la regla.")

# 5. Oráculos de los fixtures: cambiarlos requiere al humano
if re.search(r"src/test/resources/fixtures/.+/expected\.json$", rel) and tracked_in_git(rel):
    ask("Cambia un expected.json versionado (el oráculo de un fixture). "
        "Confirma solo si la spec lo justifica, no para que un test pase.")

# 6. Dependencias y versiones: la spec §11 manda
if rel in ("gradle/libs.versions.toml", "build.gradle.kts", "settings.gradle.kts") and os.path.exists(path):
    ask("Cambio en dependencias o build. Regla §12.3: ninguna dependencia nueva sin actualizar antes la spec §11.")

# 7. Tests: no debilitar
if rel.startswith("src/test/") and rel.endswith(".java"):
    edits = tool_input.get("edits") or [{"old_string": tool_input.get("old_string", ""),
                                         "new_string": tool_input.get("new_string", "")}]
    if "content" in tool_input and os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            edits = [{"old_string": f.read(), "new_string": tool_input["content"]}]
    asserts = re.compile(r"\bassert\w*\s*\(|\bverify\s*\(|\bassertThat\w*\s*\(")
    before = sum(len(asserts.findall(e.get("old_string") or "")) for e in edits)
    after = sum(len(asserts.findall(e.get("new_string") or "")) for e in edits)
    disabled_added = sum((e.get("new_string") or "").count("@Disabled") - (e.get("old_string") or "").count("@Disabled")
                         for e in edits)
    if before > after:
        block(f"la edición elimina {before - after} assert(s). Regla §12.3: nunca debilitar un test. "
              "Corrige el código de producción; si el criterio cambió, detente y pregúntale al humano.")
    if disabled_added > 0:
        block("agregar @Disabled está prohibido (§12.3).")

sys.exit(0)
