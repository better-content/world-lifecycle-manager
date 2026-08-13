# World Lifecycle Manager

World Lifecycle Manager is the Better Content Forge 1.20.1 mod for verified dedicated-server world resets, cold archives, rollback, World Condenser control, and the persistent lineage schematic library.

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
