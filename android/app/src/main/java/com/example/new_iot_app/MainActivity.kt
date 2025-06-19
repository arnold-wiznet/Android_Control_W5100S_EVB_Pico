package com.example.new_iot_app

import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import com.ekn.gruzer.gaugelibrary.Range
import com.example.new_iot_app.databinding.ActivityMainBinding
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import org.json.JSONObject
import org.json.JSONArray
import java.security.KeyStore.TrustedCertificateEntry

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json() // for JSON parsing if needed
        }

        install(HttpTimeout){
            requestTimeoutMillis = 30000
            connectTimeoutMillis = 30000
            socketTimeoutMillis = 30000
        }
    }
    private var discoveryJob: Job? = null
    private var get_data_job: Job? = null



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //Initialize binding and set view
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

//        val buttonOn = binding.buttonOn


        val buttonBulb = binding.lightButton
        var buttonState = false
//        val buttonTemp = binding.buttonTemp

        val editbox = binding.currentMode
        var ip: String? = null

        val t2 = binding.T2

        val ac = "arnoldho"
        // Avoid keep calling to sound again
        var _discovered_once = false
        var _disconnected_to_Adafruit = false
        var _discoveryJob = false
        var key: String? = null

        // Adafruit
        var adafruit_online: Int? = null
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        var io_key = prefs.getString("secret_key", null)

        // Adafruit URL
        val endpoint_temp = "https://io.adafruit.com/api/v2/$ac/feeds/temp/data"
        val endpoint_humid = "https://io.adafruit.com/api/v2/$ac/feeds/humid/data"
        val endpoint_online = "https://io.adafruit.com/api/v2/$ac/feeds/online/data"
        val endpoint_light = "https://io.adafruit.com/api/v2/$ac/feeds/light/data"
        val endpoint_conc = "https://io.adafruit.com/api/v2/$ac/feeds/conc/data"
        val endpoint_lightbox = "https://io.adafruit.com/api/v2/$ac/feeds/pwmlight/data"
        val endpoint_fan = "https://io.adafruit.com/api/v2/$ac/feeds/fan/data"

        val netLock = Mutex()
        enableEdgeToEdge()

        //
        //Half gauge
        val arcGauge = binding.arcGauge
        val range_temp = Range().apply {
            color = Color.parseColor("#6aeb79")
            from  = 0.0
            to    = 35.0
        }
        arcGauge.addRange(range_temp)
        arcGauge.minValue = 10.0
        arcGauge.maxValue = 40.0

        // Half Gauge
        val halfGauge = binding.halfGauge
        val range0 = Range().apply{
            color = Color.parseColor("#81D4FA")
            from  = 0.0
            to    = 33.33
        }
        val range1 = Range().apply{
            color = Color.parseColor("#4CAF50")
            from  = 33.34
            to    = 66.67
        }
        val range2 = Range().apply{
            color = Color.parseColor("#FF7043")
            from  = 66.67
            to    = 100.0
        }
        halfGauge.addRange(range0)
        halfGauge.addRange(range1)
        halfGauge.addRange(range2)
        halfGauge.minValue = 0.0
        halfGauge.maxValue = 100.0

        val halfGauge2 = binding.halfGauge2
        val range_HCHO_low = Range().apply{
            from  =  0.0
            to    = 50.0
            color = Color.parseColor("#6AC259")   // green
        }
        val range_HCHO_normal= Range().apply{
            from  = 50.0
            to    =100.0
            color = Color.parseColor("#F2C94C")   // yellow
        }
        val range_HCHO_high = Range().apply{
            from  =100.0
            to    =200.0
            color = Color.parseColor("#EB5757")   // red
        }
        halfGauge2.addRange(range_HCHO_low)
        halfGauge2.addRange(range_HCHO_normal)
        halfGauge2.addRange(range_HCHO_high)


        // Slider and Button
        val slider = binding.seekBar
        val slider_button = binding.slidebutton
        var slider_value:Int = 0

        slider.max = 100
        slider.setOnSeekBarChangeListener(object: OnSeekBarChangeListener{
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                slider_value = progress
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                //pass
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
               // pass
            }
        })


        // fan button
        val fan_button = binding.fanButton
        fan_button.isEnabled = false
        var fan_state = false

        // Obtain Device
        discoveryJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!isNetworkAvailable()) {
                    withContext(Dispatchers.Main) {
                        editbox.setText("Offline")
                        arcGauge.value = 0.0
                        halfGauge.value = 0.0
                        t2.text = ""
                        buttonBulb.isEnabled = false
                        slider_button.isEnabled = false
                        fan_button.isEnabled = false
                    }
                    delay(10_000)
                    continue   // try again in 10s, don’t kill the loop
                } else {
                    withContext(Dispatchers.Main){
                        buttonBulb.isEnabled = true
//                        fan_button.isEnabled = true
                    }
                }
