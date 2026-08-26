# World Lifecycle Manager

World Lifecycle Manager is the Better Content Forge 1.20.1 mod for verified dedicated-server world resets, cold archives, rollback, World Condenser control, and the persistent lineage schematic library.

The World Condenser also exposes a six-node, operator-controlled world-shaping perk tree. Each upcoming prestige supplies one point. Operators may respec before staging, while the staged build is transactionally bound to the successor and only becomes active after verified lineage advancement. Perks expand biome choices, improve or redirect spawn placement, add a fallback biome, and optionally authorize a fourth successor attempt; they never grant player inventory or disable lineage schematics.

Create Schematicannons gain persistent per-cannon material substitutions. Open the normal cannon menu, select a required ordinary block, then click the Fallback ghost slot while carrying the replacement block. The server validates the rule, pauses an active cannon when rules change, preserves compatible block-state properties, and only substitutes when the original is unavailable. Fluid-containing blocks, block entities, multi-item requirements, cycles, and self-substitutions are rejected. Native uses of the fallback material are reserved before substitutions consume it.

## Development

Java 17 is required. The checked-in Gradle wrapper provides the supported build and validation entrypoints:

```sh
./gradlew verifyFast
./gradlew verifyFull
./gradlew verifyFull stageRuntimeJar
```

`verifyFast` runs the JVM tests. `verifyFull` also runs the Forge GameTests. `stageRuntimeJar` copies the reobfuscated production artifact to `build/libs/world-lifecycle-manager-0.1.0.jar`; deploy that canonical staged JAR.

The non-shipping `visualHarness` source set validates the real Create menu. Run the `visualServer` and `visualClient` configurations under Xvfb, then issue `wlmvisual prepare <player>` and `wlmvisual capture <player> <name>` through the dedicated-server console. The server commands create the fixture and open the menu; the client harness only waits for that screen and invokes Minecraft's screenshot API. It does not inject player movement, mouse, keyboard, or gameplay controls.

The matching sibling pack repository is [better-content/better-content-modpack](https://github.com/better-content/better-content-modpack). Its `world-lifecycle-manager-server.sh` supervisor owns archive publication, successor health, retry, rollback, and exactly-once lineage advancement.

## License

World Lifecycle Manager is licensed under the GNU Affero General Public License version 3 or later. See `LICENSE`.


## Identity

The clean-break canonical identity is repository/artifact `world-lifecycle-manager`, mod ID and resource namespace `world_lifecycle_manager`, and Maven group `com.bettercontent`. Legacy `prestige` identifiers and persisted paths are not migrated.
