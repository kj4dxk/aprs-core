package net.osmand.shared.aprs

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

data class AprsIsConfig(val callsign: String, val passcode: String = "-1",
                        val host: String = "rotate.aprs2.net", val port: Int = 14580,
                        val latitude: Double, val longitude: Double,
                        val radiusKm: Int = 100, val beaconEnabled: Boolean = false,
                        val beaconIntervalMillis: Long = 120_000,
                        val application: String = "APRSISOSM", val filterMode: String = "range", val customFilter: String = "",
                        val fallbackServers: List<AprsEndpoint> = emptyList()) {
    init {
        AprsProtocol.login(callsign, passcode, AprsFilters.compose(callsign, latitude, longitude, radiusKm, filterMode, customFilter), application)
        require(host.isNotBlank() && host.length <= 253 && host.all { it.isLetterOrDigit() || it in ".-:" })
        require(port in 1..65535)
        require(fallbackServers.size<=16)
        fallbackServers.forEach {AprsEndpoint.parse(if(':' in it.host) "[${it.host}]:${it.port}" else "${it.host}:${it.port}")}
        require(!beaconEnabled || passcode != "-1")
        require(beaconIntervalMillis >= 60_000)
    }
    // Credentials must never appear in logs or diagnostic UI.
    override fun toString() = "AprsIsConfig(callsign=$callsign, host=$host, port=$port)"
}

enum class AprsConnectionState { STOPPED, CONNECTING, RECEIVING, VERIFIED, RECONNECTING }

