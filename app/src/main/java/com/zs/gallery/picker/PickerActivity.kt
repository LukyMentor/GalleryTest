package com.zs.gallery.picker

import android.content.Intent
import android.os.Bundle
import com.zs.gallery.MainActivity

/**
 * The whole Gallery M, opened by another app to pick photos (see [PickMode]). A separate
 * activity only because the main one is single-task, and those can't return a result.
 */
class PickerActivity : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val caller = callingActivity?.packageName?.let { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
                .getOrNull()
        }
        PickMode.start(this, intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false), intent.type, caller)
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        PickMode.stop(this)
        super.onDestroy()
    }
}
