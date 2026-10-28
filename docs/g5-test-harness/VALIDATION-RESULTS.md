# G5 harness - offline validation results

Generated 2026-10-27 21:46:58 by scripts/run-g5-validation.ps1. Pack zip SHA-256: `599BDFDB9D6CAF52CE497374B9EF0A11F31C04C0766BD0816A2D67F17B7C7407`

These are OFFLINE checks (mock world, synthetic fixtures, one real log excerpt). They are not gameplay evidence; every gameplay scenario stays PENDING until run in Minecraft.

| Check | Status | Seconds | Output |
|---|---|---|---|
| build (regenerate pack + zip) | **PASS** | 1 |  |
| static validator (1.20.1 grammar subset, NPCortex command shapes, safety, evidence-first invariants) | **PASS** | 1 | "checks":  8509, "failures":  0, "status":  "PASS", |
| validator mutation test (15 broken packs must be rejected) | **PASS** | 13 | "mutations":  15, "undetected":  0, "status":  "PASS" |
| controller simulation (mock world: probe v2 variants, adaptive, owner-gated, AWAIT/gate, no timer progression, stale-run protection) | **PASS** | 5 | PASS G5 data pack controller simulation: 256 assertions (mock world; controller logic only, not NPCortex/Minecraft behaviour) |
| collector tests (synthetic fixtures + real Forge log excerpt) | **PASS** | 140 | "checks":  937, "failures":  0, "status":  "PASS", |
| owner-gate tests (g5-gate.ps1 on simulated runs + hand-built cases) | **PASS** | 19 | "checks":  136, "failures":  0, "status":  "PASS", |
| installer test (throw-away tree) | **PASS** | 0 | "checks":  7, "failures":  0, "status":  "PASS" |
| isolation/preflight/launcher tests (fail-closed prerequisites, one real isolated backend on a free port, collector refuses unverified databases) | **PASS** | 33 | "checks":  62, "failures":  0, "status":  "PASS", "scope":  "fail-closed prerequisites on temp folders + one real isolated backend process; no Minecraft, no live database" |
