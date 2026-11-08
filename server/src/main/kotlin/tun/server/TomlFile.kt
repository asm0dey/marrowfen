package tun.server

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.exceptions.TomlDecodingException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration

/** `tun.toml` as written, before validation. Nullable fields are the ones with defaults. */
@Serializable
internal class TomlFile(val version: Int, val server: TomlServer, val token: List<TomlToken> = emptyList())

@Serializable
internal class TomlServer(
    val domain: String,
    val bind: String? = null,
    val port: Int? = null,
    @Serializable(DurationText::class) val grace: Duration? = null,
    @SerialName("ping_interval") @Serializable(DurationText::class) val pingInterval: Duration? = null,
)

@Serializable
internal class TomlToken(val alias: String, val hash: String, val level: String, val created: String? = null)

/** A positive duration written as text, such as `"60s"` or `"1m"`. */
internal object DurationText : KSerializer<Duration> {
    override val descriptor = PrimitiveSerialDescriptor("tun.DurationText", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Duration {
        val text = decoder.decodeString()
        val duration = Duration.parseOrNull(text)
        if (duration == null || !duration.isPositive() || !duration.isFinite()) throw BadDuration(text)
        return duration
    }

    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeString(value.toString())
}

private class BadDuration(val text: String) : SerializationException("bad duration")

private val toml = Toml(TomlInputConfig(ignoreUnknownNames = true))
private val MISSING = Regex("property <(\\w+)> from class <tun\\.server\\.Toml(\\w+)>")
private val TABLES = mapOf("Server" to "[server] ", "Token" to "[[token]] ")

/** Decodes [text]; any TOML or shape error becomes a [ConfigException] naming the key or line and the fix. */
internal fun decodeToml(text: String, where: String): TomlFile = try {
    toml.decodeFromString(TomlFile.serializer(), text)
} catch (e: SerializationException) {
    throw ConfigException("$where: ${problem(e, text)}", e)
}

private fun problem(e: SerializationException, text: String): String {
    val missing = MISSING.find(e.message.orEmpty()) // ktoml's MissingRequiredPropertyException is internal
    return when {
        missing != null -> missing.destructured
            .let { (key, table) -> "${TABLES[table].orEmpty()}$key is missing; add it (see the README)" }
        // shortcut: ktoml does not say where a custom serializer failed, so the line is found by its value
        e is BadDuration ->
            where(text, e.text) + "\"${e.text}\" is not a positive duration; write it like \"60s\" or \"1m\""
        e is TomlDecodingException -> "${e.message}; fix that line"
        else -> e.message.orEmpty()
    }
}

/** `Line N: ` for the first line holding the quoted [value], or nothing when it is not found (e.g. a 'literal'). */
private fun where(text: String, value: String): String =
    text.lines().indexOfFirst { "\"$value\"" in it }.let { if (it < 0) "" else "Line ${it + 1}: " }
