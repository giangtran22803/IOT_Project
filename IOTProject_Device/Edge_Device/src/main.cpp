#include <Arduino.h>
#include <Wire.h>
#include <BH1750.h>
#include <DHT20.h>
#include <WiFi.h>
#include <PubSubClient.h>
#include <esp_now.h>
#include <esp_wifi.h>

#include "cnn_outputs_humidity_model_data.h"
#include "cnn_outputs_temperature_model_data.h"


#include "tensorflow/lite/micro/all_ops_resolver.h"
#include "tensorflow/lite/micro/micro_error_reporter.h"
#include "tensorflow/lite/micro/micro_interpreter.h"
#include "tensorflow/lite/schema/schema_generated.h"

#include "humidity_model_data.h"
#include "temperature_model_data.h"

#define N_STEPS 10
#define SDA 8
#define SCL 9

//wifi
const char* ssid = "Hieu";
const char* password = "Hah253106";

//mqtt
const char* MQTT_SERVER = "app.coreiot.io"; //app.coreiot.io
const int MQTT_PORT = 1883;

const uint8_t PMK_KEY_STR[16] = { 't','h','e','I','o','T','P','r','o','j','e','c','t','P','M','K' };
const uint8_t LMK_KEY_STR[16] = { 't','h','e','I','o','T','P','r','o','j','e','c','t','L','M','K' };

//AI model
uint8_t tensor_arena_humidity[32 * 1024];
uint8_t tensor_arena_temperature[32 * 1024];

                // TensorFlow Lite Micro globals
tflite::MicroErrorReporter micro_error_reporter; 
tflite::ErrorReporter* error_reporter = &micro_error_reporter;

const tflite::Model* model_humidity;
const tflite::Model* model_temperature;

tflite::MicroInterpreter* interpreter_humidity;
tflite::MicroInterpreter* interpreter_temperature;

TfLiteTensor* input_humidity = nullptr;
TfLiteTensor* output_humidity = nullptr;

TfLiteTensor* input_temperature = nullptr;
TfLiteTensor* output_temperature = nullptr;

// Humidity input buffer and first 10 counters
float humidity_buffer[N_STEPS];

int readingCountHumidity = 0;

// Temperature input buffer and first 10 counters
float temperature_buffer[N_STEPS];

int readingCountTemperature = 0;

float hum = 0.0f; // Humidity value
float temp = 0.0f; // Temperature value
float light = 0.0f; // Light value


//Begin libraries
WiFiClient espClient;
PubSubClient client(espClient);

unsigned long lastPublish = 0;
const unsigned long publishInterval = 2500;

void tryConnectWiFi(const char* ssid, const char* password, const char* label);
void reconnect(char * mqtt_token);
void setupModel();
float runHumidityInference();
float runTemperatureInference();
void updateHumidityBuffer(float new_val);
void updateTemperatureBuffer(float new_val);

uint8_t senderAddress[] = {0x10, 0x06, 0x1C, 0x41, 0xA5, 0x38};
uint8_t broadcastAddress[] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
esp_now_peer_info_t peerInfo = {};

typedef struct struct_message {
    int counter;
    char mqtt_token[50];
    float temperature;
    float humidity;
    float light;
} struct_message;

struct_message myData;

bool received = false;

void printMAC(const uint8_t * mac_addr){
  char macStr[18];
  snprintf(macStr, sizeof(macStr), "%02x:%02x:%02x:%02x:%02x:%02x",
           mac_addr[0], mac_addr[1], mac_addr[2], mac_addr[3], mac_addr[4], mac_addr[5]);
  Serial.println(macStr);
}

void OnDataRecv(const uint8_t * mac_addr, const uint8_t *incomingData, int len) {
  Serial.print("Packet received from: ");
  printMAC(mac_addr);
  
  memcpy(&myData, incomingData, sizeof(myData));
  Serial.print("Packet number: ");
  Serial.println(myData.counter);
  Serial.print("temperature: ");
  Serial.println(myData.temperature);
  Serial.print("humidity: ");
  Serial.println(myData.humidity);
  Serial.print("MQTT token: ");
  Serial.println(myData.mqtt_token);
  hum = myData.humidity;
  temp = myData.temperature;
  light = myData.light;
  received = true;
}

void init_ESPNOW() {
  uint8_t wifiChannel = WiFi.channel();
  Serial.print("Current Wi-Fi channel: ");
  Serial.println(wifiChannel);


  if (esp_now_init() != ESP_OK) {
    Serial.println("There was an error initializing ESP-NOW");
    return;
  }
  
  esp_err_t ret =  esp_now_set_pmk(PMK_KEY_STR);
  if (ret != ESP_OK) {
    Serial.println("Failed to set PMK key");
    Serial.print("Error code: ");
    Serial.println(ret);
  } else {
    Serial.println("PMK key set successfully");
  }

  memset(&peerInfo, 0, sizeof(peerInfo)); // Clear peerInfo structure
  // Register the receiver board as peer
  memcpy(peerInfo.peer_addr, senderAddress, 6);
  memcpy(peerInfo.lmk, LMK_KEY_STR, 16); // Local Master Key
  peerInfo.channel = 0; 
  peerInfo.encrypt = true;
  peerInfo.ifidx = WIFI_IF_STA; // Use the station interface

  // Add receiver as peer        
  esp_err_t result = esp_now_add_peer(&peerInfo);
  if (result != ESP_OK) {
    Serial.print("Failed to add peer. Error code: ");
    Serial.println(result);
  }

  esp_now_register_recv_cb(esp_now_recv_cb_t(OnDataRecv));
}


