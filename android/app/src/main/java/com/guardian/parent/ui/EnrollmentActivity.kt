package com.guardian.parent.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.guardian.parent.BuildConfig
import com.guardian.parent.R
import com.guardian.parent.core.Logger
import com.guardian.parent.data.ApiException
import com.guardian.parent.data.DeviceIdentity
import com.guardian.parent.data.EnrollmentResult
import com.guardian.parent.data.LocalStore
import com.guardian.parent.data.NetworkException
import com.guardian.parent.data.Repo
import com.guardian.parent.service.MonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The first screen of the app: it pairs the phone with the parent's server.
 *
 * The screen explains in plain words what the app does and that the user can
 * always see that the monitoring is running.
 */
class EnrollmentActivity : AppCompatActivity() {

    private lateinit var store: LocalStore
    private lateinit var serverInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var enrollButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var errorText: TextView

    /** Result of the registration call. */
    private sealed class Outcome {
        data class Success(val result: EnrollmentResult) : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = LocalStore(this)
        if (store.isEnrolled) {
            // The phone is already paired: opening the app icon shows the
            // dashboard instead of asking for a pairing code again.
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_enrollment)

        serverInput = findViewById(R.id.serverUrlInput)
        codeInput = findViewById(R.id.codeInput)
        enrollButton = findViewById(R.id.enrollButton)
        progress = findViewById(R.id.progress)
        errorText = findViewById(R.id.errorText)

        // The address is pre-filled with the server that this build was made for,
        // so the user normally only has to type the pairing code.
        serverInput.setText(store.serverUrl ?: BuildConfig.DEFAULT_SERVER_URL)
        enrollButton.setOnClickListener { enroll() }
    }

    private fun enroll() {
        val url = serverInput.text.toString().trim()
        val code = codeInput.text.toString().trim()

        if (url.isEmpty()) {
            showError(getString(R.string.error_url_empty))
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            showError(getString(R.string.error_url_invalid))
            return
        }
        if (code.length != 8 || code.any { !it.isDigit() }) {
            showError(getString(R.string.error_code_invalid))
            return
        }

        val identity = DeviceIdentity(
            deviceName = store.deviceName.ifBlank { defaultDeviceName() },
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            appVersion = BuildConfig.VERSION_NAME
        )

        setBusy(true)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    Outcome.Success(Repo(applicationContext).enroll(url, code, identity))
                } catch (e: ApiException) {
                    // The contract returns the human readable reason in `error.message`; show it as-is.
                    val msg = e.message
                    Outcome.Failure(if (msg.isNullOrBlank()) getString(R.string.error_generic) else msg)
                } catch (e: NetworkException) {
                    Outcome.Failure(getString(R.string.error_network))
                } catch (e: Exception) {
                    Logger.e("The pairing failed", e)
                    val msg = e.message
                    Outcome.Failure(if (msg.isNullOrBlank()) getString(R.string.error_generic) else msg)
                }
            }
            setBusy(false)
            handleOutcome(outcome, url, identity)
        }
    }

    private fun handleOutcome(outcome: Outcome, url: String, identity: DeviceIdentity) {
        when (outcome) {
            is Outcome.Failure -> showError(outcome.message)
            is Outcome.Success -> {
                if (outcome.result.deviceId <= 0L || outcome.result.deviceToken.isBlank()) {
                    showError(getString(R.string.error_generic))
                    return
                }
                store.saveEnrollment(outcome.result, url)
                store.deviceName = identity.deviceName
                Logger.i("The device was paired, id=${outcome.result.deviceId}")

                MonitorService.start(this)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    private fun setBusy(busy: Boolean) {
        enrollButton.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) errorText.visibility = View.GONE
    }

    private fun defaultDeviceName(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { getString(R.string.app_short_name) }
}
