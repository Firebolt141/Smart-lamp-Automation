package com.lampsmart.controller

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class WarmPresetActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BleHelper.dim(this, 0.75f, 1.0f)
        finish()
    }
}
