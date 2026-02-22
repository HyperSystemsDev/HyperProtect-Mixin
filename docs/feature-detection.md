# Feature Detection

## Interceptor Loaded Properties

Each interceptor class sets a system property on load. Check these to determine which protections are active:

| Property | Interceptor Class | Hook Slot |
|----------|-------------------|-----------|
| `hyperprotect.bridge.active` | Plugin entry point | — |
| `hyperprotect.intercept.block_break` | HarvestInterceptor | `block_break` (0) |
| `hyperprotect.intercept.explosion` | BlastDamageInterceptor | `explosion` (1) |
| `hyperprotect.intercept.fire_spread` | FlameTickInterceptor | `fire_spread` (2) |
| `hyperprotect.intercept.builder_tools` | PasteInterceptor | `builder_tools` (3) |
| `hyperprotect.intercept.item_pickup` | ProximityLootInterceptor | `item_pickup` (4) |
| `hyperprotect.intercept.death_drop` | DeathLootInterceptor | `death_drop` (5) |
| `hyperprotect.intercept.durability` | WearInterceptor | `durability` (6) |
| `hyperprotect.intercept.workbench_context` | BenchPositionCapture | `container_access` (7) |
| `hyperprotect.intercept.container_access` | CraftingGateInterceptor | `container_access` (7) |
| `hyperprotect.intercept.spawn_marker` | MarkerSpawnGate | `mob_spawn` (8) |
| `hyperprotect.intercept.world_spawn` | ChunkSpawnGate | `mob_spawn` (8) |
| `hyperprotect.intercept.npc_spawn` | NpcAdditionGate | `mob_spawn` (8) |
| `hyperprotect.intercept.entity_load` | EntityLoadGate | `mob_spawn` (8) |
| `hyperprotect.intercept.teleporter` | TelepadInterceptor | `teleporter` (9) |
| `hyperprotect.intercept.portal_entry` | PortalGateEntry | `portal` (10) |
| `hyperprotect.intercept.portal_return` | PortalGateReturn | `portal` (10) |
| `hyperprotect.intercept.instance_config` | InstanceConfigGate | `portal` (10) |
| `hyperprotect.intercept.instance_teleport` | InstanceJumpGate | `portal` (10) |
| `hyperprotect.intercept.instance_exit` | InstanceLeaveGate | `portal` (10) |
| `hyperprotect.intercept.hub_portal` | HubGate | `portal` (10) |
| `hyperprotect.intercept.command` | CommandGateInterceptor | `command` (11) |
| `hyperprotect.intercept.interaction_log` | SpawnLogFilter | `interaction_log` (12) |
| `hyperprotect.intercept.interaction_entry_desync` | EntryDesyncFilter | `interaction_log` (12) |
| `hyperprotect.intercept.interaction_chain_desync` | ChainDesyncFilter | `interaction_log` (12) |
| `hyperprotect.intercept.entity_damage` | EntityDamageInterceptor | `entity_damage` (16) |
| `hyperprotect.intercept.container_open` | ContainerOpenInterceptor | `container_open` (17) |
| `hyperprotect.intercept.block_place` | BlockPlaceInterceptor | `block_place` (18) |
| `hyperprotect.intercept.hammer` | HammerInterceptor | `hammer` (19) |
| `hyperprotect.intercept.use` | UseInterceptor | `use` (20) |
| `hyperprotect.intercept.seat` | SeatInterceptor | `seat` (21) |
| `hyperprotect.intercept.respawn` | RespawnInterceptor | `respawn` (22) |

## Usage

```java
// Check if HyperProtect-Mixin is loaded at all
if ("true".equals(System.getProperty("hyperprotect.bridge.active"))) {
    // Register hooks
}

// Check specific interceptor
if ("true".equals(System.getProperty("hyperprotect.intercept.block_break"))) {
    bridge.set(0, new MyBlockBreakHook());
}
```

## Spawn Startup Behavior

The mob spawn hooks have special startup behavior to prevent unwanted spawns during server initialization.

### Bridge Flag Slots

| Slot | Index | Type | Default | Purpose |
|------|-------|------|---------|---------|
| `spawn_ready` | 13 | Boolean | `null` (false) | Whether the hook provider has finished initializing |
| `spawn_allow_startup` | 14 | Boolean | `null` (false) | Whether spawns are allowed before provider is ready |

### Behavior Matrix

| Hook Registered | spawn_ready | spawn_allow_startup | Result |
|----------------|-------------|---------------------|--------|
| Yes | — | — | Hook decides |
| No | `true` | — | Allow (no protection mod = no restrictions) |
| No | `false` | `true` | Allow (explicitly allowed during startup) |
| No | `false` | `false` | **Block** (safe default during startup) |

### Setting Ready Flag

When your protection mod finishes loading and is ready to handle spawn checks:

```java
@SuppressWarnings("unchecked")
AtomicReferenceArray<Object> bridge = (AtomicReferenceArray<Object>)
    System.getProperties().get("hyperprotect.bridge");

// Register your spawn hook at slot 8
bridge.set(8, new MySpawnHook());

// Mark spawn as ready at slot 13
bridge.set(13, Boolean.TRUE);
```

If your mod takes time to load data (e.g., loading regions from database), set `spawn_allow_startup` first if you want spawns to proceed during loading:

```java
bridge.set(14, Boolean.TRUE);  // Allow startup spawns
// ... load data async ...
bridge.set(8, new MySpawnHook());  // Register hook
bridge.set(13, Boolean.TRUE);     // Mark as ready
```
