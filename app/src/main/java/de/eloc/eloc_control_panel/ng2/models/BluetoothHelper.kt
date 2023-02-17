package de.eloc.eloc_control_panel.ng2.models

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import de.eloc.eloc_control_panel.ng2.App
import de.eloc.eloc_control_panel.ng2.interfaces.ElocCallback
import de.eloc.eloc_control_panel.ng2.interfaces.IntCallback
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class BluetoothHelper {

    private var scannerElapsed = 0
    private val bluetoothManager: BluetoothManager? =
        App.instance.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?

    val enablingIntent: Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
    val broadcastFilter: IntentFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
    private var executorHandle: ScheduledFuture<*>? = null

    val hasAdapter: Boolean
        get() {
            return bluetoothManager?.adapter != null
        }

    val isBluetoothOn: Boolean
        get() {
            return bluetoothManager?.adapter?.isEnabled ?: false
        }

    val isScanning: Boolean
        get() {
            return isScanningBluetooth || (executorHandle != null)
        }

    private val isScanningBluetooth: Boolean
        get() {
            var scanning = false
            val adapter = bluetoothManager?.adapter
            if (adapter != null) {
                if (ContextCompat.checkSelfPermission(
                        App.instance,
                        Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    scanning = adapter.isDiscovering
                }
            }
            return scanning
        }

    fun openSettings(activity: AppCompatActivity) {
        val intent = Intent()
        intent.action = Settings.ACTION_BLUETOOTH_SETTINGS
        activity.startActivity(intent)
    }

    protected fun finalize() {
        stopExecutor()
    }

    private fun stopExecutor() {
        if (executorHandle != null) {
            executorHandle?.cancel(true)
        }
        executorHandle = null
    }

    fun startScan(callback: IntCallback, handler: ElocCallback): String? {
        return if (isScanning)
            stopScan(callback)
        else
            startBluetoothScan(callback, handler)
    }

    private fun startBluetoothScan(callback: IntCallback, handler: ElocCallback): String? {
        val adapter = bluetoothManager?.adapter
        if (adapter != null) {
            if (ContextCompat.checkSelfPermission(
                    App.instance,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    return "Set bluetooth permissions in app settings!"
                }
            }
            scannerElapsed = 0
            executorHandle =
                Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
                    {
                        if (scannerElapsed >= SCAN_DURATION) {
                            stopScan(callback)
                        }
                        scannerElapsed++
                        callback.handler(SCAN_DURATION - scannerElapsed)
                        Log.d("TAG", "startBluetoothScan: " + scannerElapsed)
                    },
                    0,
                    1,
                    TimeUnit.SECONDS
                )

            for (device in adapter.bondedDevices) {
                handler.handler(ElocInfo(device))
            }
            adapter.cancelDiscovery()
            adapter.startDiscovery()
        }
        return null
    }

     fun stopScan(callback: IntCallback): String? {
        if (ContextCompat.checkSelfPermission(
                App.instance,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return "Set bluetooth permissions in app settings!"
            }
        }
        bluetoothManager?.adapter?.cancelDiscovery()
        stopExecutor()
        callback.handler(-1)
        return null
    }

    companion object {
        private var cInstance: BluetoothHelper? = null
        private const val SCAN_DURATION = 30 // Seconds

        val instance: BluetoothHelper
            get() {
                if (cInstance == null) {
                    cInstance = BluetoothHelper()
                }
                return cInstance!!
            }
    }
}