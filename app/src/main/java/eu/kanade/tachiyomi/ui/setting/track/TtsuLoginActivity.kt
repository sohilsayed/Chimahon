package eu.kanade.tachiyomi.ui.setting.track

import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.sync.ttsu.TtsuOAuthService
import tachiyomi.core.common.util.lang.launchIO

class TtsuLoginActivity : BaseOAuthLoginActivity() {

    override fun handleResult(uri: Uri) {
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")

        if (code != null) {
            lifecycleScope.launchIO {
                val service = TtsuOAuthService(this@TtsuLoginActivity)
                val success = service.handleAuthorizationCode(code)

                runOnUiThread {
                    if (success) {
                        Toast.makeText(
                            this@TtsuLoginActivity,
                            "TTU Sync connected successfully",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        Toast.makeText(
                            this@TtsuLoginActivity,
                            "TTU Sync connection failed",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    returnToSettings()
                }
            }
        } else if (error != null) {
            Toast.makeText(
                this@TtsuLoginActivity,
                "TTU Sync login failed: $error",
                Toast.LENGTH_LONG,
            ).show()
            returnToSettings()
        } else {
            returnToSettings()
        }
    }
}
