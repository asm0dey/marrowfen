package tun.core

import java.security.MessageDigest

/** What a token may do: take a random name, or also ask for a name of its own (Epic 3). */
enum class Level { RANDOM, NAMED }

/** A configured token. [hash] is the SHA-256 of the token text as 64 lowercase hex chars; the text is never kept. */
data class Token(val alias: String, val hash: String, val level: Level)

/** SHA-256 of [text]'s UTF-8 bytes as lowercase hex: how a presented token is matched against [Token.hash]. */
fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).toHexString()
