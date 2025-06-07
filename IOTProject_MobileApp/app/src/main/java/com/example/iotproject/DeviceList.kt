package com.example.iotproject

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.error
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import okhttp3.MediaType
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import retrofit2.http.Path
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.io.IOException
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.serialization.json.JsonElement // Import this
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

var authToken = "YOUR_BEARER_TOKEN_HERE" // e.g., "Bearer eyJhbGc..."
var currentdeviceId = ""
var currentdeviceName = ""


@Serializable
data class AttributeData(
    @SerialName("key") val key: String,
    @SerialName("value") val value: JsonElement // The value can be any valid JSON type
)

@Serializable
data class DeviceEntityId(
    @SerialName("id") val id: String, // The actual device ID string
    @SerialName("entityType") val entityType: String? = null // Optional, but good to include
)

@Serializable
data class DeviceListItem(
    @SerialName("id") val id: DeviceEntityId, // Maps to the nested "id" object
    @SerialName("name") val name: String,
)

@Serializable
data class DeviceListResponse(
    @SerialName("data") val devices: List<DeviceListItem>
)

// A simpler data class to hold only what you need in the ViewModel
data class DeviceIdentifier(
    val id: String,
    val name: String,
    var isActive: Boolean? = null
)


interface ThingsBoardApiService {

    @GET("api/user/devices")
    suspend fun getUserDevices(
        @Header("X-Authorization") authToken: String, // Added this line
        @Query("pageSize") pageSize: Int = 20,         // Default page size
        @Query("page") page: Int = 0,                  // Default page (0-indexed)
        @Query("type") type: String? = null,
        @Query("textSearch") textSearch: String? = null,
        @Query("sortProperty") sortProperty: String? = null, // e.g., "createdTime", "name"
        @Query("sortOrder") sortOrder: String? = null       // e.g., "ASC", "DESC"
    ): Response<DeviceListResponse> // Using Response for full HTTP details

    @GET("api/plugins/telemetry/{entityType}/{entityId}/values/timeseries")
    suspend fun getTelemetryData(
        @Header("X-Authorization") authToken: String, // Authorization token
        @Path("entityType") entityType: String,       // e.g., "DEVICE"
        @Path("entityId") entityId: String,           // The UUID of the device
        @Query("keys") keys: String,                   // Comma-separated list of keys (e.g., "temperature,humidity")
        @Query("useStrictDataTypes") useStrictDataTypes: Boolean? = false // Optional, defaults to false
    ): Response<TelemetryResponse>


    @GET("api/plugins/telemetry/{entityType}/{entityId}/values/attributes")
    suspend fun getAttributeData(
        @Header("X-Authorization") authToken: String, // Authorization token
        @Path("entityType") entityType: String,       // e.g., "DEVICE"
        @Path("entityId") entityId: String,           // The UUID of the device
        @Query("keys") keys: String,                   // Comma-separated list of keys (e.g., "temperature,humidity")
    ): Response<List<AttributeData>>
}

// --- Retrofit Client (Example - adapt to your existing Retrofit setup) ---
object ApiClient {
    private const val BASE_URL = "https://app.coreiot.io/"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Try the static parse method which is more fundamental
    @OptIn(ExperimentalFoundationApi::class)
    private val contentType = "application/json".toMediaTypeOrNull()

    val instance: ThingsBoardApiService by lazy {
        if (contentType == null) { // MediaType.parse can return null
            throw IllegalStateException("'application/json' could not be parsed to MediaType.")
        }
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
        retrofit.create(ThingsBoardApiService::class.java)
    }
}


class DeviceListViewModel : ViewModel() {

    private val apiService = ApiClient.instance // Get your Retrofit service instance

    private val _devices = MutableLiveData<List<DeviceIdentifier>>()
    val devices: LiveData<List<DeviceIdentifier>> = _devices

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _isLoadingAttributes = MutableLiveData<Boolean>()
    val isLoadingAttributes: LiveData<Boolean> = _isLoadingAttributes

    private val _errorAttributes = MutableLiveData<String?>()
    val errorAttributes: LiveData<String?> = _errorAttributes

