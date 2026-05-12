package com.oxclient.core.relay

import android.util.Log
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption
import org.cloudburstmc.protocol.bedrock.BedrockPeer
import org.cloudburstmc.protocol.bedrock.PacketDirection
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer
import kotlin.random.Random

/**
 * ConnectionManager — OxRelay adına sunucuya RakNet bağlantısı kurar.
 * WRelay ConnectionManager'dan adapte edildi.
 */
class ConnectionManager(
    private val session: OxRelaySession
) {
    private val TAG = "ConnectionManager"

    private var isConnecting = false
    private var eventLoopGroup: NioEventLoopGroup? = null

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val MAX_RETRY             = 3
        private const val RETRY_DELAY_MS        = 2_000L
    }

    fun cleanup() {
        eventLoopGroup?.shutdownGracefully()
        eventLoopGroup = null
        isConnecting   = false
    }

    suspend fun connectToServer(
        host: String,
        port: Int,
        onConnected: OxRelaySession.ClientSession.() -> Unit
    ): Result<OxRelaySession.ClientSession> = withContext(Dispatchers.IO) {

        if (isConnecting) {
            return@withContext Result.failure(IllegalStateException("Zaten bağlanılıyor"))
        }
        isConnecting = true

        try {
            var lastEx: Exception? = null
            repeat(MAX_RETRY) { attempt ->
                try {
                    Log.i(TAG, "Bağlantı denemesi ${attempt + 1}/$MAX_RETRY → $host:$port")
                    val client = attemptConnection(host, port, onConnected)
                    Log.i(TAG, "Bağlantı başarılı: $host:$port")
                    return@withContext Result.success(client)
                } catch (e: Exception) {
                    lastEx = e
                    Log.w(TAG, "Deneme ${attempt + 1} başarısız: ${e.message}")
                    if (attempt < MAX_RETRY - 1) delay(RETRY_DELAY_MS)
                }
            }
            Result.failure(lastEx ?: Exception("$MAX_RETRY denemede bağlantı kurulamadı"))
        } finally {
            isConnecting = false
        }
    }

    private suspend fun attemptConnection(
        host: String,
        port: Int,
        onConnected: OxRelaySession.ClientSession.() -> Unit
    ): OxRelaySession.ClientSession = suspendCoroutine { cont ->

        if (eventLoopGroup == null || eventLoopGroup!!.isShuttingDown) {
            eventLoopGroup = NioEventLoopGroup()
        }

        val bootstrap = Bootstrap()
            .group(eventLoopGroup)
            .channelFactory(RakChannelFactory.client(NioDatagramChannel::class.java))
            .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
            .option(RakChannelOption.RAK_GUID, Random.nextLong())
            .option(RakChannelOption.RAK_CONNECT_TIMEOUT, CONNECTION_TIMEOUT_MS)
            .option(RakChannelOption.RAK_SESSION_TIMEOUT, 30_000L)
            .option(RakChannelOption.RAK_COMPATIBILITY_MODE, true)
            .option(RakChannelOption.RAK_MTU, 1400)
            .handler(object : BedrockChannelInitializer<OxRelaySession.ClientSession>() {

                override fun createSession0(peer: BedrockPeer, subClientId: Int): OxRelaySession.ClientSession =
                    session.ClientSession(peer, subClientId)

                override fun initSession(clientSession: OxRelaySession.ClientSession) {
                    session.client = clientSession
                    if (!cont.context.isActive) return
                    try {
                        cont.resume(clientSession)
                        onConnected(clientSession)
                    } catch (_: Exception) {}
                }

                override fun preInitChannel(channel: Channel) {
                    channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.SERVER_BOUND)
                    super.preInitChannel(channel)
                }
            })
            .remoteAddress(host, port)

        val future = bootstrap.connect()

        // Timeout job
        val timeoutJob = CoroutineScope(Dispatchers.IO).launch {
            delay(CONNECTION_TIMEOUT_MS + 5_000)
            if (!future.isDone) {
                future.cancel(true)
                try { cont.resumeWithException(Exception("Bağlantı zaman aşımı: $host:$port")) }
                catch (_: Exception) {}
            }
        }

        future.addListener { f ->
            timeoutJob.cancel()
            if (!f.isSuccess) {
                try {
                    cont.resumeWithException(
                        f.cause() ?: Exception("Bağlantı başarısız: $host:$port")
                    )
                } catch (_: Exception) {}
            }
        }
    }
}
