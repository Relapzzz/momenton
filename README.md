# Momenton

A Fabric mod for Minecraft 1.21.11 that records, edits and exports gameplay inside the client.

Momenton records gameplay as state, not pixels. Instead of capturing your screen, it stores the
network packets, your camera pose every frame, and the client-side UI state you cannot get from
packets. Export replays that state and re-renders it into video, so the result looks like an OBS
recording of the session, including the HUD and inventory screens.

Recording as state rather than pixels means recordings are small, exports can be higher resolution
and frame rate than you actually played at, and HUD elements can be hidden after the fact.

Works with VulkanMod. All GPU work goes through Blaze3D; the mod makes no direct OpenGL calls.

## Controls

F9 starts and stops recording. Recordings land in `momenton/recordings/`.

F10 opens the replay browser, and stops playback if a replay is running. There is also a Replays
button on the title screen, since playing a replay does not need a world loaded. During playback, P
pauses and the bracket keys seek ten seconds back and forward.

## Building

    gradlew.bat build

The jar lands in `build/libs/`. Use `gradlew.bat runClient` for a dev instance and
`gradlew.bat genSources` to decompile Minecraft for navigation.

## Status

See `TASKS.md` for what currently works and what is next. `IMPLEMENTATION_PLAN.md` has the design
and the reasoning behind it.
