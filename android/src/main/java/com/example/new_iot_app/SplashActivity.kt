package com.example.new_iot_app


import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import android.os.Handler
import android.os.Looper
import com.example.new_iot_app.databinding.ActivitySplashBinding

class SplashActivity : ComponentActivity() {
    private lateinit var binding: ActivitySplashBinding



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)


        // Delay and then start MainActivity
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()  // so user can’t go back here
        }, 1500)  // 1.5 seconds
    }
}

