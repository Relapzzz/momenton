# tools

Offline checks for a `.momenton` file, so a recording can be verified without launching the game.
They are the `tools` source set in `build.gradle`: compiled by `./gradlew build` against the mod's own
classes and Minecraft, but not shipped in the jar. Run them from the project root.

```sh
./gradlew checkReplay -Preplay=<absolute path to .momenton>
./gradlew runTool -Ptool=Ids -Pargs="<file> tools/s2c_ids.txt"
```

`checkReplay` runs Inspect, Setup, Motion, Swing and Boundary and fails if any of them does. `runTool`
runs any one tool; `-Pargs` is split like a shell command line, so quote a path that has spaces in it
with single quotes inside the double ones. The container format itself is covered by the JUnit test
`ReplayFormatTest`, which `./gradlew build` runs.

| Tool | What it checks |
|---|---|
| `Inspect` | A recording: every track parses, ticks are ordered and inside each chunk's advertised range, `frames.bin` is `56 * framesRendered`, camera poses are finite. |
| `Setup` | `setup.bin` frames cleanly and in the right order; every chunk has a snapshot that leads with a join, recentres the chunk map before its chunks and announces `INITIAL_CHUNKS_COMING`; every dimension change carries a respawn, a player position and an `INITIAL_CHUNKS_COMING` after it. |
| `Ids` | Names every recorded packet by id, with counts and tick ranges, and reports whether the filtered-out ones are absent. Takes the replay and `tools/s2c_ids.txt`; a third argument dumps every packet of that id. |
| `Order` | The action sequence over a tick window, in file order. Takes the replay and two ticks. Counts cannot see ordering, and ordering is what breaks. |
| `Density` | Packets and entity-move actions per tick over the recording, in blocks of 25 ticks. |
| `Motion` | The `ENTITY_MOVE` track: that every payload decodes to exactly its own length, that every chunk opens with absolute poses so a seek can resolve the deltas after it, and that the local player's recorded path sits under the first-person camera in `frames.bin`. Also cross-checks the sneak flag against the camera's eye height. Fails on any recording made before the local player was added to the track. |
| `Swing` | The swing and cooldown halves of the `UI_STATE` track: that the recorded `handSwingTicks` sequence is one `tickHandSwing` could have produced, so playback can drive the animation from it. Counts swing starts, how many of those are restarts mid-swing, and cooldown resets. |
| `Boundary` | The chunk boundary race: for every chunk after the first, whether each chunk-data or entity-spawn packet stamped with that chunk's start tick is in its snapshot. Playback skips every action at or before the snapshot tick, so one that is in neither is lost on a seek. |
| `Effects` | Names the impact effects a recording carries - sweep arcs, crit animations, damage indicators, damage events - with their ticks. This is what settles whether a missing hit effect is a recording gap or a playback one. Needs `tools/particle_ids.txt`. |

`s2c_ids.txt` maps S2C play packet ids to names for 1.21.11+build.6 and **must be regenerated after a
Minecraft or Yarn bump**. Ids are assigned by registration order in `PlayStateFactories`, so:

```sh
MCJAR=~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2/minecraft-merged-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar
javap -p -c -cp "$MCJAR" net.minecraft.network.state.PlayStateFactories > psf.txt
# the S2C lambda is the longer of the two private static methods, the one starting with PlayPackets.BUNDLE
sed -n '<first>,<last>p' psf.txt \
  | grep -oE "Field net/minecraft/network/packet/[A-Za-z]+\.[A-Z_0-9]+:Lnet/minecraft/network/packet/PacketType;" \
  | sed -E 's|Field net/minecraft/network/packet/||; s|:Lnet/minecraft/network/packet/PacketType;||' \
  | nl -v0 -ba | sed 's/^ *//' > tools/s2c_ids.txt
```

Match on the `PacketType` field type, not on `PlayPackets.`: the `CommonPackets`, `CookiePackets` and
`PingPackets` entries are interleaved among them, and dropping those 15 shifts every id above 23 and
produces a mapping that looks plausible and is wrong.

`Setup` reads `tools/s2c_ids.txt` from the working directory, which the Gradle tasks set to the project root.

`particle_ids.txt` does the same for particle types, which `Effects` needs and which are numbered by
registration order in `ParticleTypes`:

```sh
javap -p -c -cp "$MCJAR" net.minecraft.particle.ParticleTypes \
  | grep -oE '// String [a-z_]+' | sed -E 's|// String ||' \
  | nl -v0 -ba | sed 's/^ *//' > tools/particle_ids.txt
```
