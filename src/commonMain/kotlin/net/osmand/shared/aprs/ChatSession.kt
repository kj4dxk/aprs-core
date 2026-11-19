// SPDX-License-Identifier: AGPL-3.0-only
package net.osmand.shared.aprs

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

data class PendingChat(val row: Long, val peer: String, val body: String, val wireId: String)

/** Implementations persist before returning. Account is always part of the storage key. */
interface ChatRepository {
    suspend fun receive(account: String, message: AprsMessage)
    suspend fun pending(account: String): List<PendingChat>
    suspend fun attempted(row: Long)
    suspend fun transmitted(row: Long, success: Boolean)
}

/** Reliable processing lives outside the map/window lifecycle. Does not connect automatically. */
class ChatSession(
    private val account: String,
    private val client: AprsIsClient,
    private val repository: ChatRepository,
    parent: CoroutineScope,
    private val error: (String) -> Unit = {},
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    init {
        scope.launch {
            client.messages.collect { message ->
                try {
                    repository.receive(account, message)
                    if (message.id != null) client.acknowledge(account, message.sender, message.id)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error("Unable to save or acknowledge an incoming message") }
            }
        }
        scope.launch {
            while (isActive) {
                if (client.state.value == AprsConnectionState.VERIFIED) {
                    try {
                        for (message in repository.pending(account)) {
                            repository.attempted(message.row)
                            val sent = try {
                                client.sendMessage(account, message.peer, message.body, message.wireId)
                                true
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { false }
                            repository.transmitted(message.row, sent)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error("Unable to process the saved outbox") }
                }
                delay(1000)
            }
        }
    }
    fun close() { job.cancel() }
}
