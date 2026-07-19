package com.gemmark.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.gemmark.app.di.AppContainer

class GemmarkApplication : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * App-level foreground tracking across the multi-activity structure:
     * a running benchmark auto-pauses when the whole app leaves the screen
     * (AICore blocks background inference), not when switching activities.
     */
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) container.sessionManager.onAppForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                if (--startedActivities == 0) container.sessionManager.onAppBackground()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
