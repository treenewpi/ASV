package com.icode.asmviewer

import android.app.Application
import com.icode.asmviewer.data.AppLocale

class AsmViewerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocale.applyStored(this)
    }
}
