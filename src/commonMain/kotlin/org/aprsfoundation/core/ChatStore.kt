// SPDX-License-Identifier: AGPL-3.0-only
package org.aprsfoundation.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** SQLite I/O supplied by the host. Queries consume rows before closing the cursor. */
interface ChatSql {
    fun execute(sql: String, vararg values: Any?): Int
    fun <T> query(sql: String, values: List<Any?>, read: (ChatSqlRow) -> T): List<T>
    fun <T> transaction(block: () -> T): T
}

interface ChatSqlRow {
    fun text(index: Int): String
    fun number(index: Int): Long
}

data class ChatRow(val id: Long, val peer: String, val body: String, val outgoing: Boolean,
                   val time: Long, val status: String, val unread: Boolean, val attempts: Int)
data class ChatOptions(val route: String = "best", val requestAck: Boolean = true)

/** An existing message imported from a platform's previous chat store. */
data class ImportedChat(
    val account: String, val peer: String, val body: String, val outgoing: Boolean,
    val wireId: String?, val time: Long?, val status: String, val attempts: Int = 0,
    val lastSend: Long = 0
)

/** Shared SQLite schema, delivery policy, and storage transitions for ChatSession. */
open class ChatStore(
    private val sql: ChatSql,
    private val now: () -> Long,
    private val expireAfterMillis: Long? = 900_000
) : ChatRepository {
    private val mutex = Mutex()
    private val changes = MutableStateFlow(0L)
    val revision: StateFlow<Long> = changes
    private val active = "('QUEUED','SENDING','WAITING')"

    init {
        sql.transaction {
            sql.execute("CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY, account TEXT NOT NULL, peer TEXT NOT NULL, body TEXT NOT NULL, outgoing INTEGER NOT NULL, wire_id TEXT, time INTEGER NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, last_send INTEGER NOT NULL DEFAULT 0, unread INTEGER NOT NULL DEFAULT 0)")
            sql.execute("CREATE INDEX IF NOT EXISTS conversation ON messages(account,peer,time,id)")
            sql.execute("CREATE INDEX IF NOT EXISTS outbox ON messages(account,outgoing,status)")
            val columns = sql.query("PRAGMA table_info(messages)", emptyList()) { it.text(1) }
            if ("route" !in columns) sql.execute("ALTER TABLE messages ADD COLUMN route TEXT NOT NULL DEFAULT 'best'")
            if ("request_ack" !in columns) sql.execute("ALTER TABLE messages ADD COLUMN request_ack INTEGER NOT NULL DEFAULT 1")
            if ("retry_at" !in columns) sql.execute("ALTER TABLE messages ADD COLUMN retry_at INTEGER NOT NULL DEFAULT 0")
            sql.execute("CREATE TABLE IF NOT EXISTS conversations(account TEXT NOT NULL,peer TEXT NOT NULL,route TEXT NOT NULL,request_ack INTEGER NOT NULL,PRIMARY KEY(account,peer))")
            sql.execute("CREATE TABLE IF NOT EXISTS chat_imports(name TEXT PRIMARY KEY)")
            sql.execute("PRAGMA user_version=3")
        }
    }

    private suspend fun <T> access(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    protected suspend fun closeStorage(close: () -> Unit) = access(close)
    private fun changed() { changes.value++ }
    private fun has(sqlText: String, vararg values: Any?): Boolean =
        sql.query(sqlText, values.toList()) { true }.isNotEmpty()

    private fun optionsNow(account: String, peer: String): ChatOptions =
        sql.query("SELECT route,request_ack FROM conversations WHERE account=? AND peer=?", listOf(account, peer)) {
            ChatOptions(it.text(0), it.number(1) == 1L)
        }.singleOrNull() ?: ChatOptions()

    suspend fun options(account: String, peer: String) = access { optionsNow(account, peer) }

    suspend fun saveOptions(account: String, peer: String, options: ChatOptions) = access {
        require(options.route in listOf("best", "rf", "is"))
        sql.execute("INSERT OR REPLACE INTO conversations(account,peer,route,request_ack) VALUES(?,?,?,?)",
            account, peer, options.route, if (options.requestAck) 1 else 0)
        changed()
    }

    suspend fun enqueue(account: String, peer: String, text: String) = enqueueParts(account, peer, AprsMessages.split(text))

    suspend fun enqueueParts(account: String, peer: String, parts: List<String>) = access {
        require(AprsProtocol.validCallsign(account) && AprsProtocol.validCallsign(peer) && account != peer)
        require(parts.isNotEmpty() && parts.size <= 40 && parts.all(AprsMessages::validText))
        val options = optionsNow(account, peer)
        val time = now()
        sql.transaction {
            for (part in parts) {
                var wire: String
                do {
                    wire = Random.nextInt(0x100000).toString(16).padStart(5, '0').uppercase()
                } while (has("SELECT 1 FROM messages WHERE account=? AND peer=? AND outgoing=1 AND wire_id=? AND time>? LIMIT 1",
                        account, peer, wire, time - 86_400_000))
                sql.execute("INSERT INTO messages(account,peer,body,outgoing,wire_id,time,status,route,request_ack) VALUES(?,?,?,1,?,?,'QUEUED',?,?)",
                    account, peer, part, wire, time, options.route, if (options.requestAck) 1 else 0)
            }
        }
        changed()
    }

    suspend fun importOnce(source: String, rows: () -> List<ImportedChat>) = access {
        val imported = sql.transaction {
            if (has("SELECT 1 FROM chat_imports WHERE name=?", source)) return@transaction false
            for (row in rows()) {
                sql.execute("INSERT INTO messages(account,peer,body,outgoing,wire_id,time,status,attempts,last_send) VALUES(?,?,?,?,?,?,?,?,?)",
                    row.account, row.peer, row.body, if (row.outgoing) 1 else 0, row.wireId,
                    row.time ?: now(), row.status, row.attempts, row.lastSend)
            }
            sql.execute("INSERT INTO chat_imports(name) VALUES(?)", source)
            true
        }
        if (imported) changed()
    }

    override suspend fun receive(account: String, message: AprsMessage) = access {
        require(message.recipient == account)
        val time = now()
        sql.transaction {
            for (id in listOfNotNull(message.acknowledgement, message.replyAck)) {
                val status = if (message.rejected && id == message.acknowledgement) "REJECTED" else "ACKNOWLEDGED"
                sql.execute("UPDATE messages SET status=? WHERE account=? AND peer=? AND outgoing=1 AND wire_id=? AND time>?",
                    status, account, message.sender, id, time - 86_400_000)
            }
            if (message.acknowledgement == null) {
                val duplicate = message.id != null && has("SELECT 1 FROM messages WHERE account=? AND peer=? AND outgoing=0 AND wire_id=? AND body=? AND time>? LIMIT 1",
                    account, message.sender, message.id, message.text, time - 86_400_000)
                if (!duplicate) sql.execute("INSERT INTO messages(account,peer,body,outgoing,wire_id,time,status,unread) VALUES(?,?,?,0,?,?,'RECEIVED',1)",
                    account, message.sender, message.text, message.id, time)
            }
        }
        changed()
    }

    override suspend fun pending(account: String): List<PendingChat> = access {
        val time = now()
        val cutoff = expireAfterMillis?.let { time - it } ?: Long.MIN_VALUE
        val expired = sql.execute("UPDATE messages SET status='NO_ACK' WHERE account=? AND outgoing=1 AND status IN $active AND (MAX(time,retry_at)<? OR (attempts>=5 AND last_send<?))",
            account, cutoff, time - 30_000)
        if (expired > 0) changed()
        sql.query("SELECT id,peer,body,wire_id,route,request_ack FROM messages WHERE account=? AND outgoing=1 AND status IN $active AND attempts<5 AND last_send<=? ORDER BY last_send,id LIMIT 1",
            listOf(account, time - 30_000)) {
            PendingChat(it.number(0), it.text(1), it.text(2), it.text(3), it.text(4), it.number(5) == 1L)
        }
    }

    override suspend fun attempted(row: Long) { claim(row) }

    override suspend fun claim(row: Long): Boolean = access {
        val time = now()
        val claimed = sql.execute("UPDATE messages SET status='SENDING',attempts=attempts+1,last_send=? WHERE id=? AND status IN $active AND attempts<5 AND last_send<=?",
            time, row, time - 30_000) > 0
        if (claimed) changed()
        claimed
    }

    override suspend fun transmitted(row: Long, success: Boolean) = access {
        if (sql.execute("UPDATE messages SET status=CASE WHEN request_ack=0 AND ?=1 THEN 'SENT' ELSE ? END WHERE id=? AND status='SENDING'",
            if (success) 1 else 0, if (success) "WAITING" else "QUEUED", row) > 0) changed()
    }

    suspend fun rows(account: String, peer: String? = null): List<ChatRow> = access {
        val peerFilter = if (peer == null) "" else " AND peer=?"
        sql.query("SELECT id,peer,body,outgoing,time,status,unread,attempts FROM messages WHERE account=?$peerFilter ORDER BY time,id",
            listOfNotNull(account, peer)) {
            ChatRow(it.number(0), it.text(1), it.text(2), it.number(3) == 1L, it.number(4),
                it.text(5), it.number(6) == 1L, it.number(7).toInt())
        }
    }

    suspend fun markRead(account: String, peer: String) = access {
        if (sql.execute("UPDATE messages SET unread=0 WHERE account=? AND peer=? AND unread=1", account, peer) > 0) changed()
    }

    suspend fun requeuePending(account: String, peer: String): Int = access {
        val count = sql.execute("UPDATE messages SET status='QUEUED',attempts=0,last_send=0,retry_at=? WHERE account=? AND peer=? AND outgoing=1 AND request_ack=1 AND status IN ('QUEUED','SENDING','WAITING','NO_ACK')",
            now(), account, peer)
        if (count > 0) changed()
        count
    }

    suspend fun retry(account: String, row: Long) {
        val message = rows(account).firstOrNull { it.id == row && it.outgoing && it.status in listOf("NO_ACK", "REJECTED") } ?: return
        enqueue(account, message.peer, message.body)
    }
}
