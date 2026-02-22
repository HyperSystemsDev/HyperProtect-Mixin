# Changelog

All notable changes to HyperProtect-Mixin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2025-02-17

### Added

- **AtomicReferenceArray bridge** — 24-element lock-free bridge stored in `System.getProperties()` for zero-contention cross-classloader hook communication
- **20 protection hooks** across 7 categories:
  - **Building:** block_break (0), explosion (1), fire_spread (2), builder_tools (3), block_place (18), hammer (19), use (20)
  - **Items:** item_pickup (4), death_drop (5), durability (6)
  - **Containers:** container_access (7), container_open (17)
  - **Combat:** entity_damage (16)
  - **Entities:** mob_spawn (8), respawn (22)
  - **Transport:** teleporter (9), portal (10), seat (21)
  - **Commands:** command (11)
  - **Logging:** interaction_log (12)
- **31 mixin interceptors** covering all hooks with multiple injection points for comprehensive coverage
- **Verdict protocol** — standardized ALLOW (0) / DENY_WITH_MESSAGE (1) / DENY_SILENT (2) / DENY_MOD_HANDLES (3)
- **Respawn value hook** — returns `double[3]` override coordinates instead of verdict int
- **Fail-open safety** — all hooks allow actions on error, missing hooks, or negative verdicts
- **System property detection** — `hyperprotect.bridge.active`, `hyperprotect.bridge.version`, and per-interceptor `hyperprotect.intercept.*` properties
- **Spawn startup protection** — configurable spawn blocking during server initialization via `spawn_ready` and `spawn_allow_startup` flag slots
- **ChatFormatter** — `&`-code message formatter with hex colors (`&#RRGGBB`), named colors, bold, italic, monospace, and reset support
- **FaultReporter** — sampled error logging (first + every 100th) to prevent log flooding
- **HookSlot caching** — eagerly-resolved MethodHandles with volatile identity-checked caching for minimal overhead
- **Complete documentation** — getting-started guide, hook reference, integration patterns, code examples, feature detection guide, and OrbisGuard migration guide
