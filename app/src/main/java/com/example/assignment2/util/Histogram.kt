package com.example.assignment2.util

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.abs

/**
 * Represents a histogram of RSSI values.
 *
 * @property measurements The list of RSSI values to build the histogram from.
 * @property binWidth The width of each bin in the histogram.
 * @property minRssi The minimum RSSI value considered for the histogram range.
 * Values below this might be ignored or clamped depending on implementation.
 * @property maxRssi The maximum RSSI value considered for the histogram range.
 * Values above this might be ignored or clamped.
 */
class Histogram(
    measurements: List<Int>,
    val binWidth: Int,
    private val minRssi: Int = -100, // Default typical RSSI min
    private val maxRssi: Int = 0     // Default typical RSSI max
) {
    val bins: Map<Int, Int>
    val totalCount: Int

    init {
        if (binWidth <= 0) {
            throw IllegalArgumentException("Bin width must be positive.")
        }

        val initializedBins = mutableMapOf<Int, Int>()
        var currentBinStartRssi = minRssi
        while (currentBinStartRssi <= maxRssi) {
            initializedBins[currentBinStartRssi] = 0
            if (binWidth == 0) break
            currentBinStartRssi += binWidth
        }

        var calculatedTotalCount = 0
        for (rssi in measurements) {
            if (rssi < minRssi || rssi > maxRssi) {
                continue
            }
            val binStartValue = minRssi + floor((rssi - minRssi).toDouble() / binWidth).toInt() * binWidth
            if (initializedBins.containsKey(binStartValue)) {
                initializedBins[binStartValue] = (initializedBins[binStartValue] ?: 0) + 1
                calculatedTotalCount++
            }
        }
        this.bins = initializedBins.toMap()
        this.totalCount = calculatedTotalCount
    }

    /**
     * Generates a 1D discrete Gaussian kernel.
     * The kernel is normalized to sum to 1.
     *
     * @param sigma Standard deviation of the Gaussian.
     * @param binWidth The width of histogram bins (used to map sigma to discrete space).
     * @param kernelRadiusMultiplier Determines how many sigmas wide the kernel should be.
     * @return A DoubleArray representing the discrete Gaussian kernel.
     */
    private fun generateDiscreteGaussianKernel(sigma: Double, binWidth: Int, kernelRadiusMultiplier: Int): DoubleArray {
        if (sigma <= 0 || binWidth <= 0) return doubleArrayOf(1.0) // Fallback for invalid inputs

        // Calculate radius in terms of number of bins
        val radiusInBins = ceil(kernelRadiusMultiplier * sigma / binWidth).toInt()
        val kernelSize = 2 * radiusInBins + 1
        val kernel = DoubleArray(kernelSize)
        var sum = 0.0

        for (i in 0 until kernelSize) {
            // Distance from the center of the kernel in terms of actual RSSI units
            val distance = (i - radiusInBins) * binWidth.toDouble()
            // Gaussian function, omitting constant factor 1/(sigma*sqrt(2*PI)) as we normalize by sum later
            val value = exp(-0.5 * (distance / sigma).pow(2))
            kernel[i] = value
            sum += value
        }

        // Normalize the kernel so that its elements sum to 1.0
        if (sum > 1e-9) { // Avoid division by zero or tiny numbers
            for (i in kernel.indices) {
                kernel[i] /= sum
            }
        } else if (kernelSize > 0) {
            // If sum is negligible (e.g., sigma extremely small relative to binWidth),
            // make it a delta function at the center.
            kernel.fill(0.0) // CORRECTED LINE: Set all elements to 0.0
            if (radiusInBins >= 0 && radiusInBins < kernel.size) { // Ensure center index is valid
                kernel[radiusInBins] = 1.0 // Center element
            } else if (kernel.isNotEmpty()){ // Fallback if radiusInBins is somehow out of bounds but kernel exists
                kernel[kernel.size / 2] = 1.0
            }
        }
        return kernel
    }

    /**
     * Applies Gaussian smoothing to the histogram counts using 1D convolution.
     * @param sigma Standard deviation for the Gaussian kernel.
     * @param kernelRadiusMultiplier Multiplier for sigma to determine the kernel's effective radius.
     * @return A map of bin starts to smoothed (and rounded) integer counts.
     * The structure attempts to preserve the original total count approximately.
     */
    fun getGaussianSmoothedCounts(sigma: Double, kernelRadiusMultiplier: Int = 3): Map<Int, Int> {
        if (bins.isEmpty() || sigma <= 0) {
            return bins // Return original if no data or invalid sigma
        }
        if (totalCount == 0) {
            return bins.mapValues { 0 } // All bin counts are zero
        }

        val kernel = generateDiscreteGaussianKernel(sigma, this.binWidth, kernelRadiusMultiplier)
        val kernelCenterOffset = kernel.size / 2

        val allBinStarts = this.bins.keys.sorted()
        val histogramValues = allBinStarts.map { this.bins[it] ?: 0 }.toIntArray()

        val smoothedValuesDouble = DoubleArray(histogramValues.size)

        // Perform 1D convolution
        for (i in histogramValues.indices) {
            var convolvedValue = 0.0
            for (k in kernel.indices) {
                val histogramAccessIndex = i - (kernelCenterOffset - k)

                if (histogramAccessIndex >= 0 && histogramAccessIndex < histogramValues.size) {
                    convolvedValue += histogramValues[histogramAccessIndex].toDouble() * kernel[k]
                }
            }
            smoothedValuesDouble[i] = convolvedValue
        }

        val sumOfSmoothedDoubles = smoothedValuesDouble.sum()
        val finalSmoothedIntCounts = mutableMapOf<Int, Int>()

        if (sumOfSmoothedDoubles > 1e-9) {
            val scaleFactor = this.totalCount.toDouble() / sumOfSmoothedDoubles
            for (i in allBinStarts.indices) {
                finalSmoothedIntCounts[allBinStarts[i]] = (smoothedValuesDouble[i] * scaleFactor).roundToInt()
            }
        } else {
            for (binStart in allBinStarts) {
                finalSmoothedIntCounts[binStart] = 0
            }
        }
        return finalSmoothedIntCounts
    }

    fun getCountForRssi(rssiValue: Int): Int {
        if (rssiValue < minRssi || rssiValue > maxRssi) {
            return 0
        }
        val binStartValue = minRssi + floor((rssiValue - minRssi).toDouble() / binWidth).toInt() * binWidth
        return bins[binStartValue] ?: 0
    }

    fun getPmf(): Map<Int, Double> {
        if (totalCount == 0) {
            return bins.mapValues { 0.0 }
        }
        return bins.mapValues { (_, count) ->
            count.toDouble() / totalCount
        }
    }

    override fun toString(): String {
        return "Histogram(binWidth=$binWidth, totalCount=$totalCount, bins=$bins)"
    }

    fun getApproximateAverageRssi(): Double {
        if (totalCount == 0) {
            return minRssi.toDouble()
        }
        val pmf = getPmf()
        var averageRssi = 0.0
        pmf.forEach { (binStart, probability) ->
            val binCenter = binStart + (binWidth / 2.0)
            averageRssi += binCenter * probability
        }
        return averageRssi
    }
}