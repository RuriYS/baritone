# Baritone

Baritone is a Minecraft pathfinding system. This fork contains the Fabric build used by Meteor Client and targets Minecraft 26.2.

## Build

Requires JDK 25 and Gradle.

From this directory:

```sh
gradle build
```

From the Meteor workspace:

```sh
gradle :baritone:build
```

The Fabric mod and sources jars are written to `build/libs`.

## Usage

Install Fabric Loader, place `baritone-fabric-26.2-SNAPSHOT.jar` in the Minecraft `mods` directory, and use `#help` in chat for the current command list.

Common commands:

- `#goto <x> <z>` paths to coordinates.
- `#mine <block>` mines a block type.
- `#stop` stops the active process.
- `#modified` lists changed settings.

The supported API is under [`baritone.api`](src/api/java/baritone/api).

## License

See [LICENSE](LICENSE).