//                t2.setText("Trying to ping device")
                // UDP to look for IOT device
                val result = discoverIotDevice()
//                t2.text = result
                if (result != null) {
                    netLock.withLock {ip = result.toString()}
                    editbox.setText(ip)

                    // Haven't got secrets
                    if (!prefs.contains("secret_key")) {
                        _disconnected_to_Adafruit = false
                        withContext(Dispatchers.Main) {
                            try {
                                key = client.get("http://$ip:5000/key").body()
                                if (key == "NO_KEY") {
                                    t2.text = "No key exists. Only local control available"
                                } else {
                                    prefs.edit().putString("secret_key", key).apply()
                                    io_key = prefs.getString("secret_key", null)
                                    t2.text = "Secrets saved"

                                }
                            } catch (e: Exception) {
                                // handle error...
                                t2.text = "Error in loading "
                            }


                        }
                    } else {

                        if (_disconnected_to_Adafruit) {
                            delay(1000L)
//                            t2.setText("Debug")
                            _disconnected_to_Adafruit = false
                            try{
                                val response: JsonObject = client
                                    .get("http://$ip:5000/") {
                                        header("Accept", "application/json")
                                    }
                                    .body()
                                withContext(Dispatchers.Main) {
                                    t2.text = "Reconnected to local"
                                    if (response["value"].toString().toBoolean()) {
                                        buttonState = true
                                        buttonBulb.setImageResource(R.drawable.bulb_on)
                                    } else {
                                        buttonState = false
                                        buttonBulb.setImageResource(R.drawable.bulb_off)
                                    }

                                    if (response["fan"].toString().toBoolean()) {
                                        fan_state = true
                                        fan_button.setImageResource(R.drawable.fan_on)
                                    } else {
                                        fan_state = false
                                        fan_button.setImageResource(R.drawable.fan_off)
                                    }
                                }

                            } catch (e:Exception){
                                //pass
                            }
                        } else {

//                                withContext(Dispatchers.Main) { t2.text = "Connection Continued"}
                        }
                    }



                } else {
                        _disconnected_to_Adafruit = true
                        ip = null
//
                        if (prefs.contains("secret_key")) {
                            withContext(Dispatchers.Main) { editbox.setText("Adafruit Mode")}
                        } else {
                            withContext(Dispatchers.Main) {
                                editbox.setText("Connect locally before using IOT Service")
                            }

                    }
                }

//                t2.setText("")
                if (!_discoveryJob){
                    _discoveryJob = true
                }

                    delay(10000)
            }
        }



        buttonBulb.setOnClickListener() {
            if (!buttonBulb.isEnabled  ) return@setOnClickListener
            lifecycleScope.launch {
                buttonBulb.isEnabled = false
                t2.text = "Loading..."
                if (ip != null) {

                    // Do local turn on light
                    netLock.withLock {
                        try {
                            val response: String = client.get("http://$ip:5000/light") {
                                header("Accept", "application/json")
                            }.body()
                            t2.text = response
                            buttonState = !buttonState

                        } catch (e: Exception) {
//                            t2.text = "Error: ${e.localizedMessage}"
                        }
                    }
                } else {

                    // Do Adafruit turn on light

                    // Check online status
                    if (io_key != null) {
                        try {
                            val response_online: String = client.get(endpoint_online) {
                                header("X-AIO-Key", io_key)
                            }.bodyAsText()
                            var jsonArray = JSONArray(response_online)
                            if (jsonArray.length() > 0) {
                                adafruit_online = jsonArray.getJSONObject(0).optInt("value", -1)
                            }
                        } catch (e: Exception) {
                            //pass
                        }


                        if (adafruit_online == 1) {
                            // Post light data
                            try {

                                val x: Int = if (buttonState) 0 else 1
                                val payload = """
                                {
                                    "value": $x
                                 }
                            """.trimIndent()
                                val responseText = client.post(endpoint_light) {
                                    header("X-AIO-Key", io_key)
                                    contentType(ContentType.Application.Json)
                                    setBody(payload)
                                }.bodyAsText()
                                t2.text = JSONObject(responseText).optString("value")
                                buttonState = !buttonState
                            } catch (e: Exception) {
                                t2.text = "Error: ${e.localizedMessage}"
                            }
                        } else {
                            t2.text = "Server not online"
                        }

                    }


                }
                buttonBulb.isEnabled = true
                if (buttonState) {
                    buttonBulb.setImageResource(R.drawable.bulb_on)
                } else {
                    buttonBulb.setImageResource(R.drawable.bulb_off)
                }
            }
        }


        slider_button.setOnClickListener() {
            if (!slider_button.isEnabled) return@setOnClickListener
            if (!buttonState) {
                t2.text = "Turn on light before toggle"
                return@setOnClickListener
            }
            lifecycleScope.launch {
                slider_button.isEnabled = false
                t2.text = "Loading..."
                if (ip != null) {
                    netLock.withLock {
                        try {
                            val slider_string = slider_value.toString()
                            val slider_payload = """{
                                    "value":$slider_string
                                }""".trimIndent()
                            val response: String = client.post("http://$ip:5000/lightbox") {
                                header("Accept", "application/json")
                                contentType(ContentType.Application.Json)
                                setBody(slider_payload)
                            }.body()
                            t2.text = response

                        } catch (e: Exception) {
//                            t2.text = "Error: ${e.localizedMessage}"
                        }
                    }
                }
                else {

                    // Check online status
                    if (io_key != null) {
                        try {
                            val response_online: String = client.get(endpoint_online) {
                                header("X-AIO-Key", io_key)
                            }.bodyAsText()
                            var jsonArray = JSONArray(response_online)
                            if (jsonArray.length() > 0) {
                                adafruit_online = jsonArray.getJSONObject(0).optInt("value", -1)
                            }
                        } catch (e: Exception) {
                            //pass
                        }


                        if (adafruit_online == 1) {
                            // Post light data
                            try {

                                val slider_string = slider_value.toString()
                                val slider_payload = """{
                                    "value":$slider_string
                                }""".trimIndent()
                                val responseText: String = client.post(endpoint_lightbox) {
                                    header("X-AIO-Key", io_key)
                                    contentType(ContentType.Application.Json)
                                    setBody(slider_payload)
                                }.bodyAsText()
                                t2.text = JSONObject(responseText).optString("value")

                            } catch (e: Exception) {
                                t2.text = "Error: ${e.localizedMessage}"
                            }
                        } else {
                            t2.text = "Server not online"
                        }

                    }
                }
                slider_button.isEnabled = true
            }

        }
