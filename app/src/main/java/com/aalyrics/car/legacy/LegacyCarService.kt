package com.aalyrics.car.legacy

import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService

/**
 * Entry point for Android Auto's legacy "projection" app type (the unreleased Android Auto SDK,
 * the same route Fermata Auto uses). Unlike Car App Library apps, these run in real cars when
 * the "Unknown sources" developer option is on, so a sideloaded build can show full-screen lyrics.
 */
class LegacyCarService : CarActivityService() {
    override fun getCarActivity(): Class<out CarActivity> = LegacyLyricsActivity::class.java
}
