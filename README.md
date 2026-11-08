# tun

A self-hosted, ngrok-style tunnel: one GraalVM native binary with a server and a client mode.
Story 1.1 is the scaffold: the modes are stubs.

## Build and run

Requirements: GraalVM CE `25.4.4.1+1-graalce` (JDK 25.0.4.1) installed locally, for example with sdkman.
Gradle finds it through its toolchain detection (sdkman, `JAVA_HOME`); toolchain auto-download is off.

```sh
./gradlew build                                    # compile, unit tests, ArchUnit, detekt, rule fixtures
./gradlew nativeCompile                            # native executable
app/build/native/nativeCompile/tun --version       # tun 0.1.0
app/build/native/nativeCompile/tun server          # loads /etc/tun/tun.toml; "listener not implemented" on stderr, exit 1
```

No jar is shipped: the only distributable is the native `tun` binary.

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
