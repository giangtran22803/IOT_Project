#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <Wire.h>
#include <WiFi.h>
#include "esp_wifi.h"
#include "DHT20.h"
#include "SHT31.h"
#include "esp_now.h"
#include "BH1750.h"
#include "esp_wifi.h"

// DHT dht(DHTPIN, DHTTYPE);
DHT20 dht20;
SHT31 sht(0x44, &Wire);
BH1750 lightSensor(0x23);

#define LED_PIN 2

const uint8_t PMK_KEY_STR[16] = { 't','h','e','I','o','T','P','r','o','j','e','c','t','P','M','K' };
const uint8_t LMK_KEY_STR[16] = { 't','h','e','I','o','T','P','r','o','j','e','c','t','L','M','K' };

uint8_t receiverAddress[6] = {0xF0, 0x9E, 0x9E, 0x21, 0x99, 0xB8};

typedef struct struct_message {
    int counter;
    char mqtt_token[50] = "k1iq6voc5ys3w2gatfit";
    float temperature;
    float humidity;
    float light_level;
} struct_message;

struct_message myData;

typedef struct ack_message {
    int counter = -1;
} ack_message;

ack_message ackData;

// Counter variable to keep track of number of sent packets
int counter = 0;
esp_now_peer_info_t peerInfo;

void printMAC(const uint8_t * mac_addr){
  char macStr[18];
  snprintf(macStr, sizeof(macStr), "%02x:%02x:%02x:%02x:%02x:%02x",
           mac_addr[0], mac_addr[1], mac_addr[2], mac_addr[3], mac_addr[4], mac_addr[5]);
  Serial.println(macStr);
}

void OnDataSent(const uint8_t *mac_addr, esp_now_send_status_t status) {
  Serial.print("\r\nLast Packet Send Status:\t");
  Serial.println(status == ESP_NOW_SEND_SUCCESS ? "Delivery Success" : "Delivery Fail");
  if (status == ESP_NOW_SEND_SUCCESS) {
    ackData.counter = myData.counter; // Update ackData with the current counter
  }
}

void init_ESPNOW() {
  WiFi.mode(WIFI_STA);
  esp_wifi_set_channel(11, WIFI_SECOND_CHAN_NONE);
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
  esp_now_register_send_cb(OnDataSent);
  memset(&peerInfo, 0, sizeof(peerInfo)); // Clear peerInfo structure
  
  memcpy(peerInfo.peer_addr, receiverAddress, 6);
  peerInfo.channel = 0;
  memcpy(peerInfo.lmk, LMK_KEY_STR, 16); // Set local master key
  // Set encryption to true
  peerInfo.encrypt = true;
  peerInfo.ifidx = WIFI_IF_STA;                    
  
  // Add receiver as peer        
  esp_err_t result = esp_now_add_peer(&peerInfo);
  if (result != ESP_OK) {
    Serial.print("Failed to add peer. Error code: ");
    Serial.println(result);
  }
}

void readMacAddress(){
  uint8_t baseMac[6];
  esp_err_t ret = esp_wifi_get_mac(WIFI_IF_STA, baseMac);
  if (ret == ESP_OK) {
    Serial.printf("%02x:%02x:%02x:%02x:%02x:%02x\n",
                  baseMac[0], baseMac[1], baseMac[2],
                  baseMac[3], baseMac[4], baseMac[5]);
  } else {
    Serial.println("Failed to read MAC address");
  }
}

void toggle_led(void *pvParameters){
  while(1){
    if (digitalRead(LED_PIN) == HIGH){
      digitalWrite(LED_PIN, LOW);
    } else {
      digitalWrite(LED_PIN, HIGH);
    }
    Serial.println("LED is toggled");
    vTaskDelay(1000);
  }
}

void read_dht20(void *pvParameters){
  while(1){
    dht20.read();
    float temperature = dht20.getTemperature();
    float humidity = dht20.getHumidity();  
    if(isnan(temperature) || isnan(humidity)){
      Serial.println("Failed to read from DHT sensor!");
    }
    else{
      Serial.printf("Temperature: %f\n", temperature);
      Serial.printf("Humidity: %f\n", humidity);
    }

    vTaskDelay(6000);
  }
}

void sensor_setup(){
    Wire.begin(GPIO_NUM_21, GPIO_NUM_22);
    dht20.begin();
    lightSensor.begin();
    Wire.setClock(100000);
    uint16_t stat = sht.readStatus();
    Serial.print(stat, HEX);
    Serial.println();

}

void read_sht30(void *pvParameters){
  while (1){
    sht.read();
    Serial.print("Temperature:");
    Serial.print(sht.getTemperature(), 1);
    Serial.print("\t");
    Serial.print("Humidity:");
    Serial.println(sht.getHumidity(), 1);
    vTaskDelay(1000);
  }
}

void ESP_NOW_task(void *pvParameters) {
  while(1){
    dht20.read();
    myData.counter = counter++;
    myData.temperature = dht20.getTemperature();
    myData.humidity = dht20.getHumidity();
    myData.light_level = lightSensor.readLightLevel();
    if(isnan(myData.temperature) || isnan(myData.humidity) || isnan(myData.light_level)){
      Serial.println("Failed to read from DHT sensor!");
    }
    else{
      Serial.printf("Temperature: %f\n", myData.temperature);
      Serial.printf("Humidity: %f\n", myData.humidity);
      Serial.printf("Light Level: %f\n", myData.light_level);
    }

    // Send message via ESP-NOW
    // Wait for acknowledgment
    while(myData.counter != ackData.counter) {
      esp_err_t result = esp_now_send(receiverAddress, (uint8_t *) &myData, sizeof(myData));
      if (result == ESP_OK) {
        Serial.println("Sent with success");
      }
      else {
        Serial.println("Error sending the data");
        Serial.print("Error code: ");
        Serial.println(result);
      }
      vTaskDelay(100);
    }
    vTaskDelay(5000); // Delay for a second before sending the next message
  }
}

void setup() {
  // put your setup code here, to run once:
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH);
  Serial.begin(115200);
  readMacAddress();
  sensor_setup();
  init_ESPNOW();
  
  xTaskCreate(ESP_NOW_task, "ESP_NOW_task", 8192, NULL, 2, NULL);
  // xTaskCreate(read_dht20, "read_sensor", 8192, NULL, 2, NULL);
  xTaskCreate(toggle_led, "toggle_led", 2048, NULL, 1, NULL);
}

void loop() {
}
