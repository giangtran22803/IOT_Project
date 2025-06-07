package com.example.iotproject

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable



@Serializable
data class TelemetryDataPoint(
    @SerialName("ts") val timestamp: Long,
    @SerialName("value") val value: String // Value is often a String, needs to be parsed to Double/Int
)

typealias TelemetryResponse = Map<String, List<TelemetryDataPoint>>

data class SensorUiState(
    val temperature: Double? = null,
    val humidity: Double? = null,
    val soilMoisture: Double? = null, // Added for completeness based on your Composable
    val light: Double? = null,      // Added for completeness based on your Composable
    val isLoading: Boolean = false,
    val error: String? = null
)

class SensorViewModel : ViewModel() {

    private val apiService = ApiClient.instance

    // 2. Create a MutableStateFlow for the UI state
    private val _uiState = MutableStateFlow(SensorUiState())
    // 3. Expose it as an immutable StateFlow
    val uiState: StateFlow<SensorUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    private val telemetryKeysToFetch = listOf("temperature", "humidity", "soilMoisture", "light")


    fun startFetchingPeriodically(intervalMs: Long = 5000L) {
        stopFetching()
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                // Pass the deviceId and keys to fetchDeviceTelemetry
                fetchDeviceTelemetry(deviceId = currentdeviceId, telemetryKeys = telemetryKeysToFetch, authToken = authToken)
                delay(intervalMs)
            }
            Log.i("SensorViewModel", "$currentdeviceId")
        }
    }

    fun fetchDeviceTelemetry(deviceId: String, telemetryKeys: List<String>, authToken: String) {
        _uiState.update { it.copy(isLoading = true, error = null) }

        if (authToken.startsWith("YOUR_") || authToken.isBlank()) {
            Log.e("SensorViewModel", "Auth token is a placeholder or empty.")
            _uiState.update { it.copy(isLoading = false, error = "Authentication token is missing.") }
            return
        }
        if (deviceId.isBlank() || deviceId.startsWith("YOUR_")) {
            Log.e("SensorViewModel", "Device ID is a placeholder or empty.")
            _uiState.update { it.copy(isLoading = false, error = "Device ID is missing.") }
            return
        }


        viewModelScope.launch {
            try {
                val keysString = telemetryKeys.joinToString(",")
                val response = apiService.getTelemetryData(
                    authToken = authToken,
                    entityType = "DEVICE",
                    entityId = deviceId,
                    keys = keysString,
                    useStrictDataTypes = false
                )

                if (response.isSuccessful) {
                    val telemetryData = response.body()
                    if (telemetryData != null) {
                        // 4. Update the UI state with the fetched data
                        _uiState.update { currentState ->
                            currentState.copy(
                                temperature = telemetryData["temperature"]?.firstOrNull()?.value?.toDoubleOrNull() ?: currentState.temperature,
                                humidity = telemetryData["humidity"]?.firstOrNull()?.value?.toDoubleOrNull() ?: currentState.humidity,
                                soilMoisture = telemetryData["soilMoisture"]?.firstOrNull()?.value?.toDoubleOrNull() ?: currentState.soilMoisture,
                                light = telemetryData["light"]?.firstOrNull()?.value?.toDoubleOrNull() ?: currentState.light,
                                isLoading = false
                            )
                        }
                        Log.i("SensorViewModel", "Telemetry data received and UI updated for device $deviceId: $telemetryData")
                    } else {
                        _uiState.update { it.copy(isLoading = false, error = "Telemetry response body is null") }
                        Log.w("SensorViewModel", "Telemetry response body is null for device $deviceId.")
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    _uiState.update { it.copy(isLoading = false, error = "Error fetching telemetry: ${response.code()} - ${response.message()}") }
                    Log.e("SensorViewModel", "Error fetching telemetry for device $deviceId: ${response.code()} - ${response.message()}. Body: $errorBody")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "Exception fetching telemetry: ${e.message}") }
                Log.e("SensorViewModel", "Exception fetching telemetry for device $deviceId", e)
            }
        }
    }

    fun stopFetching() {
        pollingJob?.cancel()
        pollingJob = null
        Log.d("SensorViewModel", "Fetching stopped.")
    }

    override fun onCleared() {
        super.onCleared()
        stopFetching()
    }
}

