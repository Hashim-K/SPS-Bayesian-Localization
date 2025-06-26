package com.example.assignment2.ui.home

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.assignment2.R
import com.example.assignment2.data.db.AppDatabase
import com.example.assignment2.data.model.BayesianSettings
import com.example.assignment2.data.model.BayesianMode
import com.example.assignment2.data.model.ParallelSelectionMethod
import com.example.assignment2.util.BayesianPredictor
import com.example.assignment2.util.BssidUtil
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class HomeFragment : Fragment() {

    private val TAG = "HomeFragment"
    private val PREFS_NAME = "BayesianPrefs"
    private val KEY_BAYESIAN_SETTINGS = "bayesianSettings"
    private val DEFAULT_RSSI_FOR_SCAN = -100

    // UI Elements
    private lateinit var buttonGuessLocationHome: Button
    private lateinit var textCurrentGuessedCellHome: TextView
    private lateinit var textLocationStatusHome: TextView
    private lateinit var fabBayesianSettings: FloatingActionButton
    private lateinit var cellViews: List<TextView>

    // System Services
    private lateinit var wifiManager: WifiManager
    private var isWifiReceiverRegistered = false

    // Database & DAOs
    private val appDb by lazy { AppDatabase.getDatabase(requireContext().applicationContext) }
    private val apPmfDao by lazy { appDb.apPmfDao() }
    private val knownApPrimeDao by lazy { appDb.knownApPrimeDao() }

    // Bayesian Predictor
    private lateinit var bayesianPredictor: BayesianPredictor

    // Bayesian Settings
    private lateinit var currentBayesianSettings: BayesianSettings
    private val gson = Gson()
    private lateinit var sharedPreferences: SharedPreferences

    // State for guessing process
    private var isScanningForGuess = false // Overall busy flag for UI
    private var isPerformingMultiScan = false // Specific to 3-scan sequence
    private var scanCount = 0
    private val multiScanHolder = mutableListOf<List<ScanResult>>()
    private val displayCells = Array(10) { i -> "C${i + 1}" }.toList()

    // Colors
    private var activeCellColor: Int = 0
    private var inactiveCellColor: Int = 0

    // Permission Launcher
    private val requestLocationPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                // Permission granted, now check if we were trying to start multi-scan
                if (isScanningForGuess && !isPerformingMultiScan) { // Check if we intended to start
                    startMultiScanProcess()
                } else if (isPerformingMultiScan) {
                    // This case should ideally not happen if startMultiScanProcess only proceeds after permissions.
                    // If it does, it means a scan in the sequence might proceed.
                    Log.d(TAG, "Permission granted during an ongoing multi-scan attempt.")
                }
            } else {
                Toast.makeText(context, "Permission Denied. Cannot perform WiFi scan.", Toast.LENGTH_SHORT).show()
                isScanningForGuess = false // Reset state if permission denied
                isPerformingMultiScan = false
                updateGuessButtonState()
                textLocationStatusHome.text = "Permission denied for scan."
            }
        }

    // WiFi Scan Receiver
    private val wifiScanReceiver = object : BroadcastReceiver() {
        @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_WIFI_STATE])
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isAdded || context == null || intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return

            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            Log.d(TAG, "Scan results available: $success. isPerformingMultiScan: $isPerformingMultiScan")

            if (isPerformingMultiScan) {
                val requiredScans = currentBayesianSettings.numberOfScansForAveraging
                val results: List<ScanResult> = try {
                    if (success) wifiManager.scanResults else emptyList()
                } catch (se: SecurityException) {
                    Log.e(TAG, "SecurityException getting scan results in receiver", se)
                    Toast.makeText(requireContext(), "Permission error getting scan results.", Toast.LENGTH_SHORT).show()
                    emptyList()
                } catch (e: Exception) {
                    Log.e(TAG, "Exception getting scan results in receiver", e)
                    emptyList()
                }

                multiScanHolder.add(results)
                scanCount++
                Log.d(TAG, "Scan $scanCount of $requiredScans completed. Results count: ${results.size}")

                if (scanCount < requiredScans) {
                    textLocationStatusHome.text = "Scan ${scanCount + 1} of $requiredScans in progress..."
                    if (!wifiManager.startScan()) {
                        Log.e(TAG, "wifiManager.startScan() for scan ${scanCount + 1} returned false.")
                        Toast.makeText(requireContext(), "Failed to start next WiFi scan.", Toast.LENGTH_SHORT).show()
                        cleanupAfterMultiScanAttempt("Failed to start scan ${scanCount + 1}.")
                    }
                } else { // All scans collected
                    Log.d(TAG, "All $requiredScans scans collected. Processing...")
                    textLocationStatusHome.text = "All $requiredScans scans complete. Processing..."
                    // isPerformingMultiScan will be set to false inside processAndPredict
                    // isScanningForGuess will be set to false inside runPredictionLogic's finally block
                    processAndPredictFromMultiScan(ArrayList(multiScanHolder)) // Pass a copy
                    multiScanHolder.clear()
                    scanCount = 0 // Reset for next time
                    // Note: isScanningForGuess remains true until prediction logic finishes
                }
            } else {
                // This might happen if a scan was triggered outside the multi-scan sequence,
                // or if flags were reset prematurely.
                Log.w(TAG, "Scan result received but not in multi-scan mode.")
                // If isScanningForGuess is true but isPerformingMultiScan is false, it's an inconsistent state.
                // We might want to reset UI here if it's an unexpected single scan result.
                if(isScanningForGuess) { // if button is disabled but we are not in multiScan.
                    isScanningForGuess = false
                    updateGuessButtonState()
                    textLocationStatusHome.text = "Unexpected scan result."
                }
            }
        }
    }
    private fun cleanupAfterMultiScanAttempt(statusMessage: String) {
        isPerformingMultiScan = false
        isScanningForGuess = false // Free up the UI
        scanCount = 0
        multiScanHolder.clear()
        updateGuessButtonState()
        textLocationStatusHome.text = statusMessage
        highlightGuessedCell(null)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPreferences = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadBayesianSettings()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_home, container, false)
        Log.d(TAG, "onCreateView")

        initServices()
        bayesianPredictor = BayesianPredictor(apPmfDao, knownApPrimeDao)

        val typedValue = TypedValue()
        val theme = requireContext().theme
        theme.resolveAttribute(R.attr.homeCellActiveBackground, typedValue, true)
        activeCellColor = typedValue.data
        theme.resolveAttribute(R.attr.homeCellInactiveBackground, typedValue, true)
        inactiveCellColor = typedValue.data

        textCurrentGuessedCellHome = root.findViewById(R.id.text_current_highlighted_cell_home)
        buttonGuessLocationHome = root.findViewById(R.id.button_guess_location_home)
        textLocationStatusHome = root.findViewById(R.id.text_location_status_home)
        fabBayesianSettings = root.findViewById(R.id.fab_bayesian_settings)

        cellViews = displayCells.mapNotNull { label ->
            val resIdName = "cell_${label.lowercase()}_home"
            val resId = resources.getIdentifier(resIdName, "id", requireContext().packageName)
            if (resId != 0) root.findViewById<TextView>(resId)
            else { Log.e(TAG, "Could not find view ID for $resIdName"); null }
        }

        buttonGuessLocationHome.setOnClickListener {
            Log.d(TAG, "Guess Location button clicked")
            if (!isScanningForGuess) {
                checkPermissionsAndStartMultiScan()
            }
        }
        fabBayesianSettings.setOnClickListener { showBayesianSettingsDialog() }

        updateGuessButtonState()
        highlightGuessedCell(null)
        textLocationStatusHome.text = "Ready to guess location (${currentBayesianSettings.numberOfScansForAveraging}-scan avg)."
        return root
    }

    override fun onStart() {
        super.onStart()
        try {
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            requireContext().registerReceiver(wifiScanReceiver, filter)
            isWifiReceiverRegistered = true
            Log.d(TAG, "WiFi Scan Receiver registered.")
        } catch (e: Exception) { Log.e(TAG, "Error registering WiFi receiver", e) }
    }

    override fun onStop() {
        super.onStop()
        if (isWifiReceiverRegistered) {
            try { requireContext().unregisterReceiver(wifiScanReceiver) }
            catch (e: Exception) { Log.w(TAG, "Error unregistering WiFi receiver", e) }
            isWifiReceiverRegistered = false
            Log.d(TAG, "WiFi Scan Receiver unregistered.")
        }
        // If fragment is stopped during a multi-scan, reset flags
        if (isPerformingMultiScan || isScanningForGuess) {
            Log.d(TAG, "Fragment stopped during scan, resetting state.")
            cleanupAfterMultiScanAttempt("Scan sequence interrupted.")
        }
    }

    private fun initServices() {
        wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        Log.d(TAG, "WifiManager initialized.")
    }

    private fun loadBayesianSettings() {
        val settingsJson = sharedPreferences.getString(KEY_BAYESIAN_SETTINGS, null)
        currentBayesianSettings = if (settingsJson != null) {
            try { 
                val loadedSettings = gson.fromJson(settingsJson, BayesianSettings::class.java)
                // Ensure default for new field if loading old settings
                if (loadedSettings.numberOfScansForAveraging <= 0) {
                    loadedSettings.copy(numberOfScansForAveraging = 3)
                } else {
                    loadedSettings
                }
            }
            catch (e: Exception) { 
                Log.e(TAG, "Error parsing Bayesian settings", e); 
                BayesianSettings() 
            }
        } else {
            BayesianSettings()
        }
        Log.d(TAG, "Loaded settings: $currentBayesianSettings")
    }

    private fun saveBayesianSettings(settings: BayesianSettings) {
        currentBayesianSettings = settings
        sharedPreferences.edit().putString(KEY_BAYESIAN_SETTINGS, gson.toJson(settings)).apply()
        Log.d(TAG, "Saved settings: $currentBayesianSettings")
        Toast.makeText(context, "Bayesian settings saved! Using ${settings.numberOfScansForAveraging}-scan averaging.", Toast.LENGTH_SHORT).show()
        
        // Update UI to reflect new settings
        textLocationStatusHome.text = "Ready to guess location (${settings.numberOfScansForAveraging}-scan avg)."
    }

    private fun showBayesianSettingsDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_bayesian_settings, null)
        val switchMode = dialogView.findViewById<SwitchMaterial>(R.id.switch_bayesian_mode_settings)
        val layoutParallelOptions = dialogView.findViewById<View>(R.id.layout_parallel_options_settings)
        val spinnerSelectionMethod = dialogView.findViewById<Spinner>(R.id.spinner_parallel_selection_method_settings)
        val seekBarPmfBinWidth = dialogView.findViewById<SeekBar>(R.id.seekbar_pmf_bin_width_settings)
        val labelPmfBinWidth = dialogView.findViewById<TextView>(R.id.label_pmf_bin_width_settings)
        val seekBarSerialCutoff = dialogView.findViewById<SeekBar>(R.id.seekbar_serial_cutoff_probability)
        val labelSerialCutoff = dialogView.findViewById<TextView>(R.id.label_serial_cutoff_probability)
        // Add Number of Scans slider elements
        val seekBarNumScans = dialogView.findViewById<SeekBar>(R.id.seekbar_num_scans_for_averaging)
        val labelNumScans = dialogView.findViewById<TextView>(R.id.label_num_scans_for_averaging)

        switchMode.isChecked = currentBayesianSettings.mode == BayesianMode.PARALLEL
        switchMode.text = if (switchMode.isChecked) "Mode: Parallel" else "Mode: Serial"
        layoutParallelOptions.visibility = if (switchMode.isChecked) View.VISIBLE else View.GONE
        seekBarPmfBinWidth.progress = currentBayesianSettings.pmfBinWidth
        labelPmfBinWidth.text = "PMF Bin Width to Use: ${currentBayesianSettings.pmfBinWidth}"
        seekBarSerialCutoff.progress = (currentBayesianSettings.serialCutoffProbability * 100).roundToInt()
        labelSerialCutoff.text = "Serial Cutoff Probability: ${String.format(Locale.US, "%.2f", currentBayesianSettings.serialCutoffProbability)}"
        
        // Initialize Number of Scans SeekBar
        seekBarNumScans.progress = currentBayesianSettings.numberOfScansForAveraging
        labelNumScans.text = "Number of Scans for Averaging (Testing/Home): ${currentBayesianSettings.numberOfScansForAveraging}"

        ArrayAdapter.createFromResource(requireContext(), R.array.parallel_selection_methods, android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerSelectionMethod.adapter = adapter
            spinnerSelectionMethod.setSelection( if (currentBayesianSettings.selectionMethod == ParallelSelectionMethod.HIGHEST_PROBABILITY) 0 else 0 )
        }

        switchMode.setOnCheckedChangeListener { _, isChecked ->
            switchMode.text = if (isChecked) "Mode: Parallel" else "Mode: Serial"
            layoutParallelOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        seekBarPmfBinWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { labelPmfBinWidth.text = "PMF Bin Width to Use: ${p.coerceAtLeast(1)}" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekBarSerialCutoff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { labelSerialCutoff.text = "Serial Cutoff Probability: ${String.format(Locale.US, "%.2f", p.toFloat()/100f)}" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        
        // Add listener for Number of Scans SeekBar
        seekBarNumScans.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { 
                labelNumScans.text = "Number of Scans for Averaging (Testing/Home): ${p.coerceAtLeast(1)}" 
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        AlertDialog.Builder(requireContext())
            .setTitle("Bayesian Prediction Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                saveBayesianSettings(BayesianSettings(
                    mode = if (switchMode.isChecked) BayesianMode.PARALLEL else BayesianMode.SERIAL,
                    selectionMethod = ParallelSelectionMethod.HIGHEST_PROBABILITY, // Assuming this is the only one for now
                    pmfBinWidth = seekBarPmfBinWidth.progress.coerceAtLeast(1),
                    serialCutoffProbability = (seekBarSerialCutoff.progress.toFloat()/100f).toDouble(),
                    numberOfScansForAveraging = seekBarNumScans.progress.coerceAtLeast(1) // Save new setting
                ))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkPermissionsAndStartMultiScan() {
        val context = context ?: return
        // Set flags early to prevent re-entry if permission dialog causes lifecycle events
        isScanningForGuess = true // Overall busy flag
        updateGuessButtonState() // Disable button immediately

        val locationPermission = Manifest.permission.ACCESS_FINE_LOCATION
        val nearbyDevicesPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.NEARBY_WIFI_DEVICES else null
        val hasLocationPermission = ContextCompat.checkSelfPermission(context, locationPermission) == PackageManager.PERMISSION_GRANTED
        val hasNearbyDevicesPermission = nearbyDevicesPermission?.let { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } ?: true

        val permissionsToRequest = mutableListOf<String>()
        if (!hasLocationPermission) permissionsToRequest.add(locationPermission)
        if (nearbyDevicesPermission != null && !hasNearbyDevicesPermission) permissionsToRequest.add(nearbyDevicesPermission)


        if (permissionsToRequest.isEmpty()) {
            startMultiScanProcess()
        } else {
            Log.d(TAG, "Requesting permissions: $permissionsToRequest")
            // The ActivityResultLauncher for multiple permissions is better here if you need to request more than one.
            // For simplicity with current structure, we'll use the single permission launcher.
            // This might lead to requesting one by one if both are missing.
            // A multi-permission launcher would be ideal. For now, launching the first missing one.
            requestLocationPermissionLauncher.launch(permissionsToRequest.first())
            // Note: if permission is denied, isScanningForGuess is reset by the launcher callback.
            // If granted, startMultiScanProcess is called from the launcher callback.
        }
    }

    @RequiresPermission(Manifest.permission.CHANGE_WIFI_STATE)// WifiManager.startScan requires this
    private fun startMultiScanProcess() {
        if (!isAdded) {
            cleanupAfterMultiScanAttempt("Fragment not attached.")
            return
        }
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(requireContext(), "Please enable WiFi", Toast.LENGTH_SHORT).show()
            cleanupAfterMultiScanAttempt("WiFi disabled.")
            return
        }

        Log.d(TAG, "Starting multi-scan process.")
        // isScanningForGuess is already true from checkPermissionsAndStartMultiScan
        val requiredScans = currentBayesianSettings.numberOfScansForAveraging
        isPerformingMultiScan = true
        scanCount = 0
        multiScanHolder.clear()
        textLocationStatusHome.text = "Starting scan 1 of $requiredScans..."
        updateGuessButtonState() // Ensure button is disabled

        if (!wifiManager.startScan()) {
            Log.e(TAG, "wifiManager.startScan() for initial scan returned false.")
            Toast.makeText(requireContext(), "Failed to start WiFi scan.", Toast.LENGTH_SHORT).show()
            cleanupAfterMultiScanAttempt("Failed to start initial scan.")
        }
    }


    private fun processAndPredictFromMultiScan(scans: List<List<ScanResult>>) {
        isPerformingMultiScan = false // Sequence of scans is done. Prediction is next.
        // isScanningForGuess remains true until prediction is complete.

        if (!isAdded) {
            cleanupAfterMultiScanAttempt("Fragment detached before processing.")
            return
        }

        Log.d(TAG, "Processing ${scans.size} scan results for averaging.")
        textLocationStatusHome.text = "Averaging ${scans.size} scans..."

        val accumulatedRssis = mutableMapOf<String, MutableList<Int>>()
        scans.forEach { scanResultList ->
            scanResultList.forEach { sr ->
                BssidUtil.calculateBssidPrime(sr.BSSID)?.let { prime ->
                    accumulatedRssis.getOrPut(prime) { mutableListOf() }.add(sr.level)
                }
            }
        }

        if (accumulatedRssis.isEmpty()) {
            val numScans = currentBayesianSettings.numberOfScansForAveraging
            Log.d(TAG, "No BSSIDs found after accumulating $numScans scans.")
            textLocationStatusHome.text = "No WiFi APs found in $numScans scans."
            highlightGuessedCell(null)
            // Reset isScanningForGuess here as prediction won't run
            isScanningForGuess = false
            updateGuessButtonState()
            return
        }

        val averagedRssiMap = mutableMapOf<String, Int>()
        accumulatedRssis.forEach { (prime, rssiList) ->
            if (rssiList.isNotEmpty()) {
                averagedRssiMap[prime] = rssiList.average().roundToInt()
            }
        }
        Log.d(TAG, "Averaged RSSI map: $averagedRssiMap")
        runPredictionLogic(averagedRssiMap)
    }

    private fun runPredictionLogic(liveRssiMap: Map<String, Int>) {
        if (!isAdded) {
            cleanupAfterMultiScanAttempt("Fragment detached before prediction logic.")
            return
        }
        textLocationStatusHome.text = "Calculating guess from ${currentBayesianSettings.numberOfScansForAveraging}-scan average..."
        Log.d(TAG, "Performing Bayesian guess with averaged RSSIs. Settings: $currentBayesianSettings")

        lifecycleScope.launch {
            try {
                if (liveRssiMap.isEmpty()) { // Should have been caught earlier, but double check
                    textLocationStatusHome.text = "No usable APs after averaging for prediction."
                    highlightGuessedCell(null)
                    return@launch
                }

                val posteriorProbabilities: Map<String, Double> = bayesianPredictor.predict(
                    liveRssiMap,
                    currentBayesianSettings,
                    displayCells // Pass the list of all possible cells
                )

                if (posteriorProbabilities.isEmpty()) {
                    textLocationStatusHome.text = "Could not calculate probabilities."
                    highlightGuessedCell(null)
                    return@launch
                }

                val guessedCell = posteriorProbabilities.maxByOrNull { it.value }?.key
                val highestProb = posteriorProbabilities[guessedCell] ?: 0.0
                val numScans = currentBayesianSettings.numberOfScansForAveraging
                textLocationStatusHome.text = "Guessed: ${guessedCell ?: "Unknown"} (Prob: ${String.format(
                    Locale.US, "%.3f", highestProb)}) ($numScans-scan avg)"
                highlightGuessedCell(guessedCell)
                Log.d(TAG, "Posterior probabilities ($numScans-scan avg): $posteriorProbabilities")
                Log.d(TAG, "Final Guess ($numScans-scan avg): $guessedCell")

            } catch (e: Exception) {
                Log.e(TAG, "Exception in runPredictionLogic", e)
                textLocationStatusHome.text = "Error during prediction: ${e.message}"
                highlightGuessedCell(null)
            } finally {
                if (isAdded) {
                    isScanningForGuess = false // Prediction attempt finished, allow new guess
                    updateGuessButtonState()
                }
            }
        }
    }


    private fun updateGuessButtonState() {
        if(isAdded) { // Check if fragment is attached
            buttonGuessLocationHome.isEnabled = !isScanningForGuess
        }
    }

    private fun highlightGuessedCell(guessedCellLabel: String?) {
        if (!isAdded) return
        textCurrentGuessedCellHome.text = guessedCellLabel ?: "---"
        cellViews.forEach { cellView ->
            cellView.setBackgroundColor(if (cellView.text.toString() == guessedCellLabel) activeCellColor else inactiveCellColor)
        }
    }
}