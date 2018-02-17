# Consoles

[![CircleCI](https://img.shields.io/circleci/project/github/IncognitoJam/Consoles.svg?style=for-the-badge)](https://circleci.com/gh/IncognitoJam/Consoles/tree/master)
[![GitHub issues](https://img.shields.io/github/issues/IncognitoJam/Consoles.svg?style=for-the-badge)](https://github.com/IncognitoJam/Consoles/issues)
[![GitHub pull requests](https://img.shields.io/github/issues-pr/IncognitoJam/Consoles.svg?style=for-the-badge)](https://github.com/IncognitoJam/Consoles/pulls)
[![GitHub forks](https://img.shields.io/github/forks/IncognitoJam/Consoles.svg?style=for-the-badge)](https://github.com/IncognitoJam/Consoles/network)
[![GitHub stars](https://img.shields.io/github/stars/IncognitoJam/Consoles.svg?style=for-the-badge)](https://github.com/IncognitoJam/Consoles/stargazers)

A powerful set of plugins that provides programmable computers and a powerful map rendering API for craftbukkit and spigot servers. **Requires Java 8!**

I have brought this plugin up to date with Minecraft 1.12.2. Although I have not tested everything I hope it is usable enough in its current state for people to enjoy again like it last was in 1.8. If anyone with more experience using the plugin could test it out on their server it would be greatly appreciated.

### Licensing

[LGPL Version 3](http://www.gnu.org/licenses/lgpl-3.0.en.html) for `consoles-api`

[GPL Version 3](https://www.gnu.org/licenses/gpl.html) for `consoles-core`, `consoles-computer`, `consoles-bungee`, `consoles-nms-api`, and all NMS versioned consoles modules.

License for `consoles-fetcher`: see source file(s)

### Builds

Build artifacts can be found on the [CircleCI builds page](https://circleci.com/gh/IncognitoJam/Consoles/) although I intend to set up a more user friendly repository for builds soon (perhaps uploading the releases to GitHub).

**warning:** use snapshot builds with care, they can be extremely unstable.

### Bug Reporting

Bug reporting is done using GitHub's bug tracker. For how to create detailed reports, see **[reporting bugs](https://github.com/wacossusca34/Consoles/wiki/Reporting-Bugs)**.

### API Overview

The API provides:

- A replacement for the default map renderer in the minecraft server
- A fast interface for painting to 'canvases' (that are actually a grid of maps)
- A workaround for this issue: https://bugs.mojang.com/browse/MC-46345
- Pixel-accurate interaction events with the map canvas and its components
- Different map data for each player (per-player rendering)
- Hooks for overriding command block functionality to link up to console components

Behind the scenes, this API:

- Contains a threaded painting system, calling repaints as little as possible for each player
- Contains its own packet listener, no need for ProtocolLib!
- Provides a basic component set for building interfaces with the console
- Provides _streaming_ support, so you can effectively map input and output to console components

Non-API features:

- Fully programmable Computers (separate plugin). Refer to the wiki.
- Reliable image rendering from URLs!

Notes:
 - This replaces maps, and _completely_ removes handheld map functionality. Fake map handlers/items are injected to ensure that the normal map system does not send packets and map out world regions.
 - This plugin/API is strictly for _map canvases_, which are sets of (modified) item frames in a grid containing maps that can display pixels in its own screen coordinates, for each player.
 - My code has very large NMS backends, which means writing support for older server versions and keeping this up to date will be difficult.
