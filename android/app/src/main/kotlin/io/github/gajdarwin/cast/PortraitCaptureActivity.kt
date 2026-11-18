package io.github.gajdarwin.cast

import com.journeyapps.barcodescanner.CaptureActivity

/** A dedicated portrait scanner keeps TV pairing comfortable with one hand. */
class PortraitCaptureActivity : CaptureActivity(){
    override fun attachBaseContext(base:android.content.Context){super.attachBaseContext(AppLanguage.wrap(base))}
}
