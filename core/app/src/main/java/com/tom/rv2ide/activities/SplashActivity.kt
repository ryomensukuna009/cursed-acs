/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.imageview.ShapeableImageView
import com.tom.rv2ide.R
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.app.configuration.IJdkDistributionProvider
import com.tom.rv2ide.fragments.onboarding.PermissionsFragment
import com.tom.rv2ide.preferences.internal.BuildPreferences
import com.tom.rv2ide.preferences.internal.GeneralPreferences
import com.tom.rv2ide.preferences.internal.StatPreferences
import com.tom.rv2ide.utils.AppLogoManager
import com.tom.rv2ide.utils.Environment
import java.util.concurrent.Executors

/**
 * Splash activity presenting the CURSED ACS Ryomen Sukuna brand reveal.
 * Supports:
 * - Immediate presentation of initial IDE startup/loading experience (zero blank screen)
 * - 13.65s Sukuna Domain Expansion HTML5 Canvas 60fps animation blooming smoothly over loading screen
 * - Japanese voice audio synchronization (sukuna_domain_expansion.mp3) matching visual render onset
 * - Race-safe user touch to skip appearing strictly when background app initialization is ready
 * - Clean audio teardown and software-rasterized WebView to protect against mobile GPU driver faults
 * - Settings toggle in General Preferences to enable/disable animation reliably
 * - Persistent one-time onboarding detection (prevents returning to wizard once setup is complete)
 */
class SplashActivity : AppCompatActivity() {

  private var mediaPlayer: MediaPlayer? = null
  private var splashWebView: WebView? = null
  private var skipHintView: TextView? = null
  private var containerView: View? = null

  @Volatile private var hasNavigated = false
  @Volatile private var isAppReady = false
  @Volatile private var userRequestedSkip = false
  @Volatile private var setupCompleted = false
  @Volatile private var isAnimationCompleted = false

