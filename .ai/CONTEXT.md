# AI Context Route

```yaml
schema_version: 1
context_repo: https://github.com/animine-ai/master-context
project_id: animetracker
source_repo: https://github.com/animine-ai/Anihyou
context_root: projects/animetracker/
entrypoint: projects/animetracker/INDEX.md
```

## Mandatory AI behavior

This file is only a routing pointer. Detailed context, plans, prompts, handoffs, execution state, audits and evidence must not be stored in this repository.

1. Validate this exact mapping against `REGISTRY.yaml` in `animine-ai/master-context`.
2. If validation fails, stop and report `PROJECT_ID_MISMATCH`.
3. Read the declared entrypoint before additional central context.
4. Follow only task-relevant links from `projects/animetracker/`.
5. Never create project context, prompt archives, handoffs, plans or AI execution logs inside `Animetracker`.
6. At the end of every AI development run, update the matching handoff/execution state in `animine-ai/master-context/projects/animetracker/`.
7. Archive every substantial generated implementation prompt under `animine-ai/master-context/prompts/animetracker/`.