void setup() {
  Serial.begin(115200);

  // ==== Connect Wi-Fi and MQTT ==== 
  tryConnectWiFi(ssid, password, "Hieu");
  init_ESPNOW();

  // ==== Begin AI models ==== 
  setupModel();

  client.setServer(MQTT_SERVER, MQTT_PORT);

}

void loop() {
  if (received) {
    received = false;
    reconnect(myData.mqtt_token);

    // Read sensors
    float predictedTemperature = 0.0f;

    if (readingCountTemperature < N_STEPS) {
      predictedTemperature = temp;
      updateTemperatureBuffer(temp);
      readingCountTemperature++;
    } else {
      updateTemperatureBuffer(temp);
      predictedTemperature = runTemperatureInference();
    }

    //Process the humidity
    float predictedHumidity = 0.0f;

    if (readingCountHumidity < N_STEPS ) {
      predictedHumidity = hum;
      updateHumidityBuffer(hum);
      readingCountHumidity++;
    } else {
      updateHumidityBuffer(hum);
      predictedHumidity = runHumidityInference();
    }

    // JSON payload
    char payload[160];
    snprintf(payload, sizeof(payload),
             "{\"temperature\":%.2f,\"predicting_temperature\":%.2f,\"humidity\":%.2f,\"predicting_humidity\":%.2f,\"light\":%.2f}",
             temp, predictedTemperature, hum, predictedHumidity, light);

    Serial.print("Publishing: ");
    Serial.println(payload);

    if (client.publish("v1/devices/me/telemetry", payload)) {
      Serial.println("Publish successful");
    } else {
      Serial.println("Publish failed");
    }
  }
}


void tryConnectWiFi(const char* ssid, const char* password, const char* label) {
  WiFi.mode(WIFI_STA);
  Serial.printf("Connecting to %s", label);
  WiFi.begin(ssid, password);
  for (int i = 0; i < 10; i++) {
    if (WiFi.status() == WL_CONNECTED) break;
    delay(500);
    Serial.print(".");
  }
  Serial.println();
  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("Connected to Wi-Fi!");
    Serial.print("IP address: ");
    Serial.println(WiFi.localIP());
  } else {
    Serial.println("Both Wi-Fi networks are unavailable.");
  }
}



void reconnect(char *mqtt_token) {
  while (!client.connected()) {
    Serial.print("Connecting to MQTT...");
    if (client.connect("ESP32Client", mqtt_token, NULL)) {
      Serial.println(" connected.");
    } else {
      Serial.print(" failed, rc=");
      Serial.print(client.state());
      delay(1000);
    }
  }
}

void setupModel() {
  model_humidity = tflite::GetModel(humidity_model_tflite);
  model_temperature = tflite::GetModel(temperature_model_tflite);
  
  if (model_humidity->version() != TFLITE_SCHEMA_VERSION) {
    Serial.println("Humidity model schema mismatch!");
    while (1);
  }
  if (model_temperature->version() != TFLITE_SCHEMA_VERSION) {
    Serial.println("Temperature model schema mismatch!");
    while (1);
  }

  static tflite::MicroMutableOpResolver<3> resolver;
  resolver.AddReshape();
  resolver.AddFullyConnected();
  resolver.AddRelu();

  // Create interpreter for humidity
  interpreter_humidity = new tflite::MicroInterpreter(model_humidity, resolver, tensor_arena_humidity, sizeof(tensor_arena_humidity), error_reporter);
  if (interpreter_humidity->AllocateTensors() != kTfLiteOk) {
    Serial.println("Humidity AllocateTensors failed!");
    while (1);
  } else Serial.println("Humidity AllocateTensors succeeded!");

  // Create interpreter for temperature
  interpreter_temperature = new tflite::MicroInterpreter(model_temperature, resolver, tensor_arena_temperature, sizeof(tensor_arena_temperature), error_reporter);
  if (interpreter_temperature->AllocateTensors() != kTfLiteOk) {
    Serial.println("Temperature AllocateTensors failed!");
    while (1);
  } else Serial.println("Temperature AllocateTensors succeeded!");


  input_humidity = interpreter_humidity->input(0);
  output_humidity = interpreter_humidity->output(0);

  input_temperature = interpreter_temperature->input(0);
  output_temperature = interpreter_temperature->output(0);

  // Initialize buffers
  for (int i = 0; i < N_STEPS; ++i) {
    humidity_buffer[i] = 0.0f;
    temperature_buffer[i] = 0.0f;
  }

  Serial.println("Models are ready.");
}

// Inference for humidity
float runHumidityInference() {
  for (int i = 0; i < N_STEPS; i++) {
    input_humidity->data.f[i] = humidity_buffer[i];
  }
  if (interpreter_humidity->Invoke() != kTfLiteOk) {
    Serial.println("Humidity inference failed!");
    return -1.0f;
  }
  return output_humidity->data.f[0];
}

// Inference for temperature
float runTemperatureInference() {
  for (int i = 0; i < N_STEPS; i++) {
    input_temperature->data.f[i] = temperature_buffer[i];
  }
  if (interpreter_temperature->Invoke() != kTfLiteOk) {
    Serial.println("Temperature inference failed!");
    return -1.0f;
  }
  return output_temperature->data.f[0];
}

void updateHumidityBuffer(float new_val) {
  for (int i = 0; i < N_STEPS - 1; i++) {
    humidity_buffer[i] = humidity_buffer[i + 1];
  }
  humidity_buffer[N_STEPS - 1] = new_val;
}

void updateTemperatureBuffer(float new_val) {
  for (int i = 0; i < N_STEPS - 1; i++) {
    temperature_buffer[i] = temperature_buffer[i + 1];
  }
  temperature_buffer[N_STEPS - 1] = new_val;
}