<p align="center"><img src="docs/icon.png" width="128" alt="CasTV icon"></p>

<h1 align="center">CasTV</h1>

An IPTV player for Android TV (Android 8.0 and newer). CasTV doesn't include any channels or content: add playlists
you are entitled to.

**Download:** the latest APK is on the [Releases](../../releases) page. Install it on the TV with a downloader app
or by copying it over, and allow installs from unknown sources.

## Features

- **Playlists:** M3U (link or local file), Xtream Codes (live TV, movies, shows) and Stalker portals (MAC). Any number
  of playlists of any type side by side, each with its own User-Agent, update interval and guide sources; reorder or
  turn them off.
- **TV guide:** XMLTV guides (plain or gzipped), matching by tvg-id and name, manual guide channel per channel, time
  offsets, priorities, days to keep.
- **Live TV:** channel list with groups, full guide grid, info and control panels, number zapping, previous channel,
  next-channel preview, catch-up (default/append/shift/Flussonic/Xtream/Stalker), rewinding live TV with catch-up,
  audio/subtitle/quality tracks, display modes and audio delay per channel, sleep timer, picture-in-picture, external
  player, auto frame rate, tunneled playback, software decoders (FFmpeg audio), DRM (Widevine/PlayReady/ClearKey).
- **Movies and shows:** categories, sorting, continue watching, My list, details, seasons and episodes, resume,
  autoplay next episode, trailers.
- **Organising:** favorites, own groups, renaming, hiding, blocking and manual ordering of channels and groups,
  name prefix/suffix removal.
- **Recordings** (now, scheduled, repeating; HLS and TS), **reminders**, **multiview** (2–4 screens), **search**
  (channels, programs, VOD, voice), **parental PIN**, **remappable remote buttons**, **backup and restore**, start on
  boot or wake-up.

## Building

`gradle :app:assembleRelease` with JDK 17, Gradle 8.14 and the Android SDK (API 36). Every push builds the APK on
GitHub Actions (`.github/workflows/build.yml`) and publishes it as a release.
