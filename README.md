# World Lifecycle Manager

World Lifecycle Manager is the Better Content Forge 1.20.1 mod for verified dedicated-server world resets, cold archives, rollback, World Condenser control, and the persistent lineage schematic library.

The World Condenser also exposes a six-node, operator-controlled world-shaping perk tree. Each upcoming prestige supplies one point. Operators may respec before staging, while the staged build is transactionally bound to the successor and only becomes active after verified lineage advancement. Perks expand biome choices, improve or redirect spawn placement, add a fallback biome, and optionally authorize a fourth successor attempt; they never grant player inventory or disable lineage schematics.

## Development

Java 17 is required. The checked-in Gradle wrapper provides the supported build and validation entrypoints:

```sh
./gradlew verifyFast
./gradlew verifyFull
./gradlew reobfJar
```

`verifyFast` runs the JVM tests. `verifyFull` also runs the Forge GameTests. Production deployment must use `build/reobfJar/output.jar`, not the development-mapped `jar` output.

The matching sibling pack repository is [better-content/better-content-modpack](https://github.com/better-content/better-content-modpack). Its `world-lifecycle-manager-server.sh` supervisor owns archive publication, successor health, retry, rollback, and exactly-once lineage advancement.

## License

World Lifecycle Manager is licensed under the GNU Affero General Public License version 3 or later. See `LICENSE`.


## Identity

The clean-break canonical identity is repository/artifact `world-lifecycle-manager`, mod ID and resource namespace `world_lifecycle_manager`, and Maven group `com.bettercontent`. Legacy `prestige` identifiers and persisted paths are not migrated.
