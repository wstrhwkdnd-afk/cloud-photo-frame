package com.cloudframe.tv

import android.service.dreams.DreamService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/** The screensaver. Android starts this when the TV has been idle. */
class PhotoDreamService : DreamService() {
    private val scope = MainScope()
    private lateinit var slideshow: SlideshowView

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false   // any remote button press wakes the TV
        isFullscreen = true
        isScreenBright = true
        slideshow = SlideshowView(this)
        setContentView(slideshow)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        slideshow.start(scope)
    }

    override fun onDreamingStopped() {
        slideshow.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }
}
