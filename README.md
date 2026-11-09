# OpenCode Forge

[![Release](https://img.shields.io/github/v/release/dlfinis/OpenCode_Forge)](https://github.com/dlfinis/OpenCode_Forge/releases)
[![Build](https://github.com/dlfinis/OpenCode_Forge/actions/workflows/build.yml/badge.svg)](https://github.com/dlfinis/OpenCode_Forge/actions/workflows/build.yml)
![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2025.2%2B-orange)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

**OpenCode Forge** integrates [OpenCode](https://opencode.ai) — the open-source AI coding agent —
natively into JetBrains IDEs. Connect to an OpenCode v2 server and drive an autonomous agent without
leaving the editor: launch a session with one keystroke, share code context, and review every
AI-generated change in the IDE's native diff viewer.

> Community continuation of **[OpenCode UI](https://github.com/LaiZhou/OpenCode_UI)** by LaiZhou,
> forked as an improvement over the original: full **OpenCode v2** compatibility and active
> development. See [Credits and lineage](#credits-and-lineage).

## Features

| Feature | What it does | Mac | Win/Linux |
|---|---|---|---|
| **Quick Launch** | Connect to a running OpenCode server or spin up a new terminal session | `Cmd + Esc` | `Ctrl + \` |
| **Context Sharing** | Send the current file, the editor selection, or Project View items to the agent draft | `Opt + Cmd + K` | `Ctrl + Alt + K` |
| **Diff Review** | Native multi-file diff viewer with one-click Accept / Reject | — | — |
| **Task Notifications** | System alert the moment the agent finishes | — | — |
| **Auto-Resume** | Restores the last session on launch | — | — |
| **Smart Links** | `@file#L10-20` references in terminal output are clickable | — | — |
| **Authentication** | Auto-detected credentials or optional server password | — | — |
| **Local Change Alert** | Warns when your edits diverge from the AI output | — | — |

Shortcuts are customizable in **Settings → Keymap** (search *OpenCode*). The plugin is also reachable
from the **OpenCode** tool-window icon and from editor / Project View context menus
(*OpenCode: Add Context* / *Add File(s)*).

## Requirements

- **JetBrains IDE** 2025.2 or newer (IntelliJ IDEA, PyCharm, WebStorm, GoLand, …)
- **OpenCode CLI v2** — `curl -fsSL https://opencode.ai/v2/install | bash`, or see
  [opencode.ai/download](https://opencode.ai/download)
- The plugin speaks the **v2 server API only** (`/api/info`, `/api/session`, patch-based diffs).
  A v1 server is detected and reported as unsupported — upgrade the CLI first.

## Installation

Not on the JetBrains Marketplace yet — install from the release archive:

1. Download `OpenCode_Forge-<version>.zip` from [Releases](https://github.com/dlfinis/OpenCode_Forge/releases).
2. Open **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Select the archive and restart the IDE.

OpenCode Forge declares its own plugin id (`io.github.dlfinis.opencode.forge`), so it coexists with
the original OpenCode UI instead of upgrading it in place. Uninstall the original if you don't want both.

## Usage

### 1. Connect or launch

`Cmd+Esc` / `Ctrl+\` opens the connection dialog:

- **Existing server** — enter `host:port` (e.g. `127.0.0.1:58052`) to attach to OpenCode Desktop or
  any running server, over http or https, local or remote. Credentials are detected automatically;
  a password is optional.
- **New terminal** — keep the default `127.0.0.1:4096` to spin up a local session.

Your last connection settings are remembered. Each project keeps a single terminal tab named
`OpenCode({port})` that persists across actions; if you close it, the next launch creates a fresh one.

![Connection dialog](images/1.png)

### 2. Share code context

`Opt+Cmd+K` / `Ctrl+Alt+K` from the editor or Project View inserts a reference into the agent draft —
it is not submitted, so you can type your request around it:

- **Editor selection** → `@path/to/file.kt#L10-25`
- **Editor, no selection** → the current file
- **Project View** → one reference per selected file

![Select context](images/2.png)
![Reference in the terminal draft](images/3.png)

### 3. Review diffs

When the agent finishes a response, the native diff viewer opens automatically with every file it
touched, in chronological order:

- **Navigate** — `← →` switch files, `↑ ↓` jump between changes; the title tracks progress (e.g. `1 of 5`).
- **Accept** — keeps the AI change and stages the file (`git add`), then opens the next one.
- **Reject** — restores the file to its snapshot from before the AI edited it, then opens the next one.
- A **`(Local Modified)`** marker appears when your file differs from the AI output.

![Diff viewer — accept](images/5.png)
![Diff viewer — reject](images/6.png)

### 4. Stay in flow

- **Notifications** fire when a task completes (Busy → Idle), so you can switch to other work while
  the agent runs. On macOS, allow notifications for your IDE in *System Settings → Notifications*.
- **Smart links** — clicking a `@src/main/kotlin/Main.kt#L10-20` reference in terminal output opens
  the file and highlights the lines.
- The **OpenCode tool-window icon** focuses or creates the session at any time.

![Tool window](images/4.png)

## Roadmap

Planned improvements, tracked in [releaseNotes/](releaseNotes/) and the
[issue tracker](https://github.com/dlfinis/OpenCode_Forge/issues):

- Publish on the JetBrains Marketplace
- Connection recovery on reconnect
- More robust diff review across working directories
- Checkout-aware diff attribution (separate `git checkout` changes from AI edits)

## Troubleshooting

| Symptom | Fix |
|---|---|
| `opencode: command not found` | Install the CLI: `curl -fsSL https://opencode.ai/v2/install | bash` (or `npm install -g @opencode/cli`) |
| Terminal not responding | Close the `OpenCode({port})` tab and press `Cmd+Esc` / `Ctrl+\` again for a fresh session |
| Shortcuts not working | Look for conflicts in **Settings → Keymap** and reassign |

## Support

- **This plugin** — [GitHub Issues](https://github.com/dlfinis/OpenCode_Forge/issues)
- **OpenCode itself** — [documentation](https://opencode.ai/docs) · [Discord](https://opencode.ai/discord)

## Credits and lineage

OpenCode Forge is a derivative work of **[OpenCode UI](https://github.com/LaiZhou/OpenCode_UI)** by
**LaiZhou**, under the MIT License; the full commit history is preserved in this repository.

The fork was made as an improvement over the original. OpenCode UI targeted the OpenCode **v1**
server API and stopped receiving updates; OpenCode Forge was rebuilt around **v2** — typed `/api/...`
clients, patch-based diffs, https and remote connections, session resume, shared-service discovery,
and a v1 server detected and reported as unsupported instead of failing silently. Every change the
fork introduces is recorded in [releaseNotes/](releaseNotes/), and development continues under the
[roadmap](#roadmap) above.

- **LaiZhou** — original project: plugin architecture, terminal, diff viewer, notifications, documentation
- **Milos Zivkovic** — connection dialog base-path field and project-module selector
- **Michael Bauer** — README improvements

## License

MIT — see [LICENSE](LICENSE).
