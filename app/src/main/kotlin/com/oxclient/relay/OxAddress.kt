package com.oxclient.relay

import java.net.InetSocketAddress

data class OxAddress(val host: String, val port: Int) {
    fun toInetSocketAddress(): InetSocketAddress = InetSocketAddress(host, port)
    override fun toString(): String = "$host:$port"
}
