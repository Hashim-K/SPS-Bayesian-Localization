package com.example.assignment2.util

import android.util.Log
import com.example.assignment2.data.db.ApPmf
import com.example.assignment2.data.db.ApPmfDao
import com.example.assignment2.data.db.KnownApPrimeDao
import com.example.assignment2.data.model.ApType
import com.example.assignment2.data.model.BayesianMode
import com.example.assignment2.data.model.BayesianSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Performs Bayesian location prediction using pre-calculated PMF data.
 * This class encapsulates the core Bayesian estimation logic.
 *
 * @param apPmfDao DAO for accessing stored PMF data.
 * @param knownApPrimeDao DAO for accessing Known AP Prime data (to filter fixed APs).
 */
class BayesianPredictor(
    private val apPmfDao: ApPmfDao,
    private val knownApPrimeDao: KnownApPrimeDao
) {

    private val TAG = "BayesianPredictor"
    private val DEFAULT_RSSI = -100 // Consistent with ApMeasurement default
    private val LIKELIHOOD_FOR_NULL = 10.0e-3 // A small non-zero likelihood for APs not in PMF model for a cell
    private val LIKELIHOOD_FOR_EMPTY = 10.0e-3 // A small non-zero likelihood for empty PMF bins
    private val LIKELIHOOD_FOR_ZERO = 10.0e-2
    private val MIN_ITERATIONS_BEFORE_SERIAL_CUTOFF = 1 // New constant for minimum iterations
    private val PARALLEL_BATCH_SIZE = 5 // Number of APs to process in parallel batches

    /**
     * Executes the Bayesian prediction based on the provided live RSSI scan and settings.
     *
     * @param liveRssiMap A map of detected BSSID Primes to their RSSI values from the current scan.
     * @param settings The Bayesian prediction settings (mode, bin width, etc.).
     * @param allPossibleCells A list of all possible cell labels (e.g., C1, C2, ...).
     * @return A map of cell labels to their posterior probabilities. Returns empty map if prediction is not possible.
     */
    suspend fun predict(
        liveRssiMap: Map<String, Int>,
        settings: BayesianSettings,
        allPossibleCells: List<String>
    ): Map<String, Double> {

        // Fetch all PMFs for the specified bin width and group them by BSSID Prime.
        val allPmfsForBinWidth = withContext(Dispatchers.IO) {
            apPmfDao.getAllPmfs()
                .filter { it.binWidth == settings.pmfBinWidth }
        }.groupBy { it.bssidPrime } // Group by BSSID Prime for efficient lookup

        if (allPmfsForBinWidth.isEmpty()) {
            Log.w(TAG, "No PMF data found for bin width ${settings.pmfBinWidth}. Cannot predict.")
            return emptyMap()
        }

        // Get all BSSID Primes of fixed APs from the database.
        // These are the APs that will be used in the Bayesian model.
        val allFixedApBssidsInModel = withContext(Dispatchers.IO) {
            knownApPrimeDao.getAll()
                .filter { it.apType == ApType.FIXED }
                .map { it.bssidPrime }
                .toSet() // Use a Set for efficient `contains` checks if needed later
        }

        if (allFixedApBssidsInModel.isEmpty()) {
            Log.w(TAG, "No fixed APs found in the model (KnownApPrime table). Cannot predict.")
            return emptyMap()
        }

        // Delegate to the appropriate calculation method based on the selected mode.
        return when (settings.mode) {
            BayesianMode.PARALLEL -> calculateBatchedParallelBayesian(
                liveRssiMap,
                allPmfsForBinWidth,
                allPossibleCells,
                allFixedApBssidsInModel,
                settings.serialCutoffProbability // Reusing serialCutoffProbability for the batched parallel cutoff
            )
            BayesianMode.SERIAL -> calculateSerialBayesian(
                liveRssiMap,
                allPmfsForBinWidth,
                allPossibleCells,
                allFixedApBssidsInModel,
                settings.serialCutoffProbability
            )
        }
    }

    /**
     * Calculates the likelihood P(RSSI | Cell, AP_BSSID).
     * This is derived from the stored PMF for the given AP in the given Cell.
     *
     * @param bssidPrime The BSSID Prime of the Access Point.
     * @param cell The cell label.
     * @param observedRssi The observed RSSI value for the AP.
     * @param apPmf The ApPmf object for this AP, cell, and bin width.
     * @return The calculated likelihood.
     */
    private fun getLikelihood(
        bssidPrime: String,
        cell: String,
        observedRssi: Int,
        apPmf: ApPmf? // ApPmf for the specific bssidPrime, cell, and binWidth
    ): Double {
        // If no PMF data is available for this AP in this cell,
        // return a very small, non-zero likelihood. This prevents a single missing
        // AP model from zeroing out the entire probability for a cell.
        if (apPmf == null || apPmf.binsData.isEmpty()) {
            Log.w(TAG, "PMF data is null or empty for AP $bssidPrime in Cell $cell. Returning default likelihood for missing model ($LIKELIHOOD_FOR_NULL).")
            return LIKELIHOOD_FOR_NULL
        }

        val binsData = apPmf.binsData
        val totalCount = binsData.values.sum()

        // If the PMF exists but has a total count of zero (e.g., no observations during training),
        // treat this as effectively missing data for this AP in this cell.
        if (totalCount == 0) {
            Log.d(TAG, "Total count in PMF is zero for AP $bssidPrime in Cell $cell. Returning default likelihood for missing model ($LIKELIHOOD_FOR_EMPTY).")
            return LIKELIHOOD_FOR_EMPTY // Or could be 0.0 if strictly no evidence. For robustness, small value.
        }

        // Determine which bin the observed RSSI falls into.
        // The ApPmf helper method getBinStartForRssi should handle this based on its binWidth.
        val relevantBinStart = apPmf.getBinStartForRssi(observedRssi)
        val countInBin = binsData[relevantBinStart] ?: 0 // Count of observations in that bin

        // Likelihood is P(RSSI | Cell, AP) = Count_in_bin / Total_count_in_PMF
        val likelihood = countInBin.toDouble() / totalCount.toDouble()

        if (likelihood == 0.0) {
            Log.d(TAG, "Likelihood is $LIKELIHOOD_FOR_ZERO for AP $bssidPrime (RSSI $observedRssi -> Bin $relevantBinStart) in Cell $cell. (Bin had 0 counts).")
            return LIKELIHOOD_FOR_ZERO
        }
        Log.d(TAG, "Likelihood is $likelihood for AP $bssidPrime (RSSI $observedRssi -> Bin $relevantBinStart) in Cell $cell. (Bin had $countInBin counts out of total $totalCount).")
        return likelihood
    }

    private fun calculateBatchedParallelBayesian(
        liveRssiMap: Map<String, Int>,
        pmfsByBssid: Map<String, List<ApPmf>>,
        allCells: List<String>,
        allFixedApBssidsInModel: Set<String>,
        cutoffProbability: Double
    ): Map<String, Double> {
        val numCells = allCells.size
        if (numCells == 0) return emptyMap()

        // Start with uniform priors
        var currentPriors = allCells.associateWith { 1.0 / numCells }.toMutableMap()

        // 1. Filter APs: Only those seen in the live scan AND are part of our fixed model
        // 2. Sort these observed model APs by their RSSI values (strongest first)
        val observedModelApsSorted = liveRssiMap.entries
            .filter { (bssid, _) -> allFixedApBssidsInModel.contains(bssid) }
            .sortedByDescending { (_, rssi) -> rssi }
            .map { it.key } // Get only the BSSID_Prime strings

        if (observedModelApsSorted.isEmpty()) {
            Log.w(TAG, "BatchedParallel: No observed APs from the live scan are part of the fixed model. Returning uniform priors.")
            return currentPriors // Or emptyMap() if preferred when no evidence
        }

        Log.d(TAG, "BatchedParallel: Processing ${observedModelApsSorted.size} observed model APs in batches of $PARALLEL_BATCH_SIZE. Cutoff: $cutoffProbability")

        // 3. Iterate in batches
        val batches = observedModelApsSorted.chunked(PARALLEL_BATCH_SIZE)
        var batchNumber = 0

        for (apBatch in batches) {
            batchNumber++
            Log.d(TAG, "BatchedParallel: Processing Batch #$batchNumber: ${apBatch.joinToString()}")
            val batchPosteriorsUnnormalized = mutableMapOf<String, Double>()

            for (cell in allCells) {
                var batchLikelihoodProductForCell = 1.0 // L_batch,cell

                for (bssidPrimeInBatch in apBatch) {
                    val observedRssi = liveRssiMap[bssidPrimeInBatch]
                        ?: continue // Should not happen due to filtering, but safety

                    val apPmfForCell = pmfsByBssid[bssidPrimeInBatch]?.find { it.cell == cell }
                    val likelihood = getLikelihood(bssidPrimeInBatch, cell, observedRssi, apPmfForCell)
                    // getLikelihood already applies LIKELIHOOD_FLOOR
                    batchLikelihoodProductForCell *= likelihood
                }
                // P_new(Cell) = L_batch,cell * P_old(Cell)
                batchPosteriorsUnnormalized[cell] = batchLikelihoodProductForCell * (currentPriors[cell] ?: (1.0/numCells))
            }

            currentPriors = normalizeProbabilities(batchPosteriorsUnnormalized).toMutableMap()
            Log.d(TAG, "BatchedParallel - After Batch #$batchNumber: Posteriors: ${currentPriors.entries.joinToString { "${it.key}: %.3f".format(it.value) }}")

            // 4. Check Cutoff
            val highestProb = currentPriors.values.maxOrNull() ?: 0.0
            if (highestProb >= cutoffProbability) {
                Log.i(TAG, "BatchedParallel - Cutoff reached after Batch #$batchNumber! Highest probability ($highestProb) >= cutoff ($cutoffProbability). Stopping.")
                return currentPriors // Return early
            }
        }

        Log.i(TAG, "BatchedParallel - All ${batches.size} batches processed. Cutoff not reached.")
        // Optional: If you want to incorporate a factor for model APs NOT seen in the live scan *after* all batches:
        // This would be a final update to currentPriors.
        // For now, sticking to the description of processing only seen APs in batches.

        return currentPriors
    }
    private fun calculateSerialBayesian(
        liveRssiMap: Map<String, Int>,
        pmfsByBssid: Map<String, List<ApPmf>>,
        allCells: List<String>,
        allFixedApBssidsInModel: Set<String>,
        cutoffProbability: Double
    ): Map<String, Double> {
        val numCells = allCells.size
        if (numCells == 0) return emptyMap()

        // Initialize priors: uniform distribution over all possible cells
        var currentPriors = allCells.associateWith { 1.0 / numCells }.toMutableMap()

        // Determine the order of AP processing: typically, stronger signals first
        // Filter allFixedApBssidsInModel to only those present in liveRssiMap for meaningful sorting,
        // or process all and use DEFAULT_RSSI for those not in liveRssiMap.
        // The current approach processes all fixed APs in the model.
        val sortedFixedApBssidsToProcess = allFixedApBssidsInModel.toList()
            .sortedByDescending { bssid -> liveRssiMap.getOrDefault(bssid, DEFAULT_RSSI) } // Strongest first

        Log.d(TAG, "Serial Bayesian: Starting with ${sortedFixedApBssidsToProcess.size} APs in model. Cutoff Prob: $cutoffProbability, Min Iterations: $MIN_ITERATIONS_BEFORE_SERIAL_CUTOFF")

        var iterationCount = 0 // Counter for processed APs

        for (bssidPrime in sortedFixedApBssidsToProcess) {
            iterationCount++ // Increment for each AP processed

            val observedRssi = liveRssiMap.getOrDefault(bssidPrime, DEFAULT_RSSI)
            val nextPosteriorsUnnormalized = mutableMapOf<String, Double>()

            var sumLikelihoodsForThisAp = 0.0

            for (cell in allCells) {
                val apPmfForCell = pmfsByBssid[bssidPrime]?.find { it.cell == cell }
                var likelihood = getLikelihood(bssidPrime, cell, observedRssi, apPmfForCell)

                // Use a small epsilon if likelihood is zero to prevent probabilities from becoming permanently zero too early.
                if (likelihood == 0.0) {
                    likelihood = LIKELIHOOD_FOR_ZERO // Small epsilon
                }
                sumLikelihoodsForThisAp += likelihood // For sanity check, not directly used in Bayes update here

                // P(Cell | E_k) proportional to P(E_k | Cell) * P(Cell | E_{k-1})
                // where E_k is the evidence from the k-th AP.
                // P(Cell | E_{k-1}) is currentPriors[cell]
                nextPosteriorsUnnormalized[cell] = likelihood * (currentPriors[cell] ?: (1.0 / numCells)) // Safety for missing prior
            }

            // If all likelihoods for this AP across all cells were effectively zero (e.g. LIKELIHOOD_FOR_MISSING_AP_MODEL),
            // then nextPosteriorsUnnormalized might be very small or zero. Normalization handles this.
            // If sumLikelihoodsForThisAp is very low, it means this AP provides little discriminatory info or wasn't well modeled.
            if (sumLikelihoodsForThisAp < 1e-9 && iterationCount > 1) { // Avoid logging for the very first AP if it's uninformative
                Log.w(TAG, "Serial - AP $bssidPrime (RSSI $observedRssi) provided very low likelihoods across all cells. This AP might not be discriminative.")
            }


            currentPriors = normalizeProbabilities(nextPosteriorsUnnormalized).toMutableMap()
            Log.d(TAG, "Serial - Iteration $iterationCount/$MIN_ITERATIONS_BEFORE_SERIAL_CUTOFF (AP $bssidPrime, RSSI $observedRssi): Posteriors: ${currentPriors.entries.joinToString { "${it.key}: %.3f".format(it.value) }}")

            // --- MODIFIED Cutoff Logic ---
            // Check cutoff only after a minimum number of iterations (APs processed)
            if (iterationCount >= MIN_ITERATIONS_BEFORE_SERIAL_CUTOFF) {
                val highestProb = currentPriors.values.maxOrNull() ?: 0.0
                if (highestProb >= cutoffProbability) {
                    Log.i(TAG, "Serial - Cutoff reached at iteration $iterationCount! Highest probability ($highestProb) >= cutoff ($cutoffProbability). Stopping.")
                    return currentPriors // Return early
                }
            }
        }
        Log.i(TAG, "Serial - All ${sortedFixedApBssidsToProcess.size} APs processed. Cutoff not reached or min iterations not met for cutoff.")
        return currentPriors // Return final probabilities after processing all APs
    }


    /**
     * Normalizes a map of probabilities so that they sum to 1.0.
     * Handles cases where the sum is zero or the map is empty.
     *
     * @param probs A map of cell labels to their unnormalized probabilities.
     * @return A map of cell labels to normalized probabilities.
     */
    private fun normalizeProbabilities(probs: Map<String, Double>): Map<String, Double> {
        if (probs.isEmpty()) {
            return emptyMap()
        }
        val sum = probs.values.sum()

        return if (sum == 0.0) {
            // If sum is zero (e.g., all likelihoods were zero), assign uniform probability.
            // This indicates no evidence, so all cells are equally (un)likely.
            Log.w(TAG, "Sum of probabilities is zero during normalization. Assigning uniform distribution.")
            val equalProb = 1.0 / probs.size
            probs.mapValues { equalProb }
        } else {
            // Standard normalization
            probs.mapValues { it.value / sum }
        }
    }
}