# tun

A self-hosted, ngrok-style tunnel: one GraalVM native binary with a server and a client mode.
The server routes requests and accepts client connections, and the client connects and gets its public URL;
relaying requests through the tunnel comes next (Story 1.8).

## Build and run

Requirements: GraalVM CE `25.4.4.1+1-graalce` (JDK 25.0.4.1) installed locally, for example with sdkman.
Gradle finds it through its toolchain detection (sdkman, `JAVA_HOME`); toolchain auto-download is off.

```sh
./gradlew build                                    # compile, unit tests, ArchUnit, detekt, rule fixtures
./gradlew nativeCompile                            # native executable
app/build/native/nativeCompile/tun --version       # tun 0.1.0
app/build/native/nativeCompile/tun server          # loads /etc/tun/tun.toml, then serves on bind:port
```

On macOS arm64 and Windows amd64 only the client is supported (`tun login`, `tun http`); the server is Linux-only.
CI does run `tun server` on those OSs, but only as a local test harness for the client smoke test; it is not supported
there.
Install the same GraalVM CE 25 build for that OS and point `JAVA_HOME` at it (on macOS, the JDK is under
`Contents/Home`). On Windows, `native-image` also needs the Visual Studio 2022 Build Tools (the C++ workload), and the
binary is `tun.exe`. CI builds and tests those two the same way:

```sh
./gradlew :wire:test :mux:test :client:test :app:nativeCompile
app/build/native/nativeCompile/tun login wss://tun.example.com <token>   # tun.exe on Windows
```

`tun login` writes `tun/config.toml` in the user config directory, readable only by the user: Linux
`$XDG_CONFIG_HOME` or `~/.config` and macOS `~/Library/Application Support`, both mode `0600` in a `0700` directory; Windows `%APPDATA%`,
with an ACL holding one entry for the user and nothing inherited.

No jar is shipped: the only distributable is the native `tun` binary.
`app/src/main/resources/META-INF/native-image/tun/app/reflect-config.json` registers three Netty classes' methods:
Netty looks them up by name at startup, GraalVM does not honour Netty's own hints for them, and without them the
server cannot bind.

## `tun.toml`

`tun server [--config-dir /etc/tun] [--state-dir /var/lib/tun]` reads `<config-dir>/tun.toml` at startup and never
writes it. A missing or invalid file exits 1 with one line on stderr naming the problem. Unknown keys are ignored.

```toml
version = 1

[server]
domain = "tun.example.com"   # required: a bare host name, no scheme, port or path
bind = "127.0.0.1"           # default
port = 8000                  # default
grace = "60s"                # default; durations are positive, like "60s" or "1m"
ping_interval = "20s"        # default

[[token]]                    # zero or more; with none, every client is rejected
alias = "laptop"             # unique; the only name logs ever show
hash = "sha256:<64 hex chars>"
level = "random"             # "random" or "named"
created = "2026-11-08"       # optional
```

Make a token, give the first line to the client, and write the second as `hash = "sha256:<hex>"`:

```sh
t=$(openssl rand -hex 32); echo "$t"; printf %s "$t" | sha256sum
```

## Serving

`tun server` listens on `bind:port` (default `127.0.0.1:8000`) for plain HTTP/1.1 and WebSocket, with no TLS of its
own. Put a TLS-terminating proxy (nginx, NPM, Caddy) in front and send it both the apex (`tun.example.com`) and the
wildcard (`*.tun.example.com`), keeping the `Host` header and allowing WebSocket upgrades. Every request is routed by
`Host`:

- `tun.example.com/_tun/health` answers `200 ok`, for the proxy's or an uptime check.
- `tun.example.com/_tun/connect` is the clients' control WebSocket.
- `<name>.tun.example.com` goes to that client's tunnel; until Story 1.8 it answers `502 tun: relay not implemented`.
- Anything else is `404`.

If the port cannot be bound, the server exits 1 with one line on stderr.

## Client

