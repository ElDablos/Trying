package com.eldablos.homewidget

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Finds the bulb and heater on the phone's Wi-Fi network. */
object LanScanner {

    /** The phone's Wi-Fi IPv4 address, or null if not on Wi-Fi. */
    fun wifiAddress(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        return cm.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    /** Every other address in the phone's /24 (e.g. 192.168.1.1–254). */
    fun neighbours(ownAddress: String): List<String> {
        val prefix = ownAddress.substringBeforeLast('.')
        return (1..254).map { "$prefix.$it" }.filter { it != ownAddress }
    }

    fun findHeaters(hosts: List<String>): List<String> = PhilipsClient.scan(hosts)

    fun findTapoDevices(hosts: List<String>): List<String> {
        val pool = Executors.newFixedThreadPool(48)
        try {
            val results = pool.invokeAll(hosts.map { host -> Callable { host.takeIf { TapoClient.looksLikeTapo(it) } } })
            return results.mapNotNull { runCatching { it.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }
}