//        fan_button.setOnClickListener() {
//            if (!fan_button.isEnabled) return@setOnClickListener
//            lifecycleScope.launch {
//                fan_button.isEnabled = false
//                t2.text = "Loading..."
//                if (ip != null) {
//                    netLock.withLock {
//                        try {
//
//
//                            val response: String = client.get("http://$ip:5000/fan") {
//                                header("Accept", "application/json")
//                            }.body()
//                            t2.text = response
//                            fan_state = !fan_state
//                        } catch (e: Exception) {
////                            t2.text = "Error: ${e.localizedMessage}"
//                        }
//                    }
//                }
//                else {
//
//                    // Check online status
//                    if (io_key != null) {
//                        try {
//                            val response_online: String = client.get(endpoint_online) {
//                                header("X-AIO-Key", io_key)
//                            }.bodyAsText()
//                            var jsonArray = JSONArray(response_online)
//                            if (jsonArray.length() > 0) {
//                                adafruit_online = jsonArray.getJSONObject(0).optInt("value", -1)
//                            }
//                        } catch (e: Exception) {
//                            //pass
//                        }
//
//
//                        if (adafruit_online == 1) {
//
//                            try {
//
//                                val fan_number: Int = if (fan_state) 0 else 1
//                                val fan_payload = """{
//                                    "value":$fan_number
//                                }""".trimIndent()
//                                val responseText: String = client.post(endpoint_fan) {
//                                    header("X-AIO-Key", io_key)
//                                    contentType(ContentType.Application.Json)
//                                    setBody(fan_payload)
//                                }.bodyAsText()
//                                t2.text = JSONObject(responseText).optString("value")
//                                fan_state = !fan_state
//
//                            } catch (e: Exception) {
//                                t2.text = "Error: ${e.localizedMessage}"
//                            }
//                        } else {
//                            t2.text = "Server not online"
//                        }
//
//                    }
//                }
//                fan_button.isEnabled = true
//                if (fan_state) {
//                    fan_button.setImageResource(R.drawable.fan_on)
//                } else {
//                    fan_button.setImageResource(R.drawable.fan_off)
//                }
//            }
//
//        }
        // Automatically obtain data of SFA30
        get_data_job = lifecycleScope.launch(Dispatchers.IO){
            // If ip is obtained from discovery job
            delay(3000) // Update every 3 seconds
            while (isActive){
                if (! _discoveryJob){
                    continue
                }

                if (ip != null){
                        netLock.withLock {
                            try {
                                delay(1000)
                                val response: JsonObject = client.get("http://$ip:5000/SFA30") {
                                    header("Accept", "application/json")
                                }.body()
                                //                        t2.text = "Current Temperature : ${response["Temp"].toString()}°C"
                                withContext(Dispatchers.Main) {
                                    arcGauge.value = response["Temp"].toString().toDouble()
                                    halfGauge.value = response["Humidity"].toString().toDouble()
                                    halfGauge2.value = response["HCHO"].toString().toDouble()
                                    val recieved_fan_string = response["fan"].toString()
                                    t2.text = recieved_fan_string
                                    if (recieved_fan_string == "true"){
                                        fan_button.setImageResource(R.drawable.fan_on)
                                    } else {
                                        fan_button.setImageResource(R.drawable.fan_off)
                                    }
                                }

                            } catch (e: Exception) {
//                                withContext(Dispatchers.Main) {
//                                    t2.text = "Error: ${e.localizedMessage}"
//                                }
                            }
                        }

                }
                // The service is now Adafruit Mode
                else {
//                    val io_key = prefs.getString("secret_key", null)
                    if (io_key != null) {

                        // Check for online status
                        try{
                            val response_online: String = client.get(endpoint_online) {
                                header("X-AIO-Key", io_key)
                            }.bodyAsText()
                            var jsonArray = JSONArray(response_online)
                            if (jsonArray.length() > 0) {
                                adafruit_online = jsonArray.getJSONObject(0).optInt("value", -1)
                            }
                        } catch(e:Exception){
                            adafruit_online = 0
                        }



                        // If the Adafruit IO status is online
                        if (adafruit_online == 1) {
                            // Use Async to obtain the three feeds together

                            val temp_fetch = async(Dispatchers.IO){
                                runCatching {
                                    client.get(endpoint_temp){
                                        header("X-AIO-KEY", io_key)
                                    }
                                        .bodyAsText().let { JSONArray(it).getJSONObject(0).optDouble("value", 0.0)}
                                }.getOrDefault(0.0)
                            }

                            val humid_fetch = async(Dispatchers.IO){
                                runCatching {
                                    client.get(endpoint_humid){
                                        header("X-AIO-KEY", io_key)
                                    }
                                        .bodyAsText().let { JSONArray(it).getJSONObject(0).optDouble("value", 0.0)}
                                }.getOrDefault(0.0)
                            }

                            val HCHO_fetch = async(Dispatchers.IO){
                                runCatching {
                                    client.get(endpoint_conc){
                                        header("X-AIO-KEY", io_key)
                                    }
                                        .bodyAsText().let { JSONArray(it).getJSONObject(0).optDouble("value", 0.0)}
                                }.getOrDefault(0.0)
                            }
                            val Fan_fetch = async(Dispatchers.IO){
                                runCatching {
                                    client.get(endpoint_fan){
                                        header("X-AIO-KEY", io_key)
                                    }
                                        .bodyAsText().let { JSONArray(it).getJSONObject(0).optDouble("value", 0.0)}
                                }.getOrDefault(-1)
                            }

                            val temp  = temp_fetch.await()
                            val humid = humid_fetch.await()
                            val hcho  = HCHO_fetch.await()
                            val fan   = Fan_fetch.await()

                            withContext(Dispatchers.Main){
                                arcGauge.value = temp
                                halfGauge.value = humid
                                halfGauge2.value = hcho
                                if (fan == 1.0){
                                    fan_button.setImageResource(R.drawable.fan_on)
                                } else {
                                    fan_button.setImageResource(R.drawable.fan_off)
                                }
                            }
                        } else{
                            // Offline
                            withContext(Dispatchers.Main) {
                                arcGauge.value = 0.0
                                halfGauge.value = 0.0
                                halfGauge2.value = 0.0
                                fan_button.setImageResource(R.drawable.fan_off)
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main){t2.text = "No Key"}
                    }
                    delay(2000L)
                }
            }
        }

    }
    override fun onDestroy() {
        super.onDestroy()
        client.close()
        discoveryJob?.cancel()
        get_data_job?.cancel()
    }

}
suspend fun discoverIotDevice(): String? = withContext(Dispatchers.IO) {
    val socket = DatagramSocket().apply {
        broadcast = true
        soTimeout = 5000  // 5 s timeout
    }

    try {
        // 1) send the discovery ping
        val message = "DISCOVER_IOT".toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(
            message, message.size,
            InetAddress.getByName("255.255.255.255"), 8888
        )
        socket.send(packet)

        // 2) wait for the first response
        val buf = ByteArray(256)
        val respPacket = DatagramPacket(buf, buf.size)
        socket.receive(respPacket)
        respPacket.address.hostAddress
//        val response = String(respPacket.data, 0, respPacket.length, Charsets.UTF_8)
//        val deviceIp = respPacket.address.hostAddress
//        "$deviceIp → $response"
    } catch (e: SocketTimeoutException) {
        null

    } finally {
        socket.close()
    }
}

// Check internet connectivity
@Suppress("DEPRECATION")
fun Context.isNetworkAvailable(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        // API 23+
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } else {
        // API 21–22
        val ni = cm.activeNetworkInfo
        ni != null && ni.isConnected
    }
}



