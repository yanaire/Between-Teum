package dev.pleos.starter

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import dev.pleos.starter.teum.ui.TeumUiController

class MainActivity : Activity() {
    private lateinit var ui: TeumUiController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ui = TeumUiController(this, findViewById(R.id.teum_root))
        ui.onCreate()
    }

    override fun onStart() {
        super.onStart()
        ui.onStart()
    }

    override fun onStop() {
        ui.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        ui.onDestroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ui.onConfigurationChanged()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!ui.onBackPressed()) super.onBackPressed()
    }
}
