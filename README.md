# SPS-Bayesian-Localization

An Android application that uses WiFi signal fingerprinting and Bayesian machine learning to determine indoor location with high accuracy. The app collects WiFi signal strength measurements, builds probabilistic models, and performs real-time location prediction across predefined indoor cells.

## 🎯 **Project Overview**

This research-grade indoor localization system demonstrates how WiFi signal fingerprinting can achieve room-level accuracy using statistical inference. The app implements a complete pipeline from data collection to location prediction, making it ideal for studying indoor positioning systems.

### **Key Features**
- **Real-time Location Prediction** - Predict your location among 10 predefined cells (C1-C10)
- **Training Data Collection** - Systematic WiFi fingerprint collection with batch mode
- **Bayesian Machine Learning** - Two prediction modes (Serial/Parallel) with configurable parameters
- **Data Visualization** - Histograms and probability mass functions for signal analysis
- **Algorithm Testing** - Validation suite with confusion matrices and accuracy metrics
- **Data Management** - Export/import database functionality with JSON format

## 📱 **App Architecture**

### **Navigation Structure**
```
Main Activity (Navigation Drawer)
├── Home - Location prediction interface
├── WiFi Scan - Training data collection
├── Database View - Data management
├── Histogram View - Signal distribution visualization
├── PMF View - Probability mass function analysis
└── Bayesian Testing - Algorithm validation
```

### **Technical Stack**
- **Language**: Kotlin
- **Architecture**: MVVM with Repository Pattern
- **Database**: Room (SQLite) with comprehensive migration system
- **UI**: Material Design with Navigation Drawer
- **Permissions**: Runtime permission handling for location services
- **Machine Learning**: Custom Bayesian inference implementation

## 🗃️ **Database Schema**

### **Core Tables**
- **`KnownAp`** - Individual WiFi access points
- **`KnownApPrime`** - Aggregated AP representations (handles multiple radios)
- **`MeasurementTime`** - Scan session metadata (timestamp, location, type)
- **`ApMeasurement`** - Signal strength readings (RSSI values)
- **`ApPmf`** - Probability mass functions for Bayesian inference
- **`OuiManufacturer`** - MAC address manufacturer lookup

### **Data Relationships**
```
MeasurementTime (1) ←→ (N) ApMeasurement ←→ (1) KnownApPrime
                                                      ↓
                                             ApPmf (histogram data)
```

## 🚀 **Getting Started**

### **Prerequisites**
- Android Studio Arctic Fox or later
- Android device with API level 21+ (Android 5.0)
- WiFi capability
- Location permissions

### **Installation**
1. Clone the repository:
   ```bash
   git clone https://github.com/Hashim-K/SPS-Bayesian-Localization
   ```

2. Open in Android Studio

3. Build and install on your Android device

4. Grant required permissions:
   - Location access (Fine location)
   - WiFi state access
   - Nearby devices (Android 12+)

### **Initial Setup**
1. **Data Collection Phase**:
   - Navigate to "WiFi Scan" tab
   - Select a cell (C1-C10) representing your current location
   - Choose "Training" mode
   - Use "Batch Mode" to collect multiple samples automatically
   - Repeat for all locations you want to map

2. **Location Prediction**:
   - Navigate to "Home" tab
   - Click "Guess Location"
   - The app will predict your current cell based on WiFi signals

## 📊 **How It Works**

### **WiFi Fingerprinting Process**
1. **Signal Collection**: Scan WiFi networks and record RSSI values
2. **BSSID Aggregation**: Group multiple radios from same device using "BSSID Prime"
3. **Histogram Generation**: Build probability distributions for each AP at each location
4. **Bayesian Inference**: Use Bayes' theorem to predict location from current WiFi scan

### **BSSID Prime Algorithm**
Converts `AA:BB:CC:DD:EE:FF` → `AABBCCDDEEFX` to aggregate multiple radios (2.4GHz, 5GHz) from the same router.