  private val fallbackHandler = Handler(Looper.getMainLooper())
  private var fallbackRunnable: Runnable? = null
  private val initExecutor = Executors.newSingleThreadExecutor()

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(R.style.Theme_AndroidIDE)
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_splash)

    val logoView = findViewById<ShapeableImageView>(R.id.splashLogo)
    if (logoView != null) {
      AppLogoManager.bindLogo(logoView)
    }

    val container = findViewById<View>(R.id.splashContainer)
    val webView = findViewById<WebView>(R.id.splashWebView)
    val skipHint = findViewById<TextView>(R.id.splashSkipHint)
    val root = findViewById<View>(R.id.splashRoot)

    containerView = container
    skipHintView = skipHint

    root?.setOnClickListener { handleUserSkip() }
    skipHint?.setOnClickListener { handleUserSkip() }

    // Initial IDE startup/loading layout is kept VISIBLE immediately
    container?.visibility = View.VISIBLE

    // Start background app readiness & setup check concurrently
    startConcurrentInitialization()

    val shouldPlayAnimation = GeneralPreferences.sukunaLaunchAnimation

    if (shouldPlayAnimation && webView != null) {
      // WebView is placed OVER container with transparent background
      webView.visibility = View.VISIBLE
      webView.alpha = 1f
      skipHint?.visibility = View.GONE
      skipHint?.alpha = 0f
      splashWebView = webView

      setupWebView(webView)
      startAudioPlayback()

      // Failsafe timer (14.5 seconds) to guarantee navigation even if webview or audio lags
      fallbackRunnable = Runnable { navigateNext() }
      fallbackHandler.postDelayed(fallbackRunnable!!, 14500)
    } else {
      // Fast static fallback reveal when animation is disabled
      webView?.visibility = View.GONE
      skipHint?.visibility = View.GONE
      if (container != null) {
        container.alpha = 0f
        container.scaleX = 0.90f
        container.scaleY = 0.90f

        container.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(350)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
              // Wait for app initialization then navigate
              if (isAppReady) {
                navigateNext()
              } else {
                userRequestedSkip = true
              }
            }
            .start()
      } else {
        if (isAppReady) navigateNext() else userRequestedSkip = true
      }
    }
  }

  private fun startConcurrentInitialization() {
    initExecutor.execute {
      val ready = checkSetupStatus()
      setupCompleted = ready
      isAppReady = true
      runOnUiThread {
        if (!hasNavigated) {
          if (userRequestedSkip || isAnimationCompleted) {
            navigateNext()
          } else {
            // Background loading complete! Now reveal minimalist skip button
            skipHintView?.apply {
              text = "Tap to skip ➔"
              visibility = View.VISIBLE
              alpha = 0f
              translationY = 24f
              animate()
                  .alpha(1f)
                  .translationY(0f)
                  .setDuration(400)
                  .setInterpolator(DecelerateInterpolator())
                  .start()
            }
          }
        }
      }
    }
  }

  private fun handleUserSkip() {
    if (isAppReady) {
      navigateNext()
    } else {
      userRequestedSkip = true
      skipHintView?.apply {
        text = "Initializing IDE... opening immediately"
        visibility = View.VISIBLE
        alpha = 1f
      }
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun setupWebView(webView: WebView) {
    // Software layer rasterization eliminates mobile Adreno GPU kgsl-3d0 driver faults
    webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    webView.setBackgroundColor(Color.TRANSPARENT)
    val settings = webView.settings
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = true
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.cacheMode = WebSettings.LOAD_NO_CACHE
    settings.mediaPlaybackRequiresUserGesture = false

    webView.addJavascriptInterface(object {
      @JavascriptInterface
      fun onFirstFrameRendered() {
        runOnUiThread {
          if (!hasNavigated) {
            // Smoothly bloom animation over the existing loading screen
            splashWebView?.animate()?.alpha(1f)?.setDuration(250)?.start()
            startAudioPlayback()
          }
        }
      }

      @JavascriptInterface
      fun onAnimationFinished() {
        runOnUiThread {
          isAnimationCompleted = true
          if (isAppReady) {
            navigateNext()
          }
        }
      }

      @JavascriptInterface
      fun onUserSkip() {
        runOnUiThread { handleUserSkip() }
      }
    }, "AndroidBridge")

    webView.webChromeClient = object : WebChromeClient() {
      override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        Log.d("SukunaSplash", "${consoleMessage?.message()} -- [line ${consoleMessage?.lineNumber()}]")
        return true
      }
    }

    webView.webViewClient = object : WebViewClient() {
      override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        // If JS first frame event lagged, ensure visibility after page finishes loading
        if (splashWebView != null && splashWebView?.alpha == 0f) {
          splashWebView?.animate()?.alpha(1f)?.setDuration(250)?.start()
          startAudioPlayback()
        }
      }
    }

    webView.loadUrl("file:///android_asset/sukuna/index.html")
  }

  private fun startAudioPlayback() {
    if (mediaPlayer != null) return
    try {
      mediaPlayer = MediaPlayer.create(this, R.raw.sukuna_domain_expansion).apply {
        setOnCompletionListener {
          isAnimationCompleted = true
          if (isAppReady) {
            navigateNext()
          }
        }
        start()
      }
    } catch (_: Throwable) {
      // Audio playback is non-fatal; canvas animation continues smoothly
    }
  }

  @Synchronized
  private fun stopAudioPlayback() {
    try {
      mediaPlayer?.let {
        if (it.isPlaying) {
          it.stop()
        }
        it.release()
      }
    } catch (_: Throwable) {
    } finally {
      mediaPlayer = null
    }
  }

  @Synchronized
  private fun navigateNext() {
    if (hasNavigated || isFinishing || isDestroyed) return
    hasNavigated = true

    fallbackRunnable?.let { fallbackHandler.removeCallbacks(it) }
    stopAudioPlayback()

    val targetIntent = if (setupCompleted) {
      Intent(this, MainActivity::class.java)
    } else {
      Intent(this, OnboardingActivity::class.java)
    }

    startActivity(targetIntent)
    finish()
    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
  }

  override fun onPause() {
    super.onPause()
    stopAudioPlayback()
  }

  override fun onDestroy() {
    super.onDestroy()
    fallbackRunnable?.let { fallbackHandler.removeCallbacks(it) }
    stopAudioPlayback()
    try {
      splashWebView?.destroy()
    } catch (_: Throwable) {}
    splashWebView = null
    initExecutor.shutdown()
  }

  private fun checkSetupStatus(): Boolean {
    return try {
      val prefManager = BaseApplication.getBaseInstance().prefManager
      val firstRunDone = prefManager.getBoolean("first_run_setup_completed", false)
      if (firstRunDone) {
        return StatPreferences.statConsentDialogShown &&
            PermissionsFragment.areAllPermissionsGranted(this)
      }
      if (BuildPreferences.isOfflineNdkBuild) {
        return StatPreferences.statConsentDialogShown &&
            PermissionsFragment.areAllPermissionsGranted(this)
      }
      IJdkDistributionProvider.getInstance().installedDistributions.isNotEmpty() &&
          Environment.ANDROID_HOME.exists() &&
          StatPreferences.statConsentDialogShown &&
          PermissionsFragment.areAllPermissionsGranted(this)
    } catch (_: Throwable) {
      false
    }
  }
}
