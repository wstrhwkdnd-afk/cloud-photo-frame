package com.cloudframe.tv

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope

/** Runs the slideshow on demand from the app (press Back to exit). */
class SlideshowActivity : AppCompatActivity() {
    private lateinit var slideshow: SlideshowView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        slideshow = SlideshowView(this)
        setContentView(slideshow)
    }

    override fun onStart() {
        super.onStart()
        slideshow.start(lifecycleScope)
    }

    override fun onStop() {
        slideshow.stop()
        super.onStop()
    }
}
