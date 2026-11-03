#!/usr/bin/env python3
"""PostToolUse (Edit|Write|MultiEdit): chequeos rápidos de las prohibiciones de la spec §12.3.

No reemplaza a ArchUnit ni a los tests: da feedback en segundos, sin esperar al build.
Éxito silencioso; si encuentra algo, exit 2 y el detalle llega al agente.
"""
import json
import os
import re
import sys

data = json.load(sys.stdin)
path = data.get("tool_input", {}).get("file_path", "")
if not (path.endswith(".java") or path.endswith(".sql")) or not os.path.exists(path):
    sys.exit(0)

with open(path, encoding="utf-8") as f:
    lines = f.read().splitlines()

problems = []


def scan(pattern, message, flags=0):
    rx = re.compile(pattern, flags)
    for n, line in enumerate(lines, 1):
        code = line.split("//", 1)[0]
        if rx.search(code):
            problems.append(f"  línea {n}: {message}\n    {line.strip()}")


main_java = "/src/main/java/" in path and path.endswith(".java")
pkg = path.replace("\\", "/")

if main_java:
    scan(r"\b(double|float|Double|Float)\b", "double/float prohibido para dinero (D11, §12.3). Usa Money/BigDecimal.")
    scan(r"RoundingMode\.(?!UNNECESSARY)", "nunca se redondea un monto (D11). Si es solo formato, usa setScale(2) sin redondeo.")
if "/domain/" in pkg and main_java:
    scan(r"^\s*import\s+(org\.springframework|java\.sql|javax\.sql|com\.cobre\.accounts\.(adapters|application))",
         "domain no depende de Spring, JDBC, application ni adapters (§3.3, §11).")
if "/application/" in pkg and main_java:
    scan(r"^\s*import\s+(org\.springframework|com\.cobre\.accounts\.adapters)",
         "application no depende de Spring ni de adapters (§11). Usa los puertos de application/port.")
    scan(r"@Transactional", "la transacción se abre con el puerto UnitOfWork, no con @Transactional.")
if "/src/test/" in pkg and path.endswith(".java"):
    scan(r"@Disabled", "no se desactivan tests (§12.3).")
scan(r"\b(UPDATE\s+(journal_entry|entry_line|settlement_decision)|DELETE\s+FROM\s+(journal_entry|entry_line|settlement_decision))\b",
     "el journal y las decisiones son inmutables: solo INSERT (D13).", re.IGNORECASE)

if problems:
    print(f"Revisa {os.path.basename(path)} (reglas de la spec):\n" + "\n".join(problems[:15]), file=sys.stderr)
    sys.exit(2)
sys.exit(0)
