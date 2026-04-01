<div align="center">

<img width="200" height="200" src="./app/src/main/res/drawable/chimahon.png" alt="Chimahon icon" />
<h1 align="center">Chimahon</h1>

| Releases |
|----------|
| <div align="center"> [![GitHub downloads](https://img.shields.io/github/downloads/sohilsayed/chimahon/latest/total?label=Latest%20Downloads&labelColor=27303D&color=0D1111&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/sohilsayed/chimahon/releases/latest) [![GitHub downloads](https://img.shields.io/github/downloads/sohilsayed/chimahon/total?label=Total%20Downloads&labelColor=27303D&color=0D1111&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/sohilsayed/chimahon/releases) [![Release build](https://img.shields.io/github/actions/workflow/status/sohilsayed/chimahon/release.yml?labelColor=27303D&label=Release&labelColor=06599d&color=043b69)](https://github.com/sohilsayed/chimahon/actions/workflows/release.yml) |

*Requires Android 8.0 or higher.*

[Discord](https://discord.gg/zZAZXce7d)
[![License: GPL-3.0](https://img.shields.io/github/license/sohilsayed/chimahon?labelColor=27303D&color=0877d2)](/LICENSE)

## Download

[![Stable](https://img.shields.io/github/v/release/sohilsayed/chimahon.svg?maxAge=3600&label=Stable&labelColor=06599d&color=043b69)](https://github.com/sohilsayed/chimahon/releases/latest)

*Requires Android 8.0 or higher.*

<div align="left">
Chimahon is a language-learning-focused manga reader fork in the Mihon/Komikku ecosystem. It keeps the core reading experience while adding study-friendly workflows like dictionary import and lookup, OCR-assisted text capture in the reader, and flashcard creation for vocabulary mining.

<div align="left">

## Features

### Chimahon fork-specific features

- Built-in dictionary tab for importing and managing dictionary files.
- Native lookup pipeline with ranked results, glossary rendering, style support, and media support.
- OCR overlays in reader views with tap-to-select text and popup lookup.
- Anki integration for creating cards directly from lookup results.
- Flexible card field mapping for expression, reading, glossary, pitch, cloze, and sentence-based markers.
- A study-oriented workflow built around quick capture, lookup, and export while reading.

---

### Komikku inherited features 

![screenshots of app](./.github/readme-images/screens.png)

- `Suggestions` automatically showing source-website's recommendations / suggestions / related to current entry for all sources.
- `Hidden categories` to hide yours things from *nosy* people.
- `Auto theme color` based on each entry's cover for entry View & Reader.
- `App custom theme` with `Color palettes` for endless color lover.
- `Bulk-favorite` multiple entries all at once.
- Source & Language icon on Library & various places. (Some language flags are not really accurate)
- `Feed` now supports **all** sources, with more items (20 for now).
- Fast browsing (for who with large library experiencing slow loading)
- Grouped entries in Update tab (inspired by J2K).
- Update notification with manga cover.
- Auto `2-way sync` progress with trackers.
- Chips for `Saved search` in source browse
- `Panorama cover` showing wide cover in full.
- `Merge multiple` library entries together at same time.
- `Range-selection` for Migration.
- Ability to `enable/disable repo`, with icon.
- `Update Error` screen & migrating them away.
- `to-be-updated` screen: which entries are going to be checked with smart-update?
- `Search for sources` & Quick NSFW sources filter in Extensions, Browse & Migration screen.
- `Feed` backup/restore/sync/re-order.
- Long-click to add/remove single entry to/from library, everywhere.
- Docking Read/Resume button to left/right.
- In-app progress banner shows Library syncing / Backup restoring / Library updating progress.
- Auto-install app update.
- Configurable interval to refresh entries from downloaded storage.
- Forked from SY so everything from SY.
- Always up-to-date with Mihon & SY
- More app themes & better UI, improvements...


<details>
  <summary>Features from Mihon / Tachiyomi</summary>

#### All up-to-date features from Mihon / Tachiyomi (original), include:

* Online reading from a variety of sources
* Local reading of downloaded content
* A configurable reader with multiple viewers, reading directions and other settings.
* Tracker support: [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.app/), [MangaUpdates](https://mangaupdates.com), [Shikimori](https://shikimori.one), [Bangumi](https://bgm.tv/)
* Categories to organize your library
* Light and dark themes
* Schedule updating your library for new chapters
* Create backups locally to read offline or to your desired cloud service
* Continue reading button in library


</details>

## Issues, Feature Requests and Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

<details><summary>Issues</summary>

1. **Before reporting a new issue, take a look at the [changelog](https://github.com/sohilsayed/chimahon/releases) and the already opened [issues](https://github.com/sohilsayed/chimahon/issues).**
2. If you are unsure, ask here: [Discord](https://discord.gg/zZAZXce7d)

</details>

<details><summary>Bugs</summary>

* Include version (More → About → Version)
 * If not latest, try updating, it may have already been solved
 * Preview version is equal to the number of commits as seen on the main page
* Include steps to reproduce (if not obvious from description)
* Include screenshot (if needed)
* If it could be device-dependent, try reproducing on another device (if possible)
* Don't group unrelated requests into one issue

Use the [issue forms](https://github.com/sohilsayed/chimahon/issues/new/choose) to submit a bug.

</details>

<details><summary>Feature Requests</summary>

* Write a detailed issue, explaining what it should do or how.
* Include screenshot (if needed).
</details>

<details><summary>Contributing</summary>

See [CONTRIBUTING.md](./CONTRIBUTING.md).
</details>

<details><summary>Code of Conduct</summary>

See [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md).
</details>

<div align="center">

### Credits

Thank you to all the people who have contributed!

<a href="https://github.com/sohilsayed/chimahon/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=sohilsayed/chimahon" alt="Chimahon contributors" title="Chimahon contributors" width="800"/>
</a>

### Acknowledgments

- [Yomitan](https://github.com/yomidevs/yomitan): Some language-processing tooling and marker conventions in Chimahon are adapted from Yomitan-style workflows.
- [owocr](https://github.com/AuroraWright/owocr): Chimahon's OCR merge and reconstruction flow is based on owocr's approach.
- [hoshidicts](https://github.com/Manhhao/hoshidicts/): Chimahon uses this native dictionary engine (via a vendored submodule and JNI) for dictionary import, lookup.
- [Machita Chima (町田ちま)](https://www.youtube.com/channel/UCo7TRj3cS-f_1D9ZDmuTsjw): For app name inspiration and app icon as her pet hamster gonzalez

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this application hosts zero content.

<div align="left">

## License

This project is licensed under the GNU General Public License v3.0.

See [LICENSE](./LICENSE) for the full text.
