package br.com.st.totem.payment.sitef

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * PinPadStateMonitor — Etapa 1
 * Observa eventos de conexão/desconexão USB do pinpad.
 * Só loga por enquanto — nenhuma ação ainda.
 */
class PinPadStateMonitor(
    private val context: Context,
    private val onPinPadConnected: (UsbDevice) -> Unit = {},
    private val onPinPadDisconnected: (UsbDevice) -> Unit = {}
) {
    companion object {
        private const val TAG = "PINPAD_MONITOR"

        // Vendor IDs conhecidos de pinpads Gertec
        private val KNOWN_PINPAD_VENDORS = setOf(
            5971,  // Gertec PPC-930 (0x1753)
            1317,  // Gertec alternativo (0x0525)
        )
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB CONECTADO | device=${device?.deviceName} vendor=${device?.vendorId} product=${device?.productId} name=${device?.productName}")
                    if (device != null) {
                        if (isPinPad(device)) {
                            Log.i(TAG, "✅ PINPAD CONECTADO | vendor=${device.vendorId} device=${device.deviceName}")
                            onPinPadConnected(device)
                        } else {
                            Log.d(TAG, "Device USB conectado não é pinpad conhecido — ignorando")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB DESCONECTADO | device=${device?.deviceName} vendor=${device?.vendorId} product=${device?.productId}")
                    if (device != null) {
                        if (isPinPad(device)) {
                            Log.w(TAG, "⚠️ PINPAD DESCONECTADO | vendor=${device.vendorId} device=${device.deviceName}")
                            onPinPadDisconnected(device)
                        }
                    }
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        Log.i(TAG, "PinPadStateMonitor iniciado")
        logCurrentDevices()
    }

    fun stop() {
        try {
            context.unregisterReceiver(receiver)
            Log.i(TAG, "PinPadStateMonitor parado")
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao parar monitor: ${e.message}")
        }
    }

    // Lista os devices USB conectados no momento da inicialização
    private fun logCurrentDevices() {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usbManager.deviceList
        if (devices.isEmpty()) {
            Log.d(TAG, "Nenhum device USB conectado no momento")
            return
        }
        Log.i(TAG, "Devices USB conectados ao iniciar (${devices.size}):")
        devices.values.forEach { device ->
            val hasPerm = usbManager.hasPermission(device)
            val isPinpad = isPinPad(device)
            Log.i(TAG, "  → device=${device.deviceName} vendor=${device.vendorId} product=${device.productId} isPinPad=$isPinpad hasPerm=$hasPerm")
        }
    }

    private fun isPinPad(device: UsbDevice): Boolean {
        return device.vendorId in KNOWN_PINPAD_VENDORS
    }
}