#!/usr/bin/env python3
"""PostToolUse (Edit|Write|MultiEdit) sobre docs/progress.json.

progress.json se importa en CLAUDE.md, así que se carga en TODAS las sesiones: debe ser válido y corto.
- JSON inválido -> exit 2.
- Una nota de tarea de más de MAX_NOTE caracteres -> exit 2: el detalle va en docs/notes/<TAREA>.md
  y en progress.json queda un resumen corto con el enlace.
"""
import json
import os
import sys

MAX_NOTE = int(os.environ.get("HARNESS_MAX_NOTE_CHARS", "300"))

path = json.load(sys.stdin).get("tool_input", {}).get("file_path", "")
if not path.endswith("docs/progress.json") or not os.path.exists(path):
    sys.exit(0)

try:
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
except json.JSONDecodeError as e:
    print(f"docs/progress.json quedó con JSON inválido ({e}). Corrígelo: los hooks y CLAUDE.md lo leen.", file=sys.stderr)
    sys.exit(2)

long_notes = [(t["id"], len(t.get("notes", ""))) for t in data.get("tasks", []) if len(t.get("notes", "")) > MAX_NOTE]
if long_notes:
    detail = ", ".join(f"{i} ({n} caracteres)" for i, n in long_notes)
    print(f"Notas demasiado largas en docs/progress.json: {detail}. Máximo {MAX_NOTE} caracteres, porque el archivo "
          "se carga en cada sesión. Mueve el detalle a docs/notes/<TAREA>.md (agrega al final si ya existe) y deja "
          "en notes un resumen de una línea que termine en 'Detalle: docs/notes/<TAREA>.md'.", file=sys.stderr)
    sys.exit(2)
sys.exit(0)
