#!/usr/bin/env python3
"""UserPromptSubmit: guarda cada prompt en docs/ai-usage/prompts.md (registro de uso de IA, §12.4).

No imprime nada en stdout (lo que imprima se agregaría al contexto de Claude).
Se desactiva con HARNESS_LOG_PROMPTS=0.
"""
import datetime
import json
import os
import sys

if os.environ.get("HARNESS_LOG_PROMPTS", "1") == "0":
    sys.exit(0)
data = json.load(sys.stdin)
prompt = (data.get("prompt") or "").strip()
if not prompt:
    sys.exit(0)

root = os.environ.get("CLAUDE_PROJECT_DIR") or data.get("cwd") or os.getcwd()
task = "-"
try:
    with open(os.path.join(root, "docs/progress.json"), encoding="utf-8") as f:
        task = next((t["id"] for t in json.load(f)["tasks"] if t["status"] == "in_progress"), "-")
except Exception:
    pass

os.makedirs(os.path.join(root, "docs/ai-usage"), exist_ok=True)
stamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
with open(os.path.join(root, "docs/ai-usage/prompts.md"), "a", encoding="utf-8") as f:
    f.write(f"\n### {stamp} · {task}\n\n```text\n{prompt}\n```\n")
sys.exit(0)
