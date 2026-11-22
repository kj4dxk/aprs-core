// SPDX-License-Identifier: AGPL-3.0-only
package org.aprsfoundation.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow

interface AprsMessageTransport {
    val messages: Flow<AprsMessage>
    fun canSend(): Boolean
    suspend fun sendMessage(account: String, recipient: String, text: String, id: String)
    suspend fun acknowledge(account: String, recipient: String, id: String)
    suspend fun sendPending(account:String,message:PendingChat) = sendMessage(account,message.peer,message.body,message.wireId)
}

data class PendingChat(val row: Long, val peer: String, val body: String, val wireId: String,
                       val route:String="best", val requestAck:Boolean=true)

/** Implementations persist before returning. Account is always part of the storage key. */
interface ChatRepository {
    suspend fun receive(account: String, message: AprsMessage)
    suspend fun pending(account: String): List<PendingChat>
    suspend fun attempted(row: Long)
    suspend fun claim(row: Long): Boolean {
        attempted(row)
        return true
    }
    suspend fun transmitted(row: Long, success: Boolean)
}

/** Reliable processing lives outside the map/window lifecycle. Does not connect automatically. */
class ChatSession(
    private val account: String,
    private val client: AprsMessageTransport,
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
                    if (message.id != null && client.canSend())
                        client.acknowledge(account, message.sender, message.id)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error("Unable to save or acknowledge an incoming message") }
            }
        }
        scope.launch {
            while (isActive) {
                if (client.canSend()) {
                    try {
                        for (message in repository.pending(account)) {
                            if (!repository.claim(message.row)) continue
                            val sent = try {
                                client.sendPending(account,message)
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