```sh
tun login wss://tun.example.com <token>   # once: saves the server URL and token, contacts nothing
tun http 8080                             # connects, prints https://<name>.tun.example.com, stays up
```

The client runs on Linux, macOS and Windows (the server on Linux only). `tun login` writes `server` and `token` to
`config.toml` in the user config directory:

| OS | Path |
|----|------|
| Linux | `$XDG_CONFIG_HOME/tun/config.toml` if that is set and absolute, else `$HOME/.config/tun/config.toml` |
| macOS | `$HOME/Library/Application Support/tun/config.toml` |
| Windows | `%APPDATA%\tun\config.toml` |

The file is private to you from the moment it exists. On Linux and macOS it is mode `0600`, in a directory created
`0700`. On Windows it gets an ACL that grants only the current user full control, with no inherited entries. It is
written to a temp file in the same directory, then moved into place atomically, and logging in again replaces it. If
it cannot be written, for example on a file system that supports neither POSIX permissions nor ACLs, `tun login`
prints one `tun: cannot write <path> (<why>); check its permissions` line. Only `wss://<host>[:port]` is accepted, with no path; `ws://` only for
`localhost[:port]`, for a server on the same machine. `wss://` checks the server certificate against the default trust
store and the host name; there is no option to skip that. The token is never printed.

`tun http <port>` opens `<server>/_tun/connect`, sends HELLO with the saved token, prints the public URL alone on
stdout and keeps the connection alive with a WebSocket ping every server-given interval (default 20 s); after 3
silent intervals it exits 1. Any failure is one `tun: ...` line on stderr naming the fix. Until Story 1.8 nothing is
relayed to `<port>`: requests to the URL get `502`, and streams the server opens are reset.

## Modules (AD-1)

`core` (domain, plain Kotlin) · `wire` (the only Netty user) · `mux` (`io.netty.buffer` only) ·
`server`, `client` (composition roots) · `app` (native main, nothing depends on it) · `e2e`.
`architecture-tests` checks the AD-1 graph with ArchUnit; `detekt-rules` holds the custom `DslBlockBudget` rule.
All versions live in `gradle/libs.versions.toml`.

## Readability rules, enforced by `./gradlew build`

| Rule | Where | Proof that it fires |
|------|-------|---------------------|
| AD-1 layering (ArchUnit) | `architecture-tests` | violation fixtures in its test source set |
| `LongMethod` 20 lines, `NestedBlockDepth` 3 (AD-3) | `wire`, `mux` main code; `config/detekt/detekt.yml` | `:detekt-rules:verifyHotPathFixtures` runs detekt over one over-budget file under a `wire` and a `core` path |
| `DslBlockBudget` 5 lines or sub-blocks (AD-2) | every module except `detekt-rules`, which lints itself without its own plugin (`detektMain`/`detektTest`, type resolution) | rule tests over passing and failing samples; `:detekt-rules:verifyDslFixture` runs the real plugin and config over an over-budget sample |

## Decisions

- **detekt 2.0.0-alpha.6 works with Kotlin 2.4.21**, so the 1.23.8 CLI fallback is not used (resolves the
  architecture's open question). detekt 2.0.0-alpha.6 embeds the Kotlin 2.4.10 compiler and analyses the
  2.4.21 sources, including the Analysis API type resolution `DslBlockBudget` needs.
- **GraalVM CE graal-25.4.4.1.1 instead of the architecture's graal-25.5.5.0.3** (user decision, 2026-11-08: no new
  JVM downloads locally). CI installs graal-25.4.4.1.1 (pinned by sha256). Locally, the build uses whichever GraalVM 25
  Gradle detects, currently the sdkman CE `25.4.4.1+1-graalce`: the toolchains ask for vendor `GRAAL_VM`, JDK 25 and
  `nativeImageCapable = true`. That rules out other native-image JDKs such as Liberica NIK, but pins neither the
  release nor CE over other GraalVM builds. GraalVM is the one JDK for compiling, testing and `native-image`.