/** APRS-IS transport shared by Android and future iOS clients. No connection until start(). */
class AprsIsClient : AprsMessageTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(AprsConnectionState.STOPPED)
    val state: StateFlow<AprsConnectionState> = mutableState
    private val mutableServerMessages=MutableStateFlow<List<String>>(emptyList())
    val serverMessages:StateFlow<List<String>> = mutableServerMessages
    private fun serverMessage(line:String) {
        val clean=line.take(512).replace(Regex("(?i)(\\bpass(?:code)?\\s+)[^\\s]+"),"$1[redacted]")
        mutableServerMessages.value=(mutableServerMessages.value+clean).takeLast(64)
    }
    val activeEndpoint = MutableStateFlow<AprsEndpoint?>(null)
    private val mutableStations = MutableStateFlow<List<AprsStation>>(emptyList())
    val stations: StateFlow<List<AprsStation>> = mutableStations
    private val fix = MutableStateFlow<AprsFix?>(null)
    private var job: Job? = null
    private val incoming = Channel<AprsMessage>(64)
    override val messages = incoming.receiveAsFlow()
    override fun canSend() = state.value==AprsConnectionState.VERIFIED
    private val rawIncoming=MutableSharedFlow<String>(extraBufferCapacity=128)
    val packets=rawIncoming.asSharedFlow()
    private class Sender(val callsign: String, val write: suspend (String) -> Unit)
    private val sender = MutableStateFlow<Sender?>(null)

    override suspend fun sendMessage(account: String, recipient: String, text: String, id: String) {
        val active = checkNotNull(sender.value) { "Not connected" }
        check(active.callsign == account)
        active.write(AprsMessages.packet(account, recipient, text, id))
    }

    override suspend fun acknowledge(account: String, recipient: String, id: String) {
        val active = checkNotNull(sender.value) { "Not connected" }
        check(active.callsign == account)
        active.write(AprsMessages.ack(account, recipient, id))
    }

    fun updateFix(value: AprsFix) { fix.value = value }

    /** Send a formatted report on the current verified connection, never a saved offline beacon. */
    suspend fun sendBroadcast(account: String, packet: String) {
        require(packet.endsWith("\r\n") && packet.length <= 512)
        val line=packet.dropLast(2)
        require(AprsProtocol.validCallsign(line.substringBefore('>')))
        require(line.none {it=='\r' || it=='\n' || it.code !in 1..127})
        val envelope=line.substringAfter('>').substringBefore(':')
        require(envelope.endsWith(",TCPIP*"))
        val destination=envelope.removeSuffix(",TCPIP*")
        val payload=line.substringAfter(':')
        if(payload.firstOrNull() in listOf('`','\'')) require(MicE.decode(line.substringBefore('>'),destination,payload,0)!=null)
        else require(destination=="APRS" && line.all {it.code in 32..126})
        val active=checkNotNull(sender.value) { "Not connected" }
        check(active.callsign == account)
        active.write(packet)
    }

    /** The caller supplies a policy-approved RF packet with this IGate's q construct. */
    suspend fun sendGated(account: String, line: String) {
        val packet=requireNotNull(RadioPacket.parse(line))
        require(packet.path.takeLast(2) in listOf(listOf("qAR",account),listOf("qAO",account)))
        require(packet.path.dropLast(2).none {it.startsWith('q') || it.trimEnd('*') in listOf("TCPIP","TCPXX","NOGATE","RFONLY")})
        require(!packet.payload.startsWith('}') && !packet.payload.startsWith('?'))
        val active=checkNotNull(sender.value);check(active.callsign==account)
        active.write(line+"\r\n")
    }

    fun start(config: AprsIsConfig) {
        stop()
        job = scope.launch {
            val store = AprsStationStore()
            val policy = AprsBeaconPolicy(config.beaconIntervalMillis)
            var backoff = 2_000L
            val endpoints=(listOf(AprsEndpoint(config.host,config.port))+config.fallbackServers).distinct()
            var attempt=0
            while (isActive) {
                val endpoint=endpoints[attempt%endpoints.size]
                activeEndpoint.value=endpoint
                mutableState.value = AprsConnectionState.CONNECTING
                try {
                    session(config.copy(host=endpoint.host,port=endpoint.port), store, policy)
                } catch (_: TimeoutCancellationException) {
                    ensureActive()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Do not expose a login line, passcode, or raw exception to logs.
                }
                ensureActive()
                mutableState.value = AprsConnectionState.RECONNECTING
                attempt++
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000)
            }
        }
    }

    fun stop() {
        sender.value = null
        job?.cancel()
        job = null
        fix.value = null
        mutableState.value = AprsConnectionState.STOPPED
        activeEndpoint.value = null
    }

    private suspend fun session(config: AprsIsConfig, store: AprsStationStore, policy: AprsBeaconPolicy) = coroutineScope {
        val selector = SelectorManager(Dispatchers.Default)
        try {
            val socket = withTimeout(20_000) {
                aSocket(selector).tcp().connect(config.host, config.port) { noDelay = true }
            }
            try {
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel(autoFlush = true)
                val writeLock = Mutex()
                val banner = withTimeout(20_000) { input.readUTF8Line(512) }
                check(banner?.startsWith("#") == true)
                serverMessage(banner!!)
                output.writeStringUtf8(AprsProtocol.login(config.callsign, config.passcode,
                    AprsFilters.compose(config.callsign, config.latitude, config.longitude, config.radiusKm, config.filterMode, config.customFilter),
                    config.application))
                mutableState.value = AprsConnectionState.RECEIVING
                val verified = MutableStateFlow(false)
                val sessionSender = Sender(config.callsign) { packet ->
                    writeLock.withLock {
                        check(verified.value && isActive) { "Verified login required" }
                        output.writeStringUtf8(packet)
                    }
                }
                sender.value = sessionSender
                val storeLock = Mutex()
                val ticker = launch {
                    while (isActive) {
                        delay(1000)
                        val now = Clock.System.now().toEpochMilliseconds()
                        mutableStations.value = storeLock.withLock { store.snapshot(now) }
                        val currentFix = fix.value
                        if (policy.due(currentFix, now, config.beaconEnabled, verified.value)) {
                            writeLock.withLock {
                                output.writeStringUtf8(AprsProtocol.beacon(config.callsign, currentFix!!, config.application))
                            }
                            policy.sent(now)
                        }
                    }
                }
                try {
                    while (isActive) {
                        val line = withTimeout(120_000) { input.readUTF8Line(512) } ?: break
                        if (line.startsWith("#")) {
                            serverMessage(line)
                            if (line.contains("logresp", ignoreCase = true)) {
                                verified.value = AprsProtocol.verifiedLogin(line, config.callsign)
                                mutableState.value = if (verified.value) AprsConnectionState.VERIFIED else AprsConnectionState.RECEIVING
                            }
                        } else {
                            rawIncoming.emit(line)
                            AprsMessages.parse(line)?.takeIf { it.recipient == config.callsign && it.sender != config.callsign }
                                ?.let { incoming.send(it) }
                            storeLock.withLock { store.receive(line, Clock.System.now().toEpochMilliseconds()) }
                        }
                    }
                } finally {
                    if (sender.value === sessionSender) sender.value = null
                    ticker.cancelAndJoin()
                }
            } finally { socket.close() }
        } finally { selector.close() }
    }
}
