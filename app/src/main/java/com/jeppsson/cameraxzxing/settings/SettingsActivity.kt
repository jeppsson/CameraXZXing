package com.jeppsson.cameraxzxing.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.jeppsson.cameraxzxing.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportFragmentManager
            .beginTransaction()
            .add(R.id.settings_container, SettingsFragment())
            .commit()
    }
}