# Uso de IA en el Task 2

El caso pide documentar en detalle el uso de IA (prompts, capturas y casos de uso). Este registro se arma así:

| Archivo | Quién lo escribe | Contenido |
|---|---|---|
| `prompts.md` | Hook `UserPromptSubmit` (automático) | Cada prompt, con fecha y tarea en curso |
| `build-log.md` | Hook `Stop` (automático) | Cada verificación del build: fecha, tarea, PASS/FAIL |
| `T01.md` … `T10.md` | Skill `/close-task` + el humano | Resumen por tarea con la plantilla de abajo |
| `screenshots/` | El humano | Capturas de tests en verde, revisiones y demo |

## Plantilla por tarea (`TXX.md`)

```markdown
# TXX · <título>

## Prompts usados
(lista tomada de prompts.md)

## Qué produjo el agente
(clases, tests y fixtures; resumen de 3–6 líneas)

## Hallazgos de la revisión
(spec-reviewer / ledger-reviewer: qué encontraron y cómo se resolvió)

## Corregido a mano
(lo completa el humano: qué cambiaste tú y por qué)

## Evidencia
(salida final de ./gradlew build) · Captura: screenshots/TXX.png
```

## Cómo se configuró el agente

El repositorio incluye el harness de Claude Code con el que se construyó: `CLAUDE.md`, `.claude/rules/`,
`.claude/hooks/`, `.claude/agents/` y `.claude/skills/`. `docs/harness-log.md` registra cómo evolucionó.
