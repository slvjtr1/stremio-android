package com.stremio.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.stremio.mobile.auth.FacebookLoginBridge
import com.stremio.mobile.core.display.DisplayRefreshController
import com.stremio.mobile.core.display.DisplayRefreshHost
import com.stremio.mobile.core.display.PictureInPictureController
import com.stremio.mobile.core.display.PictureInPictureHost
import kotlinx.coroutines.launch
import com.stremio.mobile.presentation.screens.StremioMobileApp
import com.stremio.mobile.presentation.viewmodel.MainViewModel

class MainActivity : ComponentActivity(), DisplayRefreshHost, PictureInPictureHost {
    override val displayRefresh by lazy { DisplayRefreshController(this) }
    override val pictureInPicture by lazy { PictureInPictureController(this) }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val viewModel: MainViewModel by viewModels {
        val app = application as MainApplication
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(
                    authRepository = app.container.authRepository,
                    boardRepository = app.container.boardRepository,
                    catalogRepository = app.container.catalogRepository,
                    addonRepository = app.container.addonRepository,
                    playbackRepository = app.container.playbackRepository,
                    updateRepository = app.container.updateRepository,
                    apkInstaller = app.container.apkInstaller,
                    serverController = app.container.serverController,
                    core = app.container.core,
                    enhancedPreferences = app.container.enhancedPreferences,
                    appContext = app.applicationContext,
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition {
            viewModel.sessionRestoring.value
        }
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestNotificationPermission()
        viewModel.acceptIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.enhancedSettings.collect { displayRefresh.setUiPreference(it.refreshRate) }
            }
        }

        setContent {
            StremioMobileApp(viewModel = viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.acceptIntent(intent)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (FacebookLoginBridge.callbackManager.onActivityResult(requestCode, resultCode, data)) {
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pictureInPicture.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPicture.onModeChanged(isInPictureInPictureMode)
    }

    override fun onResume() {
        super.onResume()
        // The display mode may have changed while we were away (resolution setting, external display).
        displayRefresh.apply()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppBackgrounded()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
