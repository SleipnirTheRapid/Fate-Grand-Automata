package com.mathewsachin.fategrandautomata.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.Activity.RESULT_OK
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.Toast
import com.mathewsachin.fategrandautomata.StorageDirs
import com.mathewsachin.fategrandautomata.dagger.service.ScriptRunnerServiceComponent
import com.mathewsachin.fategrandautomata.dagger.service.ScriptRunnerServiceModule
import com.mathewsachin.fategrandautomata.imaging.MediaProjectionScreenshotService
import com.mathewsachin.fategrandautomata.root.RootScreenshotService
import com.mathewsachin.fategrandautomata.root.SuperUser
import com.mathewsachin.fategrandautomata.scripts.enums.GameServerEnum
import com.mathewsachin.fategrandautomata.scripts.prefs.IPreferences
import com.mathewsachin.fategrandautomata.util.*
import com.mathewsachin.libautomata.IPlatformImpl
import com.mathewsachin.libautomata.IScreenshotService
import com.mathewsachin.libautomata.messageAndStackTrace
import javax.inject.Inject

class ScriptRunnerService : AccessibilityService() {
    companion object {
        var Instance: ScriptRunnerService? = null
    }

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var prefs: IPreferences

    @Inject
    lateinit var mediaProjectionManager: MediaProjectionManager

    @Inject
    lateinit var storageDirs: StorageDirs

    @Inject
    lateinit var userInterface: ScriptRunnerUserInterface

    @Inject
    lateinit var scriptManager: ScriptManager

    @Inject
    lateinit var notification: ScriptRunnerNotification

    @Inject
    lateinit var platformImpl: IPlatformImpl

    private val screenOffReceiver = ScreenOffReceiver()

    override fun onUnbind(intent: Intent?): Boolean {
        stop()

        screenOffReceiver.unregister(this)
        Instance = null

        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()

        Instance = null
    }

    val wantsMediaProjectionToken: Boolean get() = !prefs.useRootForScreenshots

    var serviceState: ServiceState = ServiceState.Stopped
        private set

    fun start(MediaProjectionToken: Intent? = null): Boolean {
        if (serviceState is ServiceState.Started) {
            return false
        }

        val screenshotService = registerScreenshot(MediaProjectionToken)
            ?: return false

        userInterface.show()

        serviceState = ServiceState.Started(screenshotService)

        return true
    }

    private fun registerScreenshot(MediaProjectionToken: Intent?): IScreenshotService? {
        return try {
            if (MediaProjectionToken != null) {
                val mediaProjection =
                    mediaProjectionManager.getMediaProjection(RESULT_OK, MediaProjectionToken)
                MediaProjectionScreenshotService(
                    mediaProjection!!,
                    userInterface.mediaProjectionMetrics,
                    storageDirs
                )
            } else RootScreenshotService(SuperUser(), storageDirs, platformImpl)
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
            null
        }
    }

    fun stop(): Boolean {
        scriptManager.stopScript()

        serviceState.let {
            if (it is ServiceState.Started) {
                it.screenshotService.close()
            } else return false
        }

        imageLoader.clearImageCache()

        userInterface.hide()
        serviceState = ServiceState.Stopped

        notification.hide()

        return true
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // from https://stackoverflow.com/a/43310945/5971497

        val restartServiceIntent = Intent(applicationContext, javaClass)
        restartServiceIntent.setPackage(packageName)

        val restartServicePendingIntent = PendingIntent.getService(
            applicationContext,
            1,
            restartServiceIntent,
            PendingIntent.FLAG_ONE_SHOT
        )
        val alarmService = applicationContext.getSystemService(ALARM_SERVICE) as AlarmManager
        alarmService.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000,
            restartServicePendingIntent
        )

        super.onTaskRemoved(rootIntent)
    }

    fun registerScriptCtrlBtnListeners(scriptCtrlBtn: ImageButton) {
        scriptCtrlBtn.setThrottledClickListener {
            val state = serviceState

            if (state is ServiceState.Started) {
                when (scriptManager.scriptState) {
                    is ScriptState.Started -> scriptManager.stopScript()
                    is ScriptState.Stopped -> {
                        // Overwrite the server in the preferences with the detected one, if possible
                        currentFgoServer?.let { server -> prefs.gameServer = server }

                        scriptManager.startScript(this, state.screenshotService, component)
                    }
                }
            }
        }
    }

    fun registerScriptPauseBtnListeners(scriptPauseBtn: ImageButton) {
        scriptPauseBtn.setThrottledClickListener {
            if (serviceState is ServiceState.Started) {
                val scriptState = scriptManager.scriptState

                if (scriptState is ScriptState.Started) {
                    if (scriptState.paused) {
                        scriptManager.resumeScript()
                    } else scriptManager.pauseScript()
                }
            }
        }
    }

    private lateinit var component: ScriptRunnerServiceComponent

    override fun onServiceConnected() {
        Instance = this
        component = appComponent.scriptRunnerServiceComponent()
            .scriptRunnerServiceModule(
                ScriptRunnerServiceModule(
                    this
                )
            )
            .build()

        component.inject(this)

        screenOffReceiver.register(this) {
            scriptManager.stopScript()
        }

        super.onServiceConnected()
    }

    override fun onInterrupt() {}

    private var currentFgoServer: GameServerEnum? = null

    /**
     * This method is called on any subscribed [AccessibilityEvent] in script_runner_service.xml.
     *
     * When the app in the foreground changes, this method will check if the foreground app is one
     * of the FGO APKs and will store that information into [currentFgoServer].
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val foregroundAppName = event.packageName?.toString()
                    ?: return

                GameServerEnum.fromPackageName(foregroundAppName)
                    ?.let { currentFgoServer = it }
            }
        }
    }

    fun showMessageBox(Title: String, Message: String, Error: Exception? = null) {
        ScriptRunnerDialog(userInterface).apply {
            setTitle(Title)
            setMessage(Message)
            setPositiveButton(getString(android.R.string.ok)) { }

            if (Error != null) {
                setNeutralButton("Copy") {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clipData = ClipData.newPlainText("Error", Error.messageAndStackTrace)

                    clipboard.setPrimaryClip(clipData)
                }
            }

            show()
        }
    }
}
