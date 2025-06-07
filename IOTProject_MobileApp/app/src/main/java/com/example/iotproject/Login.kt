package com.example.iotproject
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory // Or your preferred converter

data class LoginRequest(
    val username: String,
    val password: String
)


object RetrofitClient {

    // IMPORTANT: Replace with your actual ThingsBoard base URL
    private const val BASE_URL = "https://app.coreiot.io"

    val instance: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create()) // Use Gson for JSON parsing
            .build()
    }
}

// Data class for the login response
data class LoginResponse(
    val token: String,
    val refreshToken: String
)

interface AuthThingsBoardApi { // Or a more specific name like AuthApi
    // --- OR Using Kotlin Coroutines (Recommended) ---
    @POST("api/auth/login")
    suspend fun loginCoroutine(
        @Body loginCredentials: LoginRequest
    ): Response<LoginResponse> // For use with coroutines
}

class authViewModel : ViewModel() {
    val authApi = RetrofitClient.instance.create(AuthThingsBoardApi::class.java)
}

@Composable
fun MyTextArea(
    modifier: Modifier = Modifier,
    label: String = "Description",
    initialText: String = "",
    onTextChanged: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onTextChanged(it)
        },
        label = { Text(label) },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp),
        // singleLine = false, // Default is false, so explicitly setting it isn't always needed but can be for clarity
        keyboardOptions = KeyboardOptions.Default.copy(
            keyboardType = KeyboardType.Text // Or KeyboardType.Ascii if you only want standard characters
        ),
        maxLines = 1
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    authViewModel: authViewModel = viewModel(), // Get an instance of AuthViewModel
    onLoginSuccess: () -> Unit // Callback for successful login
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) } // To show a loading indicator
    var errorMessage by remember { mutableStateOf<String?>(null) } // To display errors

    // Observe ViewModel state if you expose it (e.g., LiveData or StateFlow for login status/errors)
    // For this example, we'll handle simple Toast feedback directly.
    // In a real app, you'd likely have a more robust state management in the ViewModel
    // that the Composable observes for login success/failure/loading.

    val context = LocalContext.current // For showing Toasts

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log In", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF6200EE),
                    titleContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.White)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Welcome Back!",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 24.dp),
                color = Color.Black
            )

            // Using your MyTextArea for username
            MyTextArea(
                label = "Username or Email",
                initialText = username,
                onTextChanged = { username = it },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Using OutlinedTextField directly for password for PasswordVisualTransformation
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Password),
                singleLine = true,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(8.dp))

            errorMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(vertical = 16.dp))
            } else {
                Button(
                    onClick = {
                        if (username.isNotBlank() && password.isNotBlank()) {
                            isLoading = true
                            errorMessage = null
                            authViewModel.viewModelScope.launch { // Using ViewModel's scope
                                try {
                                    val response = authViewModel.authApi.loginCoroutine(
                                        LoginRequest(username, password)
                                    )
                                    if (response.isSuccessful && response.body() != null) {
                                        Log.d("LoginScreen", "Login successful: ${response.body()?.token}")
                                        isLoading = false
                                        authToken =  "Bearer " + response.body()?.token ?: ""
                                        Toast.makeText(context, "Login Successful!", Toast.LENGTH_SHORT).show()
                                        onLoginSuccess() // Trigger navigation or other action
                                    } else {
                                        val errorBody = response.errorBody()?.string() ?: "Unknown login error"
                                        Log.e("LoginScreen", "Login failed: ${response.code()} - $errorBody")
                                        errorMessage = "Login failed: ${response.message()} ${ if(errorBody.contains("Invalid credentials")) "(Invalid credentials)" else "" }"
                                        isLoading = false
                                    }
                                } catch (e: Exception) {
                                    Log.e("LoginScreen", "Login exception: ${e.message}", e)
                                    errorMessage = "Login error: ${e.localizedMessage ?: "Network error"}"
                                    isLoading = false
                                }
                            }
                        } else {
                            errorMessage = "Username and password cannot be empty."
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    enabled = !isLoading
                ) {
                    Text("Log In")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    // For preview, you might need a mock AuthViewModel or just pass the default
    MaterialTheme { // Ensure a MaterialTheme is applied for previews
        LoginScreen(
            authViewModel = authViewModel(),
            onLoginSuccess = { println("Preview: Login Success!") }
        )
    }
}