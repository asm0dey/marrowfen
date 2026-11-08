package tun.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** The outcome of [Registry.claim]. */
sealed interface ClaimResult {
    data class Claimed(val name: String) : ClaimResult
    data object Rejected : ClaimResult
}

/** A live session: the token [alias] it came in on, the [job] whose completion frees its name, and its [tunnel]. */
data class Live<T>(val alias: String, val job: Job, val tunnel: T)

/** An immutable view of the live sessions, by name. Routing reads this; it never changes in place. */
data class Snapshot<T>(val sessions: Map<String, Live<T>>)

/**
 * The single owner of tokens and live sessions (AD-4). Every change is a command handled in turn by one coroutine,
 * which publishes a new [snapshot] after each change. [T] is the handle a request is routed to, such as a mux session.
 */
class Registry<T> internal constructor(
    internal val commands: Channel<Command<T>>,
    val snapshot: StateFlow<Snapshot<T>>,
) {
    /**
     * Claims a fresh random name for [tunnel] if [token] is configured; the name is freed when [job] completes.
     * The token is hashed here, so its text never enters the owner's queue. [job] must end when the caller's session
     * does (e.g. be the caller's own job), or the name stays held, even if the caller is cancelled while claiming.
     */
    suspend fun claim(token: String, job: Job, tunnel: T): ClaimResult {
        val reply = CompletableDeferred<ClaimResult>()
        commands.send(Command.Claim(sha256Hex(token), job, tunnel, reply))
        return reply.await()
    }
}

internal sealed interface Command<T> {
    class Claim<T>(val hash: String, val job: Job, val tunnel: T, val reply: CompletableDeferred<ClaimResult>) :
        Command<T>

    /** Sent by the owner's completion hook; [job] keeps a stale one from freeing a name a later session now holds. */
    class Disconnected<T>(val name: String, val job: Job) : Command<T>
}

/** Starts the registry owner in this scope. It runs until the scope is cancelled. */
fun <T> CoroutineScope.launchRegistry(tokens: List<Token>, names: () -> String = ::randomName): Registry<T> {
    // a claim still queued when the owner stops fails instead of waiting forever
    val commands = Channel<Command<T>>(Channel.UNLIMITED) { (it as? Command.Claim)?.reply?.cancel() }
    val owner = Owner(tokens.associateBy { it.hash }, names, commands)
    launch {
        try {
            for (command in commands) owner.handle(command)
        } finally {
            commands.cancel()
        }
    }
    return Registry(commands, owner.snapshot)
}

private class Owner<T>(
    private val byHash: Map<String, Token>,
    private val names: () -> String,
    private val commands: Channel<Command<T>>,
) {
    val snapshot = MutableStateFlow(Snapshot<T>(emptyMap()))
    private val sessions get() = snapshot.value.sessions

    fun handle(command: Command<T>) {
        when (command) {
            is Command.Claim -> claim(command)
            is Command.Disconnected -> if (sessions[command.name]?.job === command.job) publish(sessions - command.name)
        }
    }

    private fun claim(command: Command.Claim<T>) {
        val token = byHash[command.hash]
        if (token == null) {
            command.reply.complete(ClaimResult.Rejected)
            return
        }
        val name = generateSequence { names() }.first { it !in sessions }
        publish(sessions + (name to Live(token.alias, command.job, command.tunnel)))
        command.job.invokeOnCompletion { commands.trySend(Command.Disconnected(name, command.job)) }
        command.reply.complete(ClaimResult.Claimed(name))
    }

    private fun publish(next: Map<String, Live<T>>) {
        snapshot.value = Snapshot(next)
    }
}

private const val NAME_LENGTH = 8
private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567" // lowercase base32
private val random = SecureRandom()

/** A random 8-char lowercase base32 name. */
fun randomName(): String = String(CharArray(NAME_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })
