# Prestige

Prestige is the Better Content Forge 1.20.1 mod for verified dedicated-server world resets, cold archives, rollback, World Condenser control, and the persistent lineage schematic library.

## Development

Java 17 is required. The checked-in Gradle wrapper provides the supported build and validation entrypoints:

```sh
./gradlew verifyFast
./gradlew verifyFull
./gradlew reobfJar
```

`verifyFast` runs the JVM tests. `verifyFull` also runs the Forge GameTests. Production deployment must use `build/reobfJar/output.jar`, not the development-mapped `jar` output.

The matching pack repository is [better-content/better-content](https://github.com/better-content/better-content). Its `prestige-server.sh` supervisor owns archive publication, successor health, retry, rollback, and exactly-once lineage advancement.

## License

Prestige is licensed under the GNU Affero General Public License version 3 or later. See `LICENSE`.