### **Prediction Modes**
- **Serial Mode**: Process APs sequentially until confidence threshold is reached
- **Parallel Mode**: Process all APs simultaneously and combine probabilities

## ⚙️ **Configuration Options**

### **Bayesian Settings** (accessible via Settings dialog)
- **Prediction Mode**: Serial vs Parallel processing
- **PMF Bin Width**: Histogram resolution (1-20)
- **Serial Cutoff Probability**: Confidence threshold (0.01-1.0)
- **Number of Scans for Averaging**: Multi-scan averaging (1-10)

### **Data Collection Settings**
- **Measurement Type**: Training vs Testing data
- **Batch Mode**: Automated collection with configurable intervals
- **Sample Targets**: Set desired number of samples per location

## 📈 **Data Analysis Features**

### **Visualization Tools**
- **Histogram View**: RSSI distribution per AP per cell
- **PMF View**: Probability mass functions used in prediction
- **Real-time Accuracy**: Live prediction confidence scores

### **Testing & Validation**
- **Algorithm Testing**: Validate prediction accuracy against test data
- **Confusion Matrix**: Detailed accuracy breakdown per cell
- **Export Results**: JSON export for further analysis

### **Database Management**
- **Export Database**: Save collected data as JSON
- **Restore Database**: Import previously collected data
- **Selective Wipe**: Clear user data while preserving manufacturer data

## 🔬 **Research Applications**

This app is designed for:
- **Indoor Positioning Research**: Study WiFi-based localization accuracy
- **Algorithm Development**: Test different machine learning approaches
- **Signal Analysis**: Understand WiFi propagation patterns indoors
- **Comparative Studies**: Benchmark against other positioning methods

## 🛠️ **Technical Details**

### **Key Algorithms**
```kotlin
// Bayesian inference formula
P(Location|WiFi_Signals) ∝ P(WiFi_Signals|Location) × P(Location)

// BSSID Prime calculation
fun calculateBssidPrime(bssid: String): String {
    return bssid.replace(":", "").substring(0, 11) + "0"
}
```

### **Database Migrations**
The app includes comprehensive migration system (V4→V13) preserving user data across schema changes.

### **Permission Handling**
- Runtime permission requests with user-friendly explanations
- Graceful degradation when permissions are denied
- Clear error messages for troubleshooting

## 📱 **Usage Examples**

### **Collecting Training Data**
1. Stand in location C1
2. Select "C1" button in WiFi Scan tab
3. Ensure "Training" mode is selected
4. Click "Batch Mode" and enter desired sample count (e.g., 50)
5. App automatically collects samples with progress feedback

### **Testing Accuracy**
1. Collect training data for multiple cells
2. Switch to "Testing" mode
3. Collect test samples from known locations
4. Use "Bayesian Testing" tab to run validation
5. Review confusion matrix and accuracy metrics

## 🤝 **Contributing**

This is an academic project for the TU Delft CESE4120 course.

## 📄 **License**

This project is created for educational purposes as part of the TU Delft Computer Engineering & Embedded Systems curriculum.

## 🔍 **Troubleshooting**

### **Common Issues**
- **No WiFi networks detected**: Ensure location permissions are granted
- **Low prediction accuracy**: Collect more training samples per location
- **App crashes during scan**: Check Android WiFi throttling settings
- **Missing scan results**: Verify nearby device permissions on Android 12+

### **Performance Tips**
- Use batch mode for efficient data collection
- Adjust PMF bin width based on signal stability
- Collect samples at different times for robustness
- Use averaging mode for more stable predictions

## 📚 **Academic Context**

This application demonstrates practical implementation of:
- **Machine Learning**: Bayesian inference for classification
- **Signal Processing**: WiFi RSSI analysis and filtering
- **Mobile Computing**: Android sensor integration
- **Database Design**: Efficient storage of time-series data
- **Software Engineering**: Clean architecture and testing practices

---

**Course**: CESE4120 - TU Delft  
**Academic Year**: 2024-2025  
**Focus**: Indoor Localization using WiFi Fingerprinting
