package org.openbst.client

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.DatePicker
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import kotlinx.android.synthetic.main.activity_main.*
import org.jetbrains.anko.alert
import java.text.SimpleDateFormat
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

    private val databaseHandler: DatabaseHandler by lazy {
        DatabaseHandler(this)
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bleScanner by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }

    private val isLocationPermissionGranted
        get() = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    private var isScanning = false
        set(value) {
            field = value
            runOnUiThread {
                if (value) {
                    fab.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(
                            this,
                            R.color.fab_scanning
                        )
                    )
                    scan_progress_bar.visibility = ProgressBar.VISIBLE
                }
            }
        }

    // Used for automatically reconnecting: represents whether the user wants to be connected
    private var connectionDesired = false
        set(value) {
            field = value
            // Handle connecting/disconnecting to/from our device
            if (value)
                startConnection()
            else
                stopConnection()
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

    private val repetitions = mutableListOf<RepData>()
    private val repetitionAdapter: RepDataAdapter by lazy {
        RepDataAdapter(repetitions, ::promptDeleteRepetition)
    }

    private var bluetoothGatt: BluetoothGatt? = null

    /*
     * Parent Override Functions
     */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        fab.setOnClickListener {
            // Toggle our connectDesired var: this var AUTOMATICALLY HANDLES CONNECT/DISCONNECT functionality
            if (!connectionDesired)
                connectionDesired = true // This automatically connects us
            else
                if (isConnectedAndReady)
                    connectionDesired = false // Only allow disconnect after connection successful
        }

        setupRecyclerView()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        // Populate toolbar
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_settings -> {
            // Settings action selected
            val settingsIntent = Intent(this@MainActivity, SettingsActivity::class.java)
            startActivity(settingsIntent)
            true
        }
        R.id.action_calendar -> {
            // Calendar action selected
            val c = Calendar.getInstance()
            val year = c.get(Calendar.YEAR)
            val month = c.get(Calendar.MONTH)
            val day = c.get(Calendar.DAY_OF_MONTH)

            val dpd = DatePickerDialog(this@MainActivity)

            dpd.datePicker.init(year, month, day, DatePicker.OnDateChangedListener { view, year, monthOfYear, dayOfMonth ->
                Log.i("DateChanged", "" + dayOfMonth + ", " + monthOfYear + ", " + year)
                dpd.dismiss()
            })
            dpd.setButton(DatePickerDialog.BUTTON_POSITIVE, null, dpd);
            dpd.setButton(DatePickerDialog.BUTTON_NEGATIVE, null, dpd);

            dpd.show()
            true
        }
        else -> {
            // Unrecognized action
            super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()

        if (bluetoothAdapter != null) {
            if (!bluetoothAdapter!!.isEnabled) {
                promptEnableBluetooth();
            }
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
        // Read all old entries from the database
        repetitions.addAll(databaseHandler.getRepetitionsAtDate(getCurrentDateString()))

        scan_results_recycler_view.apply {
            adapter = repetitionAdapter
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
        if (bluetoothAdapter != null) {
            if (!bluetoothAdapter!!.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, ENABLE_BLUETOOTH_REQUEST_CODE)
            }
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

    private fun startConnection() {
        // Start scanning: we will auto-connect when correct device is found
        startBleScan()
    }

    private fun stopConnection() {
        if (isScanning) stopBleScan()

        // Force a disconnection
        bluetoothGatt?.disconnect()
    }

    private fun startBleScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isLocationPermissionGranted) {
            requestLocationPermission()
        } else {
            if (bleScanner != null) {
                bleScanner!!.startScan(null, scanSettings, scanCallback)
                isScanning = true
            } else {
                runOnUiThread {
                    alert {
                        title = "Bluetooth not supported"
                        message = "Your device does not support bluetooth scanning."
                    }.show()
                }
            }
        }
    }

    private fun stopBleScan() {
        if (bleScanner != null) {
            bleScanner!!.stopScan(scanCallback)
            isScanning = false
        }
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

    private fun addRepetition(timestampMs : Long, dateStr : String,
                              maxVelocityStr : String, minVelocityStr : String,
                              maxAccelStr : String, minAccelStr : String) {

        val repData = RepData(
            timestampMs,
            dateStr,
            maxVelocityStr.toFloat(),
            minVelocityStr.toFloat(),
            maxAccelStr.toFloat(),
            minAccelStr.toFloat())

        runOnUiThread {
            max_velocity.text = maxVelocityStr
            min_velocity.text = minVelocityStr
            max_accel.text = maxAccelStr
            min_accel.text = minAccelStr

            repetitions.add(repData)
            repetitionAdapter.notifyItemInserted(repetitions.size - 1)
        }

        databaseHandler.addRepetition(repData)
    }

    private fun promptDeleteRepetition(repData : RepData) {
        Log.w("promptDeleteRepetition", "Deleting rep: " + repData.toString())
        runOnUiThread {
            alert {
                title = "Delete this entry?"
                positiveButton(android.R.string.ok) {
                    // Remove from list frontend
                    val index = repetitions.indexOf(repData)
                    repetitions.remove(repData)
                    repetitionAdapter.notifyItemRemoved(index)

                    // Remove from database backend
                    databaseHandler.deleteRepetition(repData)
                }
                negativeButton(android.R.string.cancel) {
                    // Do nothing
                }
            }.show()
        }
    }

    private fun getCurrentDateString() = SimpleDateFormat("yyyy-MM-dd").format(Date())

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
                    Log.w("BluetoothGattCallback", "Disconnected from $deviceAddress")
                    gatt.close()
                    bluetoothGatt = null
                    isConnectedAndReady = false

                    // If we desire to be connected, then automatically attempt reconnection
                    if (connectionDesired)
                        startConnection()
                }
            } else {
                Log.w("BluetoothGattCallback", "Error $status encountered for $deviceAddress! Disconnecting...")
                gatt.close()
                bluetoothGatt = null
                isConnectedAndReady = false

                // If we desire to be connected, then automatically attempt reconnection
                if (connectionDesired)
                    startConnection()
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

                val timestampMs = System.currentTimeMillis() / 1000

                addRepetition(timestampMs, getCurrentDateString(), params[0], params[1], params[2], params[3])

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