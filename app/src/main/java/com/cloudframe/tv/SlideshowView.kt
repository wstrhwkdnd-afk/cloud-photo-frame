package com.cloudframe.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Full-screen crossfading slideshow, shared by the screensaver and the in-app preview. */
class SlideshowView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val app = context.applicationContext as FrameApp
    private val prefs = app.prefs
    private val images = arrayOf(newImage(), newImage())
    private var current = 0
    private val message = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        gravity = Gravity.CENTER
        setPadding(dp(48), 0, dp(48), 0)
    }
    private val clockBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        alpha = 0.8f
        addView(TextClock(context).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            setShadowLayer(12f, 0f, 2f, Color.BLACK)
        })
        addView(TextClock(context).apply {
            format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, d MMMM"
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setShadowLayer(10f, 0f, 2f, Color.BLACK)
        })
    }
    private var job: Job? = null

    init {
        setBackgroundColor(Color.BLACK)
        images.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(clockBox, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(48), dp(32), dp(56), dp(40)) })
    }

    fun start(scope: CoroutineScope) {
        stop()
        clockBox.visibility = if (prefs.showClock) VISIBLE else GONE
        val scale = if (prefs.fillScreen) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        images.forEach { it.scaleType = scale }
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun CoroutineScope.run() {
        if (!app.api.signedIn && prefs.folderId.isEmpty()) return showMessage(context.getString(R.string.msg_sign_in))
        if (prefs.folderId.isEmpty()) return showMessage(context.getString(R.string.msg_choose_folder))
        showMessage(context.getString(R.string.msg_loading))

        val photos = try {
            app.library.photos()
        } catch (e: DegooApi.AuthException) {
            return showMessage(context.getString(R.string.msg_session_expired))
        } catch (e: Exception) {
            return showMessage(context.getString(R.string.msg_error, e.message ?: "unknown error"))
        }
        if (photos.isEmpty()) return showMessage(context.getString(R.string.msg_no_photos, prefs.folderName))

        var order = arrange(if (prefs.sessionExpired) savedOnly(photos) else photos)
        if (order.isEmpty()) return showMessage(context.getString(R.string.msg_session_expired))
        val slideMs = prefs.slideSeconds * 1000L
        var index = 0
        var failures = 0
        var next = async { load(order[index]) }

        while (isActive) {
            val drawable = next.await()
            index = (index + 1) % order.size
            next = async { load(order[index]) }   // download the next photo while this one shows
            if (drawable == null) {
                if (++failures >= minOf(order.size, 10)) {
                    // Offline or signed out: carry on with the photos already saved on the TV
                    val saved = savedOnly(order)
                    if (saved.isNotEmpty() && saved.size < order.size) {
                        order = arrange(saved); index = 0
                        next.cancel(); next = async { load(order[index]) }
                    } else {
                        showMessage(context.getString(R.string.msg_offline)); delay(60_000)
                    }
                    failures = 0
                }
                continue
            }
            failures = 0
            show(drawable, slideMs)
            delay(slideMs)
        }
    }

    private fun arrange(list: List<PhotoLibrary.Photo>) =
        if (prefs.shuffle) list.shuffled() else list.sortedBy { it.created }

    private suspend fun savedOnly(list: List<PhotoLibrary.Photo>) =
        withContext(Dispatchers.IO) { list.filter(::isSaved) }

    /** True if this photo is already in the TV's photo cache, so it can be shown without Degoo. */
    private fun isSaved(photo: PhotoLibrary.Photo): Boolean =
        context.imageLoader.diskCache?.openSnapshot("degoo-${photo.id}")?.use { true } ?: false

    private suspend fun load(photo: PhotoLibrary.Photo): Drawable? {
        suspend fun fetch(url: String): Drawable? {
            val request = ImageRequest.Builder(context)
                .data(url)
                // Signed URLs change, so cache by Degoo's file ID instead
                .diskCacheKey("degoo-${photo.id}")
                .memoryCacheKey("degoo-${photo.id}")
                .size(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                .precision(Precision.INEXACT)
                .build()
            return (context.imageLoader.execute(request) as? SuccessResult)?.drawable
        }
        if (photo.url.isNotEmpty() || isSaved(photo)) fetch(photo.url)?.let { return it }
        if (prefs.sessionExpired) return null
        val fresh = runCatching { app.library.freshUrl(photo) }.getOrNull() ?: return null
        return if (fresh.isNotEmpty()) fetch(fresh) else null
    }

    private fun show(drawable: Drawable, slideMs: Long) {
        message.visibility = GONE
        val incoming = images[1 - current]
        val outgoing = images[current]
        current = 1 - current

        incoming.animate().cancel()
        incoming.setImageDrawable(drawable)
        incoming.alpha = 0f
        incoming.scaleX = 1f; incoming.scaleY = 1f
        incoming.animate().alpha(1f).setDuration(FADE_MS).start()
        outgoing.animate().alpha(0f).setDuration(FADE_MS).start()
        if (prefs.slowZoom) {
            incoming.animate().scaleX(1.08f).scaleY(1.08f)
                .setDuration(slideMs + FADE_MS).setInterpolator(LinearInterpolator()).start()
        }
        // Nudge the clock a little each slide so it doesn't burn into OLED screens
        clockBox.translationX = Random.nextInt(-dp(16), dp(16)).toFloat()
        clockBox.translationY = Random.nextInt(-dp(12), dp(12)).toFloat()
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = VISIBLE
        images.forEach { it.setImageDrawable(null) }
    }

    private fun newImage() = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val FADE_MS = 1200L
    }
}
