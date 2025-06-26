package com.example.assignment2.ui.testing

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
// import android.provider.DocumentsContract // Not explicitly used for initial URI
import android.util.Log
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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.assignment2.R
import com.example.assignment2.data.db.AppDatabase
import com.example.assignment2.data.db.MeasurementTime
import com.example.assignment2.data.model.BayesianMode
import com.example.assignment2.data.model.BayesianSettings
import com.example.assignment2.data.model.MeasurementType
import com.example.assignment2.data.model.ParallelSelectionMethod
import com.example.assignment2.util.BayesianPredictor
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// Data class for structured prediction results
data class TestPredictionResult(
    val timestampMillis: Long,
    val formattedTime: String,
    val actualCell: String,
    val predictedCell: String?,
    val predictionProbability: Double?,
    val status: String,
    val averagedScanData: Map<String, Int>?,
    val posteriorProbabilitiesForAllCells: Map<String, Double>?
)

class BayesianTestingFragment : Fragment() {

    private val TAG = "BayesianTestingFragment"
    private val PREFS_NAME = "BayesianPrefs"
    private val KEY_BAYESIAN_SETTINGS = "bayesianSettings"

    // UI Elements
    private lateinit var textTestingStatus: TextView
    private lateinit var buttonRunBayesianTesting: Button
    private lateinit var buttonClearTestingResults: Button
    private lateinit var buttonExportResultsJson: Button
    private lateinit var textOverallAccuracy: TextView
    private lateinit var textCorrectPredictions: TextView
    private lateinit var textTotalPredictions: TextView
    private lateinit var textIndividualResults: TextView
    private lateinit var fabTestingSettings: FloatingActionButton

    // DAOs
    private val appDb by lazy { AppDatabase.getDatabase(requireContext().applicationContext) }
    private val measurementTimeDao by lazy { appDb.measurementTimeDao() }
    private val apMeasurementDao by lazy { appDb.apMeasurementDao() }
    private val apPmfDao by lazy { appDb.apPmfDao() }
    private val knownApPrimeDao by lazy { appDb.knownApPrimeDao() }

    // Bayesian Predictor
    private lateinit var bayesianPredictor: BayesianPredictor

    // State
    private var testingRunning: Boolean = false
    private lateinit var currentBayesianSettings: BayesianSettings
    private val displayCells = Array(10) { i -> "C${i + 1}" }.toList()
    private lateinit var sharedPreferences: SharedPreferences
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val detailedTestResults = mutableListOf<TestPredictionResult>()
    private lateinit var createFileLauncher: ActivityResultLauncher<Intent>


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPreferences = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadBayesianSettings() // Load initial settings

        createFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    exportResultsToJsonFile(uri)
                } ?: Toast.makeText(context, "Failed to get file location.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "File saving cancelled.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_bayesian_testing, container, false)

        textTestingStatus = root.findViewById(R.id.text_testing_status)
        buttonRunBayesianTesting = root.findViewById(R.id.button_run_bayesian_testing)
        buttonClearTestingResults = root.findViewById(R.id.button_clear_testing_results)
        buttonExportResultsJson = root.findViewById(R.id.button_export_results_json)
        textOverallAccuracy = root.findViewById(R.id.text_overall_accuracy)
        textCorrectPredictions = root.findViewById(R.id.text_correct_predictions)
        textTotalPredictions = root.findViewById(R.id.text_total_predictions)
        textIndividualResults = root.findViewById(R.id.text_individual_results)
        fabTestingSettings = root.findViewById(R.id.fab_testing_settings)

        bayesianPredictor = BayesianPredictor(apPmfDao, knownApPrimeDao)

        setupListeners()
        updateUIState(0, 0)
        buttonExportResultsJson.isEnabled = false
        updateButtonText() // Update button text based on settings

        return root
    }

    override fun onResume() {
        super.onResume()
        loadBayesianSettings() // Reload settings in case they changed
        updateButtonText()
        textTestingStatus.text = "Ready to run tests (${currentBayesianSettings.numberOfScansForAveraging}-scan avg). Using ${currentBayesianSettings.mode} mode (Bin Width: ${currentBayesianSettings.pmfBinWidth})."
    }

    private fun updateButtonText() {
        if(::buttonRunBayesianTesting.isInitialized) { // Ensure view is created
            buttonRunBayesianTesting.text = "Run Bayesian Testing (${currentBayesianSettings.numberOfScansForAveraging}-Scan Avg)"
        }
    }


    private fun setupListeners() {
        buttonRunBayesianTesting.setOnClickListener {
            if (!testingRunning) {
                runBayesianTesting()
            } else {
                Toast.makeText(context, "Testing is already running.", Toast.LENGTH_SHORT).show()
            }
        }

        buttonClearTestingResults.setOnClickListener {
            clearResultsDisplay()
            detailedTestResults.clear()
            buttonExportResultsJson.isEnabled = false
            Toast.makeText(context, "Results cleared.", Toast.LENGTH_SHORT).show()
        }

        buttonExportResultsJson.setOnClickListener {
            initiateJsonExport()
        }

        fabTestingSettings.setOnClickListener {
            showBayesianSettingsDialog()
        }
    }

    private fun initiateJsonExport() {
        if (detailedTestResults.isEmpty()) {
            Toast.makeText(context, "No results to export. Run testing first.", Toast.LENGTH_SHORT).show()
            return
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "bayesian_test_results_${currentBayesianSettings.numberOfScansForAveraging}scans_$timestamp.json"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        try {
            createFileLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching file creator", e)
            Toast.makeText(context, "Error initiating file export: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportResultsToJsonFile(uri: Uri) {
        try {
            val jsonString = gson.toJson(detailedTestResults)
            requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(jsonString)
                }
            }
            Toast.makeText(context, "Results exported successfully.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing JSON to file", e)
            Toast.makeText(context, "Failed to export results: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadBayesianSettings() {
        val settingsJson = sharedPreferences.getString(KEY_BAYESIAN_SETTINGS, null)
        currentBayesianSettings = if (settingsJson != null) {
            try {
                gson.fromJson(settingsJson, BayesianSettings::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing Bayesian settings, using defaults.", e)
                BayesianSettings()
            }
        } else {
            BayesianSettings()
        }
        // Ensure default for new field if loading old settings
        if (currentBayesianSettings.numberOfScansForAveraging <= 0) {
            currentBayesianSettings = currentBayesianSettings.copy(numberOfScansForAveraging = 3)
        }
        Log.d(TAG, "Loaded Bayesian settings for testing: $currentBayesianSettings")
    }

    private fun saveBayesianSettings(settings: BayesianSettings) {
        currentBayesianSettings = settings
        val settingsJson = gson.toJson(settings)
        sharedPreferences.edit().putString(KEY_BAYESIAN_SETTINGS, settingsJson).apply()
        Log.d(TAG, "Saved settings: $currentBayesianSettings")
        Toast.makeText(context, "Bayesian settings saved!", Toast.LENGTH_SHORT).show()
        updateButtonText() // Update button text after saving
        textTestingStatus.text = "Settings updated. Ready for ${currentBayesianSettings.numberOfScansForAveraging}-scan avg tests. Using ${currentBayesianSettings.mode} mode (Bin Width: ${currentBayesianSettings.pmfBinWidth})."
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
        // New UI elements for Number of Scans
        val seekBarNumScans = dialogView.findViewById<SeekBar>(R.id.seekbar_num_scans_for_averaging)
        val labelNumScans = dialogView.findViewById<TextView>(R.id.label_num_scans_for_averaging)

        val currentSettings = currentBayesianSettings

        switchMode.isChecked = currentSettings.mode == BayesianMode.PARALLEL
        switchMode.text = if (switchMode.isChecked) "Mode: Parallel" else "Mode: Serial"
        layoutParallelOptions.visibility = if (switchMode.isChecked) View.VISIBLE else View.GONE

        seekBarPmfBinWidth.progress = currentSettings.pmfBinWidth
        labelPmfBinWidth.text = "PMF Bin Width to Use: ${currentSettings.pmfBinWidth}"

        seekBarSerialCutoff.progress = (currentSettings.serialCutoffProbability * 100).roundToInt()
        labelSerialCutoff.text = "Serial Cutoff Probability: ${String.format(Locale.US, "%.2f", currentSettings.serialCutoffProbability)}"

        // Initialize Number of Scans SeekBar
        seekBarNumScans.progress = currentSettings.numberOfScansForAveraging
        labelNumScans.text = "Number of Scans for Averaging: ${currentSettings.numberOfScansForAveraging}"


        ArrayAdapter.createFromResource(
            requireContext(), R.array.parallel_selection_methods, android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerSelectionMethod.adapter = adapter
            spinnerSelectionMethod.setSelection(
                if (currentSettings.selectionMethod == ParallelSelectionMethod.HIGHEST_PROBABILITY) 0 else 0
            )
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
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { labelSerialCutoff.text = "Serial Cutoff Probability: ${String.format(Locale.US, "%.2f", p.toFloat() / 100f)}" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekBarNumScans.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { labelNumScans.text = "Number of Scans for Averaging: ${p.coerceAtLeast(1)}" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        AlertDialog.Builder(requireContext())
            .setTitle("Bayesian Prediction Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newMode = if (switchMode.isChecked) BayesianMode.PARALLEL else BayesianMode.SERIAL
                val newSelectionMethod = ParallelSelectionMethod.HIGHEST_PROBABILITY
                val newPmfBinWidth = seekBarPmfBinWidth.progress.coerceAtLeast(1)
                val newSerialCutoff = (seekBarSerialCutoff.progress.toFloat() / 100f).toDouble()
                val newNumScans = seekBarNumScans.progress.coerceAtLeast(1)

                saveBayesianSettings(
                    BayesianSettings(
                        mode = newMode,
                        selectionMethod = newSelectionMethod,
                        pmfBinWidth = newPmfBinWidth,
                        serialCutoffProbability = newSerialCutoff,
                        numberOfScansForAveraging = newNumScans // Save new setting
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runBayesianTesting() {
        testingRunning = true
        buttonRunBayesianTesting.isEnabled = false
        buttonClearTestingResults.isEnabled = false
        buttonExportResultsJson.isEnabled = false
        val numScansToAverage = currentBayesianSettings.numberOfScansForAveraging
        textTestingStatus.text = "Loading testing data for $numScansToAverage-scan averaging..."
        clearResultsDisplay()
        detailedTestResults.clear()

        val settings = currentBayesianSettings

        lifecycleScope.launch {
            try {
                val allTestingScanEventsGloballySorted = withContext(Dispatchers.IO) {
                    measurementTimeDao.getAll()
                        .filter { it.measurementType == MeasurementType.TESTING }
                        .sortedBy { it.timestampMillis }
                }

                if (allTestingScanEventsGloballySorted.size < numScansToAverage) {
                    textTestingStatus.text = "Not enough TESTING data (need at least $numScansToAverage scans globally). Record more."
                    Toast.makeText(context, "Not enough testing data for $numScansToAverage-scan sequences.", Toast.LENGTH_LONG).show()
                    finalizeTestingUI()
                    return@launch
                }

                val testingScanEventsByCell = allTestingScanEventsGloballySorted.groupBy { it.cell }
                val allApMeasurementsList = withContext(Dispatchers.IO) { apMeasurementDao.getAllRawMeasurementsOrdered() }
                val apMeasurementsByTimestampId = allApMeasurementsList.groupBy { it.timestampId }

                var correctPredictions = 0
                var totalScanWindows = 0
                val individualResultsBuilder = StringBuilder()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

                textTestingStatus.text = "Processing ${allTestingScanEventsGloballySorted.size} total scans into $numScansToAverage-scan windows..."

                for ((cell, scanEventsInCell) in testingScanEventsByCell) {
                    if (scanEventsInCell.size < numScansToAverage) {
                        Log.d(TAG, "Cell $cell has ${scanEventsInCell.size} scans, less than $numScansToAverage. Skipping.")
                        continue
                    }

                    for (i in (numScansToAverage - 1) until scanEventsInCell.size) {
                        totalScanWindows++

                        val currentWindowScanEvents = mutableListOf<MeasurementTime>()
                        for (j in 0 until numScansToAverage) {
                            currentWindowScanEvents.add(scanEventsInCell[i - j])
                        }
                        // The latest scan in the window is currentWindowScanEvents.first() (was scanEventsInCell[i])
                        // The oldest scan in the window is currentWindowScanEvents.last() (was scanEventsInCell[i - (numScansToAverage - 1)])

                        val latestScanEventInWindow = currentWindowScanEvents.first() // scanEventsInCell[i]
                        val actualCell = latestScanEventInWindow.cell
                        val timestampMillisForDisplay = latestScanEventInWindow.timestampMillis
                        val formattedTime = dateFormat.format(Date(timestampMillisForDisplay))

                        val accumulatedRssis = mutableMapOf<String, MutableList<Int>>()
                        currentWindowScanEvents.forEach { scanEvent ->
                            val apMeasurements = apMeasurementsByTimestampId[scanEvent.timestampId] ?: emptyList()
                            apMeasurements.forEach { apm ->
                                accumulatedRssis.getOrPut(apm.bssidPrime) { mutableListOf() }.add(apm.rssi)
                            }
                        }

                        var currentResultStatus = "UNKNOWN"
                        var predictedCellResult: String? = null
                        var predictionProbResult: Double? = null
                        var averagedScanDataForExport: Map<String, Int>? = null
                        var posteriorProbabilitiesForExport: Map<String, Double>? = null

                        if (accumulatedRssis.isEmpty()) {
                            currentResultStatus = "NO_APS_IN_$numScansToAverage scans"
                            individualResultsBuilder.append("Time: $formattedTime, True: $actualCell, Pred: N/A, Prob: N/A, Status: $currentResultStatus\n")
                        } else {
                            val liveRssiMap = mutableMapOf<String, Int>()
                            accumulatedRssis.forEach { (bssidPrime, rssiList) ->
                                if (rssiList.isNotEmpty()) {
                                    liveRssiMap[bssidPrime] = rssiList.average().roundToInt()
                                }
                            }
                            averagedScanDataForExport = liveRssiMap.toMap()

                            val posteriorProbabilities = bayesianPredictor.predict(liveRssiMap, settings, displayCells)
                            posteriorProbabilitiesForExport = posteriorProbabilities.toMap()

                            if (posteriorProbabilities.isEmpty()) {
                                currentResultStatus = "NO_PREDICTION_POSSIBLE"
                                individualResultsBuilder.append("Time: $formattedTime, True: $actualCell, Pred: N/A, Prob: N/A, Status: $currentResultStatus (from $numScansToAverage scans)\n")
                            } else {
                                predictedCellResult = posteriorProbabilities.maxByOrNull { it.value }?.key
                                predictionProbResult = posteriorProbabilities[predictedCellResult] ?: 0.0
                                currentResultStatus = if (predictedCellResult == actualCell) {
                                    correctPredictions++
                                    "CORRECT"
                                } else {
                                    "INCORRECT"
                                }
                                individualResultsBuilder.append("Time: $formattedTime, True: $actualCell, Pred: ${predictedCellResult ?: "N/A"}, Prob: ${String.format(Locale.US, "%.3f", predictionProbResult)}, Status: $currentResultStatus (from $numScansToAverage scans)\n")
                            }
                        }
                        detailedTestResults.add(TestPredictionResult(timestampMillisForDisplay, formattedTime, actualCell, predictedCellResult, predictionProbResult, currentResultStatus, averagedScanDataForExport, posteriorProbabilitiesForExport))
                    }
                }

                val finalStatusMessage = when {
                    totalScanWindows == 0 && allTestingScanEventsGloballySorted.size >= numScansToAverage -> "Testing complete. No valid $numScansToAverage-scan sequences found for any cell."
                    totalScanWindows == 0 -> "Testing complete. Not enough data for any $numScansToAverage-scan sequences."
                    else -> "Testing complete. Processed $totalScanWindows $numScansToAverage-scan windows. Last run at ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}."
                }
                textTestingStatus.text = finalStatusMessage
                updateUIState(correctPredictions, totalScanWindows, individualResultsBuilder.toString())
                if (detailedTestResults.isNotEmpty()) {
                    buttonExportResultsJson.isEnabled = true
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during Bayesian testing evaluation", e)
                Toast.makeText(context, "Error during testing: ${e.message}", Toast.LENGTH_LONG).show()
                textTestingStatus.text = "Testing failed: ${e.localizedMessage}"
            } finally {
                finalizeTestingUI()
            }
        }
    }

    private fun finalizeTestingUI() {
        testingRunning = false
        if (isAdded) {
            buttonRunBayesianTesting.isEnabled = true
            buttonClearTestingResults.isEnabled = true
        }
    }

    private fun updateUIState(correct: Int, total: Int, individualResults: String = "") {
        if (!isAdded) return
        val accuracy = if (total > 0) (correct.toDouble() / total * 100) else 0.0
        textOverallAccuracy.text = String.format(Locale.US, "%.2f%%", accuracy)
        textCorrectPredictions.text = "Correct: $correct"
        textTotalPredictions.text = "Total Preds: $total"
        textIndividualResults.text = individualResults
    }

    private fun clearResultsDisplay() {
        if (!isAdded) return
        textOverallAccuracy.text = "N/A"
        textCorrectPredictions.text = "Correct: 0"
        textTotalPredictions.text = "Total Preds: 0"
        textIndividualResults.text = ""
        // Update status text reflecting current settings when cleared
        val numScans = if (::currentBayesianSettings.isInitialized) currentBayesianSettings.numberOfScansForAveraging else 3
        val mode = if (::currentBayesianSettings.isInitialized) currentBayesianSettings.mode else BayesianMode.PARALLEL
        val binWidth = if (::currentBayesianSettings.isInitialized) currentBayesianSettings.pmfBinWidth else 1
        textTestingStatus.text = "Ready to run tests ($numScans-scan avg). Using $mode mode (Bin Width: $binWidth)."
    }
}