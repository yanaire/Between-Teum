package dev.pleos.starter.teum.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import dev.pleos.starter.R

/** Hosts the retained native renderer; this is not the production WebView entrypoint. */
class NativeRegressionActivity : Activity() {
    private lateinit var controller: TeumUiController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        controller = TeumUiController(this, findViewById(R.id.teum_root))
        controller.onCreate()
    }

    override fun onStart() {
        super.onStart()
        controller.onStart()
    }

    override fun onStop() {
        controller.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        controller.onDestroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller.onConfigurationChanged()
    }

    @Deprecated("Uses the legacy Activity back callback for native regression coverage.")
    override fun onBackPressed() {
        if (!controller.onBackPressed()) super.onBackPressed()
    }

    /** The fixture owns coordinator startup; Activity teardown still owns this controller. */
    fun installCoordinator(coordinator: TeumSessionCoordinator): TeumUiController {
        controller.onStop()
        controller.onDestroy()
        controller = TeumUiController(this, findViewById(R.id.teum_root), coordinator)
        controller.onCreate()
        return controller
    }
}
