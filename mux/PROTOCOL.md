# tun mux protocol, version 1

This document is normative (AD-8). The client and the server must agree with it byte for byte, and the golden
vectors in [`src/test/resources/golden/`](src/test/resources/golden/) are checked by both directions of the codec
(`FrameCodec.kt`, tested by `GoldenVectorTest`). If this document and a vector disagree, that is a bug in one of them.

## Conventions

- All integers are unsigned and big-endian (network order).
- `u8`, `u16`, `u32` are 1, 2 and 4 bytes.
- `str` is a `u16` byte length followed by that many bytes of UTF-8. Length 0 is the empty string. A string never
  exceeds 65535 bytes; an encoder must refuse rather than truncate. A receiver decodes malformed UTF-8 with
  U+FFFD replacement characters; it does not reject it.
- Every `u32` in this protocol (stream ids, windows, `maxFrame`, increments, the ping interval) is at most
  `2^31 - 1`. A receiver rejects a `u32` field with the top bit set.

## Framing

The mux runs over one WebSocket connection (`/_tun/connect`). Each mux frame is exactly one **binary** WebSocket
message; continuation fragments are reassembled before decoding. A message holds one frame and nothing else.

| Offset | Size | Field      | Meaning                                  |
|-------:|-----:|------------|------------------------------------------|
| 0      | 4    | `streamId` | `u32`, `0` for the connection itself     |
| 4      | 1    | `type`     | `u8`, see [Type codes](#type-codes)      |
| 5      | 1    | `flags`    | `u8`, see [Flags](#flags)                |
| 6      | n    | payload    | the rest of the message, `n <= maxFrame` |

`maxFrame` bounds the payload only, not the 6-byte header. Each side announces the largest payload it accepts in
`HELLO` / `HELLO_OK`; before `HELLO` has been read the server applies its own limit.

### Type codes

| Code | Type            | Stream   | Flags allowed |
|-----:|-----------------|----------|---------------|
| 0    | `HELLO`         | 0 only   | none          |
| 1    | `HELLO_OK`      | 0 only   | none          |
| 2    | `HELLO_ERR`     | 0 only   | none          |
| 3    | `OPEN`          | not 0    | `END`         |
| 4    | `HEADERS`       | not 0    | `END`         |
| 5    | `DATA`          | not 0    | `END`         |
| 6    | `WINDOW_UPDATE` | not 0    | none          |
| 7    | `RESET`         | not 0    | none          |

Codes 8–255 are unknown and are a protocol error.

### Flags

| Bit    | Name  | Meaning                                                                   |
|--------|-------|---------------------------------------------------------------------------|
| `0x01` | `END` | the sender half-closes the stream (HTTP/2 `END_STREAM`); `OPEN`/`HEADERS`/`DATA` only |
| `0xFE` | —     | reserved, must be 0                                                       |

### Protocol errors

A receiver treats each of these as a protocol error. The decoder releases the input buffer either way:

- the message is shorter than 6 bytes, or a fixed field or a `str` length runs past its end;
- the payload is longer than `maxFrame`;
- an unknown type code;
- a reserved flag bit set, or `END` on a type other than `OPEN`, `HEADERS`, `DATA`;
- `HELLO`, `HELLO_OK` or `HELLO_ERR` on a stream other than 0, or any other type on stream 0;
- a `u32` field of `2^31` or more;
- a `RESET` code outside 1–5;
- bytes left over after a fixed-size payload (`HELLO*`, `WINDOW_UPDATE`, `RESET`).

## Payloads

### `HELLO` (0)

Sent by the client, first, on stream 0. The server closes the connection if it does not arrive within 10 s.

| Field      | Type  | Meaning                                                    |
|------------|-------|------------------------------------------------------------|
| `version`  | `u16` | protocol version, `1` for this document                    |
| `token`    | `str` | the token as 64 hex ASCII characters (32 bytes)        |
| `name`     | `str` | requested name; length 0 means none (assign a random one)  |
| `window`   | `u32` | the client's initial per-stream window, in bytes           |
| `maxFrame` | `u32` | the largest payload the client accepts, in bytes           |

The codec decodes any `version` and any token text; checking them is the handshake's job, answered with
`HELLO_ERR`.

Vectors: [`hello.hex`](src/test/resources/golden/hello.hex),
[`hello-no-name.hex`](src/test/resources/golden/hello-no-name.hex).

### `HELLO_OK` (1)

The server's answer to an accepted `HELLO`, on stream 0. The server sends exactly one of `HELLO_OK` or `HELLO_ERR`.

| Field          | Type  | Meaning                                                    |
|----------------|-------|------------------------------------------------------------|
| `name`         | `str` | the assigned name                                          |
| `url`          | `str` | the public URL, e.g. `https://myapp.example.com`           |
| `window`       | `u32` | the server's initial per-stream window, in bytes           |
| `maxFrame`     | `u32` | the largest payload the server accepts, in bytes           |
| `pingInterval` | `u32` | WebSocket ping interval, in milliseconds                   |

The client pings every `pingInterval`; the server always pongs. Either side declares the peer dead after 3
intervals without a ping or pong.

Vector: [`hello-ok.hex`](src/test/resources/golden/hello-ok.hex).

### `HELLO_ERR` (2)

The server's answer to a refused `HELLO`, on stream 0, followed by close.

| Field     | Type  | Meaning                                         |
|-----------|-------|-------------------------------------------------|
| `code`    | `u16` | error code; values are assigned by the handshake |
| `message` | `str` | human-readable reason that names the fix        |

Vector: [`hello-err.hex`](src/test/resources/golden/hello-err.hex).

### `OPEN` (3) and `HEADERS` (4)

`OPEN` starts a stream with the request head; `HEADERS` carries the response head or trailers. The payload is a
[header block](#header-blocks). `END` on `OPEN` means the request has no body.

The first non-1xx `HEADERS` on a stream is the response head. A later `HEADERS` is trailers and must carry `END`.
A visitor WebSocket is an `OPEN` carrying the upgrade headers; after `HEADERS` with `:status 101`, `DATA` carries
the raw bytes in both directions.

Stream ids are allocated by the server, only go up within one WebSocket and are never reused. Frames for a closed
or unknown stream are ignored.

Vectors: [`open.hex`](src/test/resources/golden/open.hex), [`open-end.hex`](src/test/resources/golden/open-end.hex),
[`headers.hex`](src/test/resources/golden/headers.hex),
[`headers-trailers-end.hex`](src/test/resources/golden/headers-trailers-end.hex).

### `DATA` (5)

| Field   | Type   | Meaning                                   |
|---------|--------|-------------------------------------------|
| payload | bytes  | body bytes, the whole payload (may be empty) |

An empty `DATA` with `END` half-closes without sending bytes. Only `DATA` payload bytes count against credit.

Vectors: [`data.hex`](src/test/resources/golden/data.hex),
[`data-empty-end.hex`](src/test/resources/golden/data-empty-end.hex).

### `WINDOW_UPDATE` (6)

| Field       | Type  | Meaning                                         |
|-------------|-------|-------------------------------------------------|
| `increment` | `u32` | bytes of credit returned to the peer for this stream |

Credit is per stream only. The receiver sends `WINDOW_UPDATE` only after the `DATA` bytes have been written to the
next hop: that is the backpressure rule.

Vector: [`window-update.hex`](src/test/resources/golden/window-update.hex).

### `RESET` (7)

Abnormal termination of a stream, in either direction. A normal end uses `END`, never `RESET`.

| Field  | Type  | Meaning            |
|--------|-------|--------------------|
| `code` | `u16` | one of the codes below |

| Code | Name                  |
|-----:|-----------------------|
| 1    | `cancel`              |
| 2    | `backend_unreachable` |
| 3    | `protocol_error`      |
| 4    | `flow_control`        |
| 5    | `refused`             |

Vector: [`reset.hex`](src/test/resources/golden/reset.hex).

## Header blocks

The payload of `OPEN` and `HEADERS` is zero or more fields, repeated to the end of the payload (an empty block is
allowed):

| Field   | Type  |
|---------|-------|
| `name`  | `str` |
| `value` | `str` |

- Requests use the HTTP/2 pseudo-headers `:method`, `:scheme`, `:authority`, `:path`; responses use `:status`.
  Pseudo-headers come first and are ordinary fields in the block.
- Names are lowercase. The encoder lowercases them.
- Hop-by-hop headers are stripped by the encoder: `connection`, `keep-alive`, `transfer-encoding`, `upgrade`, and
  `te` unless its value is `trailers`.
- Values are UTF-8, so any character, including Latin-1 above `0x7F` (two bytes on the wire), round-trips unchanged.
- A head bigger than `maxFrame` cannot be sent; the relay answers it (431) rather than the codec.

## Golden vectors

One file per vector in [`src/test/resources/golden/`](src/test/resources/golden/). A file is hex digits; whitespace
and everything after `#` on a line are ignored, and each line names the field it holds. `GoldenVectorTest` fails if
a file has no frame in its table or a table entry has no file.

| File | Frame |
|------|-------|
| [`hello.hex`](src/test/resources/golden/hello.hex) | `HELLO` with a name |
| [`hello-no-name.hex`](src/test/resources/golden/hello-no-name.hex) | `HELLO` without a name |
| [`hello-ok.hex`](src/test/resources/golden/hello-ok.hex) | `HELLO_OK` |
| [`hello-err.hex`](src/test/resources/golden/hello-err.hex) | `HELLO_ERR` |
| [`open.hex`](src/test/resources/golden/open.hex) | `OPEN`, body follows |
| [`open-end.hex`](src/test/resources/golden/open-end.hex) | `OPEN` with `END` |
| [`headers.hex`](src/test/resources/golden/headers.hex) | `HEADERS`, response head |
| [`headers-trailers-end.hex`](src/test/resources/golden/headers-trailers-end.hex) | `HEADERS` trailers with `END` |
| [`data.hex`](src/test/resources/golden/data.hex) | `DATA` |
| [`data-empty-end.hex`](src/test/resources/golden/data-empty-end.hex) | empty `DATA` with `END` |
| [`window-update.hex`](src/test/resources/golden/window-update.hex) | `WINDOW_UPDATE` |
| [`reset.hex`](src/test/resources/golden/reset.hex) | `RESET` |
