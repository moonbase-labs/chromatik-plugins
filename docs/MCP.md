# 🤖 Driving Chromatik from an AI agent

`chromatik-mcp` runs a [Model Context Protocol](https://modelcontextprotocol.io) server inside
Chromatik, so an agent such as Claude Code can look at the rig and build a show on it.

**It installs differently from the other packages.** Dropping the jar in is not enough: a plugin has
to be enabled before it runs.

1. Put `chromatik-mcp-<version>.jar` in `~/Chromatik/Packages`.
2. **Chromatik → Preferences → Plugins**, tick **Chromatik MCP**.
3. Restart Chromatik. The log prints the URL, and `~/Chromatik/LaserphileMCP/server.json` holds it too.
4. `claude mcp add --transport http chromatik http://127.0.0.1:3579/mcp`

Port 3579 by default, and the next free port above it if something already has that one. Override
with `-Dlaserphile.mcp.port` or `LASERPHILE_MCP_PORT`.

Eight tools. Everything is addressed by canonical LX path, so the output of one call is the input to
the next.

| Tool | What it does |
|---|---|
| `lx_project` | The model's size and bounds, engine and tempo, output state, every channel |
| `lx_catalog` | Which patterns, effects and modulators are installed, with class names |
| `lx_docs` | What a component's parameters mean: path key, label, Java field, range, enum options |
| `lx_device` | One component's live values |
| `lx_set` | Set parameters in a batch. Undoable |
| `lx_add` | Add a channel, pattern or effect. Undoable |
| `lx_undo` | Step back through Chromatik's own undo history |
| `lx_look` | A PNG of what the rig is showing, plus brightness and coverage statistics |

Writes go through `LXCommand`, so an agent's work lands in Chromatik's undo history and Cmd-Z
reverses it exactly as if it had been clicked.

`lx_docs` earns its place on this repo's own plugins. A parameter's path key, its Java field name
and its panel label can all differ: the field `wrapMode` is keyed `wrap` and labelled "Wrap", and
`fileName` is keyed `file`. Paths use the key, so an agent reading the table further down this
README and guessing would build a path that does not resolve. `lx_docs` gives all three, plus the
enum option names, which appear nowhere in Chromatik's OSCQuery output.

> [!WARNING]
> There is no authentication. The server binds `127.0.0.1` only and validates the `Origin` header,
> and it deliberately offers no option to bind anywhere else. Anything that can reach the port can
> drive the rig and edit the project.

The server never raises output brightness or enables output; it can only lower them. Point it at a
project you have saved.
