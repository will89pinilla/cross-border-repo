#!/usr/bin/env python3
"""PreToolUse (Bash): evita editar archivos del proyecto por Bash.

Si el agente escribe archivos con redirecciones, heredocs, sed -i, cp o mv, los hooks de
Edit/Write (protect.py y check-java.py) no corren. Este hook cierra ese atajo para el código,
la spec y el harness. Bash queda para compilar, testear, git y consultas.
"""
import json
import re
import sys

cmd = json.load(sys.stdin).get("tool_input", {}).get("command", "")

TARGETS = re.compile(r"(^|[\s'\"=/.])(src/|docs/(spec|design|challenge)/|\.claude/|CLAUDE\.md|"
                     r"build\.gradle\.kts|settings\.gradle\.kts|libs\.versions\.toml|docker-compose\.yml|docs/progress\.json)")
REDIRECT = re.compile(r"(?<![0-9&])>>?\s*([^\s;&|]+)")
TEE = re.compile(r"\btee\s+(?:-a\s+)?([^\s;&|]+)")
INPLACE = re.compile(r"\b(sed|perl)\s+-[a-zA-Z]*i|\b(cp|mv|rm|truncate|install|ln)\s")
# Solo scripts con intención de escritura; leer (open(...) en modo lectura, json.load) está permitido.
SCRIPTED = re.compile(r"\b(python3?|node|ruby)\b.*(open\([^)]*['\"][wax]\+?b?['\"]|\.write\(|write_text|"
                      r"write_bytes|json\.dump\(|shutil\.|os\.(remove|rename|replace|unlink)|\.unlink\(|writeFile)",
                      re.DOTALL)

hits = [t for t in REDIRECT.findall(cmd) + TEE.findall(cmd) if TARGETS.search(" " + t)]
if not hits and TARGETS.search(" " + cmd) and (INPLACE.search(cmd) or SCRIPTED.search(cmd)):
    hits = ["(edición en sitio, copia, movimiento o script)"]

if hits:
    print("BLOQUEADO: no edites ni crees archivos del proyecto por Bash "
          f"({', '.join(hits[:3])}). Usa las herramientas Edit/Write para que corran los hooks "
          "de protección y de chequeo. Si de verdad necesitas Bash (por ejemplo, mover un archivo), "
          "pídele al humano que lo haga.", file=sys.stderr)
    sys.exit(2)
sys.exit(0)
