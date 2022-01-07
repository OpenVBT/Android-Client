package org.openbst.client

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.*
import android.util.Log
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import kotlinx.android.synthetic.main.activity_main.*
import org.jetbrains.anko.alert
import java.util.*


/* MAC address of the OpenVBT unit to connect to */
private const val OPENVBT_MAC_ADDRESS = "5C:60:D3:6C:82:1C"

/* UUID of the Client Characteristic Configuration Descriptor (0x2902). */
private const val CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805F9B34FB"

/* UUIDs of our service and characteristics */
private const val REP_STATISTICS_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
private const val REP_STATISTICS_CHAR_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

private const val GATT_MAX_MTU_SIZE = 517

private const val ENABLE_BLUETOOTH_REQUEST_CODE = 1
private const val LOCATION_PERMISSION_REQUEST_CODE = 2

class MainActivity : AppCompatActivity() {

    /*
     * Class Properties
     */

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private val isLocationPermissionGranted
        get() = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    private var isScanning = false
        set(value) {
            field = value
            runOnUiThread {
                if (value)
                    fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(
                        this,
                        R.color.fab_scanning
                    ))

                // When scanning begins, make progressbar visible
                if (value)
                    scan_progress_bar.visibility = ProgressBar.VISIBLE
            }
        }

    private var isConnectedAndReady = false
        set(value) {
            field = value
            runOnUiThread {
                // When scanning is over AND our connection is fully configured
                if (value) {
                    fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(
                        this,
                        R.color.fab_connected
                    ))
                    scan_progress_bar.visibility = ProgressBar.INVISIBLE
                } else {
                    fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(
                        this,
                        R.color.fab_not_connected
                    ))
                }
            }
        }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    private val scanResults = mutableListOf<ScanResult>()
    private val scanResultAdapter: ScanResultAdapter by lazy {
        ScanResultAdapter(scanResults) { result ->
            if (isScanning) stopBleScan()

            with (result.device) {
                Log.w("ScanResultAdapter", "Connecting to $address")
                connectGatt(this@MainActivity, false, gattCallback)
            }
        }
    }

    private var bluetoothGatt: BluetoothGatt? = null

    /*
     * Parent Override Functions
     */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fab.setOnClickListener {
            if (!isScanning)
                startBleScan()
            else
                stopBleScan()
        }

        setupRecyclerView()
    }

    override fun onResume() {
        super.onResume()

        if(!bluetoothAdapter.isEnabled) {
            promptEnableBluetooth();
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            ENABLE_BLUETOOTH_REQUEST_CODE -> {
                if (resultCode != Activity.RESULT_OK) {
                    promptEnableBluetooth()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when(requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_DENIED)
                    requestLocationPermission()
                else
                    startBleScan()
            }
        }
    }

    /*
     * Local Helper Functions
     */

    private fun setupRecyclerView() {
        scan_results_recycler_view.apply {
            adapter = scanResultAdapter
            layoutManager = LinearLayoutManager(
                this@MainActivity,
                RecyclerView.VERTICAL,
                false
            )
            isNestedScrollingEnabled = false
        }

        val animator = scan_results_recycler_view.itemAnimator
        if (animator is SimpleItemAnimator) {
            animator.supportsChangeAnimations = false
        }
    }

    private fun promptEnableBluetooth() {
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, ENABLE_BLUETOOTH_REQUEST_CODE)
        }
    }

    private fun requestLocationPermission() {
        if (isLocationPermissionGranted) return;

        runOnUiThread {
            alert {
                title = "Location permission required"
                message = "Starting from Android M (6.0), the system requires apps to be granted " +
                        "location access in order to scan for BLE devices."
                positiveButton(android.R.string.ok) {
                    requestPermission(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        LOCATION_PERMISSION_REQUEST_CODE
                    )
                }
            }.show()
        }
    }

    private fun startBleScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isLocationPermissionGranted) {
            requestLocationPermission()
        } else {
            // Clear old results
            scanResults.clear()
            scanResultAdapter.notifyDataSetChanged()

            // Find new results
            bleScanner.startScan(null, scanSettings, scanCallback)
            isScanning = true
        }
    }

    private fun stopBleScan() {
        bleScanner.stopScan(scanCallback)
        isScanning = false
    }

    private fun enableNotifications(characteristic: BluetoothGattCharacteristic) {
        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)

        val payload = when {
            characteristic.isIndicatable() -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            characteristic.isNotifiable() -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> {
                Log.e("ConnectionManager", "${characteristic.uuid} doesn't support notifications/indications")
                return
            }
        }

        characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
            if (bluetoothGatt?.setCharacteristicNotification(characteristic, true) == false) {
                Log.e("ConnectionManager", "setCharacteristicNotification failed for ${characteristic.uuid}")
                return
            }
            writeDescriptor(cccDescriptor, payload)
        }
    }

    private fun writeDescriptor(descriptor: BluetoothGattDescriptor, payload: ByteArray) {
        bluetoothGatt?.let { gatt ->
            descriptor.value = payload
            gatt.writeDescriptor(descriptor)
        } ?: error("Not connected to a BLE device!")
    }

    /*
     * Callback Functions
     */

    private val scanCallback = object: ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            with(result.device) {
                Log.d(
                    "ScanCallback",
                    "Found BLE device! Name: ${name ?: "Unnamed"}, address: $address"
                )
            }

            if (result.device.address == OPENVBT_MAC_ADDRESS) {
                if (isScanning) stopBleScan()

                Handler(Looper.getMainLooper()).postDelayed({
                    with (result.device) {
                        Log.w("ScanResultAdapter", "OpenVBT unit detected! Connecting to $address")
                        connectGatt(this@MainActivity, false, gattCallback)
                    }
                }, 1000)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("ScanCallback", "onScanFailed: code $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.w("BluetoothGattCallback", "Successfully connected to $deviceAddress")
                    bluetoothGatt = gatt

                    // Request max MTU in order to receive full data from device
                    gatt.requestMtu(GATT_MAX_MTU_SIZE)

                    // Delay is necessary to solve bug where callback isn't fired on first connection (min delay found to be 1000ms)
                    Handler(Looper.getMainLooper()).postDelayed({
                        bluetoothGatt?.discoverServices()
                    }, 1000)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.w("BluetoothGattCallback", "Successfully disconnected from $deviceAddress")
                    gatt.close()
                    bluetoothGatt = null
                    isConnectedAndReady = false
                }
            } else {
                Log.w("BluetoothGattCallback", "Error $status encountered for $deviceAddress! Disconnecting...")
                gatt.close()
                bluetoothGatt = null
                isConnectedAndReady = false
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.w(
                "BluetoothGattCallback",
                "ATT MTU changed to $mtu, success: ${status == BluetoothGatt.GATT_SUCCESS}",
            )
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            with (gatt) {
                Log.w("BluetoothGattCallback", "Discovered ${services.size} services for ${device.address}")
                printGattTable()
            }

            // Now that services are discovered, we can queue enabling notifications on the main thread
            // Note that this cannot happen immediately: onServicesDiscovered must return first
            Handler(Looper.getMainLooper()).post {
                // Enable notifications for our rep statistics (main function of OpenBST)
                val repStatsServUuid = UUID.fromString(REP_STATISTICS_SERVICE_UUID)
                val repStatsCharUuid = UUID.fromString(REP_STATISTICS_CHAR_UUID)
                val repStatsChar = gatt
                    .getService(repStatsServUuid).getCharacteristic(repStatsCharUuid)
                enableNotifications(repStatsChar)

                isConnectedAndReady = true
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic
        ) {
            with (characteristic) {
                // Split our received data into four values:
                //     max_vel, min_vel, max_accel, min_accel
                val params = String(value).split(" ")
                runOnUiThread {
                    max_velocity.text = params[0]
                    min_velocity.text = params[1]
                    max_accel.text = params[2]
                    min_accel.text = params[3]
                }

                Log.i("BluetoothGattCallback", "Characteristic $uuid changed | value: ${String(value)}")
            }
        }
    }

    /*
     * Extension Functions
     */

    // Permissions

    private fun Context.hasPermission(permissionType: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permissionType) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun Activity.requestPermission(permission: String, requestCode: Int) {
        ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
    }

    // Bluetooth

    private fun BluetoothGatt.printGattTable() {
        if (services.isEmpty()) {
            Log.i("printGattTable", "No service and characteristic available, call discoverServices() first?")
            return
        }
        services.forEach { service ->
            val characteristicsTable = service.characteristics.joinToString(
                separator = "\n|--",
                prefix = "|--"
            ) { it.uuid.toString() }
            Log.i("printGattTable", "\nService ${service.uuid}\nCharacteristics:\n$characteristicsTable"
            )
        }
    }

    private fun BluetoothGattCharacteristic.isIndicatable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_INDICATE)

    private fun BluetoothGattCharacteristic.isNotifiable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY)

    private fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean {
        return properties and property != 0
    }
}