    fun fetchUserDevices(
        authToken: String = com.example.iotproject.authToken,
        pageSize: Int = 20,
        page: Int = 0,
        type: String? = null,
        textSearch: String? = null
    ) {
        _isLoading.value = true
        _error.value = null

        // Ensure the token is not empty or a placeholder before making the call
        if (authToken == "YOUR_BEARER_TOKEN_HERE" || authToken.isBlank()) {
            _error.postValue("Authentication token is missing or invalid.")
            _isLoading.postValue(false)
            Log.e("DeviceListViewModel", "Auth token is a placeholder or empty.")
            return
        }

        viewModelScope.launch {
            try {
                val response = apiService.getUserDevices(
                    authToken = authToken,
                    pageSize = pageSize,
                    page = page,
                    type = type,
                    textSearch = textSearch
                )

                Log.i("deviceListResponse", "$authToken")

                if (response.isSuccessful) {
                    val deviceListResponse = response.body()
                    if (deviceListResponse != null) {
                        val identifiers = deviceListResponse.devices.map { deviceListItem ->
                            DeviceIdentifier(
                                id = deviceListItem.id.id,
                                name = deviceListItem.name
                            )
                        }
                        Log.i("deviceListResponse", "not null")
                        _devices.postValue(identifiers)
                        fetchActiveAttributesForAllDevices(identifiers)
                    } else {
                        Log.i("deviceListResponse", "null")
                        _error.postValue("Response body is null")
                    }
                } else {
                    Log.i("deviceListResponse", "not successful")
                    _error.postValue("Error: ${response.code()} - ${response.message()}")
                }
            } catch (e: IOException) {
                _error.postValue("Network Error: ${e.message}")
                Log.i("deviceListResponse", "network error")
            } catch (e: Exception) { // For other errors (like JSON parsing issues if not handled by Retrofit)
                _error.postValue("An unexpected error occurred: ${e.message}")
                Log.e("DeviceListViewModel", "An unexpected error occurred", e)
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    private fun fetchActiveAttributesForAllDevices(deviceIdentifiersFromFetchUser: List<DeviceIdentifier>) {
        if (authToken == "YOUR_BEARER_TOKEN_HERE" || authToken.isBlank()) {
            _errorAttributes.postValue("Cannot fetch attributes: Auth token missing.")
            _isLoadingAttributes.postValue(false) // Also set loading to false
            return
        }

        // If there are no identifiers to process, no need to proceed.
        if (deviceIdentifiersFromFetchUser.isEmpty()) {
            Log.d("AttributesFetch", "No device identifiers provided to fetch attributes for.")
            _isLoadingAttributes.postValue(false)
            _errorAttributes.postValue(null) // Clear any previous error
            // Do NOT post to _devices here, as fetchUserDevices would have already set it (possibly to empty)
            return
        }

        _isLoadingAttributes.postValue(true)
        _errorAttributes.postValue(null)

        viewModelScope.launch {
            Log.d("AttributesFetch", "Coroutine started. Processing ${deviceIdentifiersFromFetchUser.size} devices for attributes.")


            val currentDeviceMap = (_devices.value ?: emptyList())
                .associateBy { it.id }
                .toMutableMap()

            // It's possible that deviceIdentifiersFromFetchUser contains devices not yet in _devices.value
            // (e.g., if fetchUserDevices posted, then immediately called this, and LiveData emission is slightly delayed).
            // Ensure all devices from deviceIdentifiersFromFetchUser are present in our working map.
            // The items from deviceIdentifiersFromFetchUser are the "base" items, potentially without 'isActive' yet.
            deviceIdentifiersFromFetchUser.forEach { deviceFromParam ->
                if (!currentDeviceMap.containsKey(deviceFromParam.id)) {
                    // This device was in the list passed from fetchUserDevices but not yet in _devices.value
                    // or was lost. Add it to our working map.
                    Log.w("AttributesFetch", "Device ${deviceFromParam.name} (${deviceFromParam.id}) from parameters not found in current _devices.value. Adding it.")
                    currentDeviceMap[deviceFromParam.id] = deviceFromParam // It will have isActive=null initially
                }
            }

            Log.d("AttributesFetch", "Initial working map size: ${currentDeviceMap.size}. _devices.value had: ${_devices.value?.size ?: "null"} items.")


            var anErrorOccurred = false

            // Process only the devices that were passed in the parameter
            deviceIdentifiersFromFetchUser.forEach { deviceToProcess ->
                try {
                    Log.d("AttributesFetch", "Fetching 'active' for ${deviceToProcess.name} (${deviceToProcess.id})")
                    val attributeResponse = apiService.getAttributeData(
                        authToken = authToken,
                        entityType = "DEVICE",
                        entityId = deviceToProcess.id,
                        keys = "active"
                    )

                    var finalIsActiveStatus: Boolean? = null // Default to unknown/null

                    if (attributeResponse.isSuccessful) {
                        val attributes = attributeResponse.body()
                        if (attributes != null && attributes.isNotEmpty()) {
                            val activeAttribute = attributes.find { it.key == "active" }
                            // If activeAttribute is null, or its value is not boolean, booleanOrNull returns null.
                            // If you want to default to false if attribute is missing or not boolean:
                            // finalIsActiveStatus = (activeAttribute?.value as? JsonPrimitive)?.booleanOrNull ?: false
                            finalIsActiveStatus = (activeAttribute?.value as? JsonPrimitive)?.booleanOrNull
                            Log.d("AttributesFetch", "Device ${deviceToProcess.name} raw active status: $finalIsActiveStatus (from API)")
                        } else {
                            Log.w("AttributesFetch", "Attribute response body is null or empty for ${deviceToProcess.name}. Setting isActive to null.")
                        }
                    } else {
                        Log.e("AttributesFetch", "Error fetching attributes for ${deviceToProcess.name}: ${attributeResponse.code()} - ${attributeResponse.message()}")
                        // finalIsActiveStatus remains null (error state)
                        _errorAttributes.postValue("Error for ${deviceToProcess.name}: ${attributeResponse.message()}")
                        anErrorOccurred = true
                    }

                    // Update the device in our working map
                    // Ensure we get the existing device from the map to copy, then update its isActive
                    currentDeviceMap[deviceToProcess.id]?.let { existingDeviceInMap ->
                        currentDeviceMap[deviceToProcess.id] = existingDeviceInMap.copy(isActive = finalIsActiveStatus)
                        Log.d("AttributesFetch", "Updated ${deviceToProcess.name} in map. New isActive: $finalIsActiveStatus")
                    } ?: run {
                        // This case should ideally not happen if we pre-populated the map correctly above.
                        Log.e("AttributesFetch", "CRITICAL: Device ${deviceToProcess.name} (${deviceToProcess.id}) not found in working map for update. This is unexpected.")
                    }

                } catch (e: IOException) {
                    Log.e("AttributesFetch", "Network error for ${deviceToProcess.name}", e)
                    currentDeviceMap[deviceToProcess.id]?.let { existingDeviceInMap ->
                        currentDeviceMap[deviceToProcess.id] = existingDeviceInMap.copy(isActive = null) // Set to null on error
                    }
                    _errorAttributes.postValue("Network error for ${deviceToProcess.name}: ${e.message}")
                    anErrorOccurred = true
                } catch (e: Exception) {
                    Log.e("AttributesFetch", "Processing error for ${deviceToProcess.name}", e)
                    currentDeviceMap[deviceToProcess.id]?.let { existingDeviceInMap ->
                        currentDeviceMap[deviceToProcess.id] = existingDeviceInMap.copy(isActive = null) // Set to null on error
                    }
                    _errorAttributes.postValue("Processing error for ${deviceToProcess.name}: ${e.message}")
                    anErrorOccurred = true
                }
            }

            // Convert the updated map values back to a list
            val updatedDeviceList = currentDeviceMap.values.toList()
            Log.d("AttributesFetch", "Finished processing. Posting updated list with ${updatedDeviceList.size} devices.")

            _devices.postValue(updatedDeviceList)
            _isLoadingAttributes.postValue(false)
            if (!anErrorOccurred) {
                _errorAttributes.postValue(null)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DeviceListScreen(deviceListViewModel: DeviceListViewModel = viewModel(), onClickListener: (DeviceIdentifier) -> Unit) { // Pass DeviceIdentifier

    LaunchedEffect(Unit) {
        deviceListViewModel.fetchUserDevices()
    }

    val devices by deviceListViewModel.devices.observeAsState(initial = emptyList())
    val isLoadingDevices by deviceListViewModel.isLoading.observeAsState(initial = false)
    val errorDevices by deviceListViewModel.error.observeAsState()

    // Observe attribute loading/error states if you want to show specific UI for them
    val isLoadingAttributes by deviceListViewModel.isLoadingAttributes.observeAsState(initial = false)
    val errorAttributes by deviceListViewModel.errorAttributes.observeAsState()


    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = "User Devices", color = Color.White)
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(color = Color.White),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (isLoadingDevices) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                Text("Loading devices...", modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            errorDevices?.let {
                Text("Error loading devices: $it", color = Color.Red, modifier = Modifier.padding(8.dp))
            }
            if (isLoadingAttributes && !isLoadingDevices) { // Show only if devices are loaded
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp))
                Text("Loading device statuses...", modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            errorAttributes?.let {
                Text("Attribute Error: $it", color = Color.Magenta, modifier = Modifier.padding(8.dp))
            }


            if (!isLoadingDevices && errorDevices == null) {
                if (devices.isEmpty()) {
                    Log.i("DeviceListScreen", "No devices to display")
                } else {
                    LazyColumn { // Use LazyColumn for better performance with lists
                        items(devices, key = { it.id }) { device -> // Add key for stability
                            DeviceRow(device = device, onClickListener = { onClickListener(device) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceRow(device: DeviceIdentifier, onClickListener: () -> Unit) { // onClickListener might now take the device
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp) // Slightly increased height for status
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically // Align items vertically
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(Color.White)
                .border(1.dp, Color.Black, shape = RoundedCornerShape(16.dp))
                .clickable(onClick = {
                    currentdeviceId = device.id
                    currentdeviceName = device.name
                    onClickListener()
                }), // Call the passed onClickListener
            // contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = device.name,
                    fontSize = 17.sp,
                    color = Color.Black,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                // Display active status
                when (device.isActive) {
                    true -> Text("Active", color = Color.Green, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    false -> Text("Inactive", color = Color.Red, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    null -> Text("...", color = Color.Gray, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) // Loading or unknown
                }
            }
        }
    }
}
@Composable
@Preview(showBackground = true)
fun DeviceScreenPreview(){
    DeviceListScreen(onClickListener = {})
}
