package com.cloudframe.tv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home screen: sign in, pick a folder, tweak the slideshow, and preview it. */
class SetupActivity : AppCompatActivity() {
    private val app get() = application as FrameApp
    private val prefs get() = app.prefs
    private fun <T : View> view(id: Int): T = findViewById(id)

    private val setupServer by lazy { SetupServer(applicationContext) { form -> signInFromPhone(form) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        view<Button>(R.id.signIn).setOnClickListener { signIn() }
        view<Button>(R.id.signOut).setOnClickListener {
            app.api.signOut(); app.library.clear(); render()
        }
        view<Button>(R.id.chooseFolder).setOnClickListener {
            startActivity(Intent(this, FolderPickerActivity::class.java))
        }
        view<Button>(R.id.slideTime).setOnClickListener {
            val c = Prefs.SLIDE_CHOICES
            prefs.slideSeconds = c[(c.indexOf(prefs.slideSeconds) + 1) % c.size]; render()
        }
        view<Button>(R.id.shuffle).setOnClickListener { prefs.shuffle = !prefs.shuffle; render() }
        view<Button>(R.id.fillScreen).setOnClickListener { prefs.fillScreen = !prefs.fillScreen; render() }
        view<Button>(R.id.showClock).setOnClickListener { prefs.showClock = !prefs.showClock; render() }
        view<Button>(R.id.slowZoom).setOnClickListener { prefs.slowZoom = !prefs.slowZoom; render() }
        view<Button>(R.id.preview).setOnClickListener {
            startActivity(Intent(this, SlideshowActivity::class.java))
        }
        view<Button>(R.id.refresh).setOnClickListener { refreshPhotos() }
        view<Button>(R.id.screensaverSettings).setOnClickListener { openScreensaverSettings() }
    }

    override fun onResume() {
        super.onResume()
        render()
        if (!app.api.signedIn || prefs.sessionExpired || !app.credentials.hasCredentials) startPhoneSetup()
        if (app.api.signedIn && prefs.folderId.isNotEmpty()) countPhotos()
    }

    override fun onPause() {
        setupServer.stop()
        super.onPause()
    }

    private fun render() {
        val signedIn = app.api.signedIn && !prefs.sessionExpired
        // Signed in from before auto sign-in existed: one more sign-in saves the password
        val needsPassword = signedIn && !app.credentials.hasCredentials
        when {
            prefs.sessionExpired -> status(getString(R.string.session_expired_setup))
            needsPassword -> status(getString(R.string.turn_on_auto_sign_in))
        }
        view<View>(R.id.signInForm).visibility = if (signedIn && !needsPassword) View.GONE else View.VISIBLE
        view<View>(R.id.signedInRow).visibility = if (signedIn) View.VISIBLE else View.GONE
        view<TextView>(R.id.accountText).text =
            getString(R.string.signed_in_as, prefs.email.ifEmpty { getString(R.string.your_account) })
        view<Button>(R.id.chooseFolder).isEnabled = signedIn
        view<TextView>(R.id.folderText).text =
            if (prefs.folderId.isEmpty()) getString(R.string.no_folder) else prefs.folderName
        view<Button>(R.id.slideTime).text = getString(R.string.opt_slide_time, label(prefs.slideSeconds))
        view<Button>(R.id.shuffle).text = getString(R.string.opt_shuffle, onOff(prefs.shuffle))
        view<Button>(R.id.fillScreen).text = getString(R.string.opt_fill, onOff(prefs.fillScreen))
        view<Button>(R.id.showClock).text = getString(R.string.opt_clock, onOff(prefs.showClock))
        view<Button>(R.id.slowZoom).text = getString(R.string.opt_zoom, onOff(prefs.slowZoom))
        view<Button>(R.id.preview).isEnabled = prefs.folderId.isNotEmpty()
        view<Button>(R.id.refresh).isEnabled = signedIn && prefs.folderId.isNotEmpty()
        if (!app.api.signedIn) view<TextView>(R.id.photoCount).text = ""
    }

    private fun signIn() {
        val email = view<EditText>(R.id.email).text.toString().trim()
        val password = view<EditText>(R.id.password).text.toString()
        if (email.isEmpty() || password.isEmpty()) return status(getString(R.string.enter_email_password))
        status(getString(R.string.signing_in))
        lifecycleScope.launch {
            try {
                app.api.login(email, password)
                view<EditText>(R.id.password).setText("")
                status("")
                setupServer.stop()
                render()
                view<Button>(R.id.chooseFolder).requestFocus()
            } catch (e: Exception) {
                status(e.message ?: getString(R.string.sign_in_failed))
            }
        }
    }

    /** Called from the phone setup page (on a background thread). Returns an error message or null. */
    private suspend fun signInFromPhone(form: Map<String, String>): String? = try {
        val access = form["access_token"].orEmpty()
        val refresh = form["refresh_token"].orEmpty()
        if (access.isNotBlank() || refresh.isNotBlank()) {
            app.api.useTokens(access, refresh)
            app.api.folders("0")  // check the token actually works
        } else {
            val email = form["email"].orEmpty().trim()
            val password = form["password"].orEmpty()
            if (email.isEmpty() || password.isEmpty()) throw Exception(getString(R.string.enter_email_password))
            app.api.login(email, password)
        }
        withContext(Dispatchers.Main) { status(""); render(); setupServer.stop() }
        null
    } catch (e: Exception) {
        e.message ?: getString(R.string.sign_in_failed)
    }

    private fun startPhoneSetup() {
        setupServer.start()
        val address = setupServer.address
        view<TextView>(R.id.phoneSetup).text = if (address == null) getString(R.string.phone_setup_unavailable)
        else getString(R.string.phone_setup, address, setupServer.pin)
    }

    private fun countPhotos(force: Boolean = false) {
        val count = view<TextView>(R.id.photoCount)
        count.text = getString(R.string.counting_photos)
        lifecycleScope.launch {
            try {
                val photos = app.library.photos(forceRefresh = force) { n ->
                    lifecycleScope.launch { count.text = getString(R.string.scanning_photos, n) }
                }
                count.text = resources.getQuantityString(R.plurals.photo_count, photos.size, photos.size)
                if (prefs.sessionExpired) { render(); startPhoneSetup() }
            } catch (e: DegooApi.AuthException) {
                render(); startPhoneSetup()
                count.text = e.message
            } catch (e: Exception) {
                count.text = getString(R.string.msg_error, e.message ?: "")
            }
        }
    }

    private fun refreshPhotos() = countPhotos(force = true)

    private fun openScreensaverSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_screensaver_settings, Toast.LENGTH_LONG).show()
        }
    }

    private fun status(text: String) {
        view<TextView>(R.id.signInStatus).text = text
    }

    private fun onOff(b: Boolean) = getString(if (b) R.string.on else R.string.off)
    private fun label(seconds: Int) = if (seconds < 60) "${seconds}s" else "${seconds / 60} min"
}
