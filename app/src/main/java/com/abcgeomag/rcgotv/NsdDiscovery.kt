package com.abcgeomag.rcgotv

import android.content.Context
import android.os.Build
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.util.concurrent.Executor

data class TvDevice(val name: String, val host: String, val port: Int)

class NsdDiscovery(c: Context) {
    private val nsd = c.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val executor: Executor = Executor { command -> command.run() }

    private data class DeviceKey(val host: String, val port: Int)
    private data class ServiceKey(val type: String, val name: String)

    private class DiscoveryEntry(val listener: NsdManager.DiscoveryListener) {
        var stopRequested = false
    }

    private class ServiceEntry(val key: ServiceKey) {
        var infoCallback: NsdManager.ServiceInfoCallback? = null
        var unregisterRequested = false
    }

    fun scan(): Flow<TvDevice> = callbackFlow {
        val lock = Any()
        val discoveryEntries = mutableListOf<DiscoveryEntry>()
        val serviceEntries = mutableMapOf<ServiceKey, ServiceEntry>()
        val serviceDevices = mutableMapOf<ServiceKey, TvDevice>()
        val emittedDevices = mutableMapOf<DeviceKey, TvDevice>()
        var closed = false

        fun recompute(deviceKey: DeviceKey) {
            val updated = serviceDevices.values.firstOrNull {
                it.host == deviceKey.host && it.port == deviceKey.port
            }
            val previous = emittedDevices[deviceKey]
            if (updated == null) {
                emittedDevices.remove(deviceKey)
            } else if (updated != previous) {
                emittedDevices[deviceKey] = updated
                trySend(updated)
            }
        }

        fun deviceKey(device: TvDevice) = DeviceKey(device.host, device.port)

        fun updateService(entry: ServiceEntry, info: NsdServiceInfo) {
            val address = if (Build.VERSION.SDK_INT >= 34) {
                info.hostAddresses.filterIsInstance<Inet4Address>().firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                (info.host as? Inet4Address)
            }
            val host = address?.hostAddress ?: return

            synchronized(lock) {
                if (closed || serviceEntries[entry.key] !== entry) return
                val updated = TvDevice(info.serviceName, host, info.port)
                val previous = serviceDevices.put(entry.key, updated)
                if (previous != null && deviceKey(previous) != deviceKey(updated)) {
                    recompute(deviceKey(previous))
                }
                recompute(deviceKey(updated))
            }
        }

        fun removeService(entry: ServiceEntry, unregisterCallback: Boolean) {
            var callbackToUnregister: NsdManager.ServiceInfoCallback? = null
            synchronized(lock) {
                if (serviceEntries[entry.key] !== entry) return
                serviceEntries.remove(entry.key)
                val previous = serviceDevices.remove(entry.key)
                if (previous != null) recompute(deviceKey(previous))

                if (unregisterCallback && !entry.unregisterRequested) {
                    entry.unregisterRequested = true
                    callbackToUnregister = entry.infoCallback
                }
            }

            callbackToUnregister?.let {
                try {
                    nsd.unregisterServiceInfoCallback(it)
                } catch (_: Throwable) {
                    // The callback may have been unregistered by the system already.
                }
            }
        }

        fun stopDiscovery(entry: DiscoveryEntry) {
            val shouldStop = synchronized(lock) {
                if (entry.stopRequested) {
                    false
                } else {
                    entry.stopRequested = true
                    true
                }
            }
            if (shouldStop) {
                try {
                    nsd.stopServiceDiscovery(entry.listener)
                } catch (t: Throwable) {
                    close(t)
                }
            }
        }

        fun discover(type: String) {
            lateinit var entry: DiscoveryEntry
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    stopDiscovery(entry)
                    close(IllegalStateException("NSD discovery failed for $serviceType: $errorCode"))
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    close(IllegalStateException("Stopping NSD discovery failed for $serviceType: $errorCode"))
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val key = ServiceKey(type, serviceInfo.serviceName)
                    val serviceEntry = synchronized(lock) { serviceEntries[key] }
                    if (serviceEntry != null) removeService(serviceEntry, true)
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    val key = ServiceKey(type, serviceInfo.serviceName)
                    val serviceEntry = ServiceEntry(key)

                    synchronized(lock) {
                        if (closed || serviceEntries.containsKey(key)) return
                        serviceEntries[key] = serviceEntry

                        if (Build.VERSION.SDK_INT >= 34) {
                            val callback = object : NsdManager.ServiceInfoCallback {
                                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                                    updateService(serviceEntry, serviceInfo)
                                }

                                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                                    removeService(serviceEntry, false)
                                }

                                override fun onServiceInfoCallbackUnregistered() {
                                    removeService(serviceEntry, false)
                                }

                                override fun onServiceLost() {
                                    removeService(serviceEntry, true)
                                }
                            }
                            serviceEntry.infoCallback = callback
                            try {
                                nsd.registerServiceInfoCallback(serviceInfo, executor, callback)
                            } catch (t: Throwable) {
                                removeService(serviceEntry, true)
                                close(t)
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            try {
                                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                    override fun onResolveFailed(
                                        serviceInfo: NsdServiceInfo,
                                        errorCode: Int
                                    ) {
                                        removeService(serviceEntry, false)
                                    }

                                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                        updateService(serviceEntry, serviceInfo)
                                    }
                                })
                            } catch (t: Throwable) {
                                removeService(serviceEntry, false)
                                close(t)
                            }
                        }
                    }
                }
            }

            entry = DiscoveryEntry(listener)
            synchronized(lock) {
                if (closed) return
                discoveryEntries += entry
                try {
                    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                } catch (t: Throwable) {
                    stopDiscovery(entry)
                    close(t)
                }
            }
        }

        fun cleanup() {
            val listenersToStop = mutableListOf<DiscoveryEntry>()
            val callbacksToUnregister = mutableListOf<NsdManager.ServiceInfoCallback>()

            synchronized(lock) {
                if (closed) return
                closed = true

                discoveryEntries.forEach { entry ->
                    if (!entry.stopRequested) {
                        entry.stopRequested = true
                        listenersToStop += entry
                    }
                }

                serviceEntries.values.forEach { entry ->
                    val callback = entry.infoCallback
                    if (callback != null && !entry.unregisterRequested) {
                        entry.unregisterRequested = true
                        callbacksToUnregister += callback
                    }
                }

                discoveryEntries.clear()
                serviceEntries.clear()
                serviceDevices.clear()
                emittedDevices.clear()
            }

            listenersToStop.forEach {
                try {
                    nsd.stopServiceDiscovery(it.listener)
                } catch (_: Throwable) {
                    // Cleanup is best-effort if discovery already stopped or failed.
                }
            }

            callbacksToUnregister.forEach {
                try {
                    nsd.unregisterServiceInfoCallback(it)
                } catch (_: Throwable) {
                    // Cleanup is best-effort if the callback was already unregistered.
                }
            }
        }

        discover("_androidtvremote2._tcp.")
        discover("_androidtvremote._tcp.")

        awaitClose {
            cleanup()
        }
    }
}