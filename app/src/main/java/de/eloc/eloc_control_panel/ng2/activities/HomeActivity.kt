package de.eloc.eloc_control_panel.ng2.activities

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.eloc.eloc_control_panel.BuildConfig
import de.eloc.eloc_control_panel.R
import de.eloc.eloc_control_panel.SNTPClient
import de.eloc.eloc_control_panel.UploadFileAsync
import de.eloc.eloc_control_panel.activities.MapActivity
import de.eloc.eloc_control_panel.activities.TerminalActivity
import de.eloc.eloc_control_panel.databinding.ActivityHomeBinding
import de.eloc.eloc_control_panel.databinding.PopupWindowBinding
import de.eloc.eloc_control_panel.ng2.models.BluetoothHelper
import de.eloc.eloc_control_panel.ng2.models.ElocInfoAdapter
import de.eloc.eloc_control_panel.ng2.models.PreferencesHelper
import de.eloc.eloc_control_panel.ng2.receivers.BluetoothDeviceReceiver
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class HomeActivity : ThemableActivity() {
    private lateinit var binding: ActivityHomeBinding
    private val preferencesHelper = PreferencesHelper.instance
    private val bluetoothHelper = BluetoothHelper.instance
    private var rangerName = preferencesHelper.getRangerName()
    private val elocAdapter = ElocInfoAdapter(this::onListUpdated, this::showDevice)
    private val elocReceiver = BluetoothDeviceReceiver(elocAdapter::add)
    private var gUploadEnabled = false
    private var gLastTimeDifferenceMillisecond = 0L
    private lateinit var preferencesLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initialize()
    }

    override fun onResume() {
        super.onResume()
        checkRangerName()

        val hasNoDevices = binding.devicesRecyclerView.adapter?.itemCount == 0
        if (hasNoDevices) {
            startScan()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_devices, menu)
        val aboutItem = menu?.findItem(R.id.about)
        aboutItem?.title = BuildConfig.VERSION_NAME
        menu?.findItem(R.id.bt_settings)?.isEnabled = bluetoothHelper.hasAdapter
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.browseStatusUpdates -> ActivityHelper.showStatusUpdates(this@HomeActivity)
            R.id.timeSync -> doSync()
            R.id.setRangerName -> editRangerName()
            R.id.bt_settings -> bluetoothHelper.openSettings(this)
            R.id.userPrefs -> openUserPrefs()
        }
        return true
    }

    override fun onStop() {
        super.onStop()
        bluetoothHelper.stopScan(this::scanUpdate)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(elocReceiver)
    }

    private fun openUserPrefs() {
        val intent = Intent(this, UserPrefsActivity::class.java)
        preferencesLauncher.launch(intent)
    }

    private fun initialize() {
        registerReceiver(elocReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        setLaunchers()
        setListeners()
        setupListView()
        loadRangerName()
    }

    private fun setLaunchers() {
        preferencesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == RESULT_OK) {
                val fontSizeChanged = result.data?.getBooleanExtra(UserPrefsActivity.EXTRA_FONT_SIZE_CHANGED, false)
                        ?: false
                if (fontSizeChanged) {
                    MaterialAlertDialogBuilder(this)
                            .setCancelable(false)
                            .setTitle(R.string.app_restart_required)
                            .setMessage(R.string.app_restart_message)
                            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                                dialog.dismiss()
                                finish()
                            }
                            .show()
                }
            }
        }
    }

    private fun setListeners() {

        // oops... i am still new to kotlin and I have to look up somethihing haha
        binding.instructionsButton.setOnClickListener { ActivityHelper.showInstructions(this@HomeActivity) }
        binding.refreshListButton.setOnClickListener { startScan() }
        binding.uploadElocStatusButton.setOnClickListener { uploadElocStatus() }
        binding.findElocButton.setOnClickListener { showMap() }
    }

    private fun loadRangerName() {
        rangerName = preferencesHelper.getRangerName().trim()
    }

    private fun setupListView() {
        binding.devicesRecyclerView.adapter = elocAdapter
        binding.devicesRecyclerView.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
    }

    private fun checkRangerName() {
        loadRangerName()
        if (TextUtils.isEmpty(rangerName) || rangerName == PreferencesHelper.DEFAULT_RANGER_NAME) {
            editRangerName()
        }
    }

    private fun validateRangerName(name: String) {
        preferencesHelper.setRangerName(name.trim())
        loadRangerName()
        if (TextUtils.isEmpty(rangerName)) {
            checkRangerName()
        }
        ActivityHelper.hideKeyboard(this)
    }

    private fun editRangerName() {
        val popupWindowBinding = PopupWindowBinding.inflate(layoutInflater)
        popupWindowBinding.rangerName.setText(rangerName)
        MaterialAlertDialogBuilder(this)
                .setCancelable(false)
                .setTitle(R.string.input_your_ranger_id)
                .setView(popupWindowBinding.root)
                .setPositiveButton(R.string.save) { dialog, _ ->
                    run {
                        val editable = popupWindowBinding.rangerName.text
                        if (editable != null) {
                            dialog.dismiss()
                            val name = editable.toString().trim()
                            validateRangerName(name)
                        }
                    }
                }
                .show()
    }

    private fun startScan() {
        val isOn = BluetoothHelper.instance.isAdapterOn()
        binding.status.text =
                if (isOn)
                    getString(R.string.scanning_eloc_devices)
                else
                    "<bluetooth is disabled>"
        if (!isOn) {
            return
        }

        if (!bluetoothHelper.isScanning) {
            elocAdapter.clear()
        }

        onListUpdated(false)
        val error = bluetoothHelper.startScan(this::scanUpdate)
        if (!TextUtils.isEmpty(error)) {
            ActivityHelper.showSnack(binding.coordinator, error!!)
        }
    }

    private fun scanUpdate(remaining: Int) {
        runOnUiThread {
            var label = getString(R.string.refresh)
            if (remaining > 0) {
                label = getString(R.string.stop, remaining)
            } else {
                onListUpdated(scanFinished = true)
            }
            binding.refreshListButton.text = label
        }
    }

    private fun onListUpdated(scanFinished: Boolean) {
        runOnUiThread {
            val hasEmptyAdapter = binding.devicesRecyclerView.adapter?.itemCount == 0
            binding.refreshListButton.visibility = View.VISIBLE
            binding.devicesRecyclerView.visibility =
                    if (hasEmptyAdapter) View.GONE else View.VISIBLE
            if (scanFinished) {
                binding.uploadElocStatusButton.visibility = View.VISIBLE
                binding.findElocButton.visibility = View.VISIBLE
                if (hasEmptyAdapter) {
                    binding.statusLayout.visibility = View.VISIBLE
                    binding.status.visibility = View.VISIBLE
                    binding.status.text = getString(R.string.no_devices_found)
                    binding.progressHorizontal.visibility = View.INVISIBLE
                } else {
                    binding.statusLayout.visibility = View.GONE
                }
            } else {
                binding.statusLayout.visibility = View.VISIBLE
                binding.status.visibility = View.VISIBLE
                binding.status.text = getString(R.string.scanning_eloc_devices)
                binding.progressHorizontal.visibility = View.VISIBLE
                binding.uploadElocStatusButton.visibility = View.GONE
                binding.findElocButton.visibility = View.GONE
            }
        }
    }

    private fun fileToString(file: File): String {
        val text = StringBuilder()
        var br: BufferedReader? = null
        try {
            br = BufferedReader(FileReader(file))
            while (true) {
                try {
                    val line = br.readLine() ?: break
                    text.append(line)
                    text.append('\n')
                } catch (_: IOException) {
                    break
                }
            }
        } catch (_: IOException) {
        } finally {
            br?.close()
        }
        return text.toString()
    }

    private fun uploadElocStatus() {
        var filecounter = 0
        loadRangerName()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        val filestring = "update " + sdf.format(Date()) + ".upd"

        var fileout: OutputStreamWriter? = null
        try {
            val files = filesDir.listFiles()
            fileout = OutputStreamWriter(openFileOutput(filestring, Context.MODE_PRIVATE))
            if (files != null) {
                for (file in files) {
                    if (!file.isDirectory) {
                        if (file.name.endsWith(".txt")) {
                            filecounter++
                            fileout.write(fileToString(file) + "\n\n\n")
                        }
                    }
                }
                fileout.write("\n\n\n end of updates")
            }
        } catch (_: Exception) {
        } finally {
            fileout?.close()
        }

        if (filecounter > 0) {
            val filename = filesDir.absolutePath + "/" + filestring
            UploadFileAsync.run(filename, filesDir) { message ->
                runOnUiThread {
                    if (message.trim().isNotEmpty()) {
                        ActivityHelper.showSnack(binding.coordinator, message)
                    }
                }
            }
        } else {
            ActivityHelper.showSnack(binding.coordinator, "Nothing to Upload!")
        }
    }

    private fun showDevice(name: String, address: String) {
        BluetoothHelper.instance.stopScan(this::scanUpdate)
        val intent = Intent(this, TerminalActivity::class.java)
        intent.putExtra(TerminalActivity.EXTRA_DEVICE, address)
        intent.putExtra(TerminalActivity.EXTRA_DEVICE_NAME, name)
        startActivity(intent)
    }

    private fun doSync() {
        val timeoutMS = 5000
        val showMessage = true
        SNTPClient.getDate(
                timeoutMS,
                Calendar.getInstance().timeZone
        ) { _,
            _,
            googletimestamp,
            _
            ->
            run {
                if (googletimestamp == 0L) {
                    gUploadEnabled = false
                    invalidateOptionsMenu()
                    if (showMessage) {
                        ActivityHelper.showSnack(
                                binding.coordinator,
                                "sync FAILED\nCheck internet connection"
                        )
                    }
                } else {
                    gLastTimeDifferenceMillisecond = System.currentTimeMillis() - googletimestamp
                    PreferencesHelper.instance.saveTimestamps(
                            SystemClock.elapsedRealtime(),
                            googletimestamp
                    )
                    gUploadEnabled = true
                    invalidateOptionsMenu()
                    if (showMessage) {
                        val message =
                                getString(R.string.sync_template, gLastTimeDifferenceMillisecond)
                        ActivityHelper.showSnack(binding.coordinator, message)
                    }
                }
            }
        }
    }

    private fun showMap() {
        val intent = Intent(this, MapActivity::class.java)
        startActivity(intent)
    }
}