@Composable
fun BoxComponent(name1: String, name2: String, modifier: Modifier = Modifier, value1: Any?, value2: Any?, image1: Painter, image2: Painter, color1: Color, color2: Color, unit1 : String = "", unit2 : String = "") {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    )
    {
        Box(
            modifier = Modifier
                .width(180.dp)
                .fillMaxHeight()
                .padding(5.dp)
                .background(Color.White, shape = RoundedCornerShape(16.dp))
                .border(2.dp, Color.Black, shape = RoundedCornerShape(16.dp))
        ){
            Box (
                modifier = Modifier
                    .fillMaxWidth()
                    .height(27.dp)
                    .background(
                        color = color1,
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
            ){
                Text(
                    text = "$name1",
                    modifier = modifier
                        .align(Alignment.TopCenter)
                        .fillMaxHeight()
                        .padding(3.dp),
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
            HorizontalDivider(
                color = Color.Black,
                thickness = 2.dp,
                modifier = Modifier.padding(vertical = 27.dp)
            )
            Image(
                painter = image1,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(50.dp),
                contentDescription = "Temperature",
            )
            Text (
                text = "$value1 $unit1",
                fontSize = 15.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp),
                color = Color.Black,
            )
        }
        Box(
            modifier = Modifier
                .width(180.dp)
                .fillMaxHeight()
                .padding(5.dp)
                .background(Color.White, shape = RoundedCornerShape(16.dp))
                .border(2.dp, Color.Black, shape = RoundedCornerShape(16.dp))
        ){
            Box (
                modifier = Modifier
                    .fillMaxWidth()
                    .height(27.dp)
                    .background(
                        color = color2,
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
            ){
                Text(
                    text = "$name2",
                    modifier = modifier
                        .align(Alignment.TopCenter)
                        .fillMaxHeight()
                        .padding(3.dp),
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
            HorizontalDivider(
                color = Color.Black,
                thickness = 2.dp,
                modifier = Modifier.padding(vertical = 27.dp)
            )
            Image(
                painter = image2,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(50.dp),
                contentDescription = "Humidity",
            )
            Text (
                text = "$value2 $unit2",
                fontSize = 15.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp),
                color = Color.Black,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview(showBackground = true)
fun SensorViewScreen(sensorViewModel: SensorViewModel = viewModel()) {
    val uiState by sensorViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        sensorViewModel.startFetchingPeriodically()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = "View Sensor", color = Color.White)
                },
                modifier = Modifier.height(60.dp),
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF6200EE), // Purple color for the app bar background
                    titleContentColor = Color.White, // Color for the title text
                    navigationIconContentColor = Color.White, // Color for navigation icon (if any)
                    actionIconContentColor = Color.White // Color for action icons (if any)
                )
            )
        }
    )
    { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(innerPadding)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(Color.White)
                    .border(2.dp, Color.Black, shape = RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("${currentdeviceName}", modifier = Modifier.padding(5.dp), fontSize = 20.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            }
            BoxComponent(name1 = "Temperature", name2 = "Humidity", value1 = uiState.temperature, value2 = uiState.humidity,image1 = painterResource(id = R.drawable.temperature), image2 = painterResource(id = R.drawable.humidity), color1 = Color.Red, color2 = Color(0xFF00BFFF), unit1 = "°C", unit2 = "%")
            BoxComponent(name1 = "Soil Moiture", name2 = "Light", value1 = uiState.soilMoisture, value2 = uiState.light,image1 = painterResource(id = R.drawable.soilmoisture), image2 = painterResource(id = R.drawable.light), color1 = Color(0xFF8B4513), color2 = Color.Yellow, unit1 = "%", unit2 = "lux")
        }
    }
}