package com.lampsmart.controller

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class CoolPresetActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BleHelper.dim(this, 1.0f, 0.0f)
        finish()
    }
}
