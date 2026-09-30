package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.capture.ScreenCaptureManager
import com.example.licensing.TrialLicenseManager
import com.example.overlay.FloatingPillOverlay
import com.example.state.ServiceStateHolder

/**
 * SERVICE FOREGROUND DE SURVEILLANCE ÉCRAN
 *
 * HISTORIQUE DES VERSIONS :
 * - v1.0 à v6.0 : Pipeline MediaProjection, capture et moteur forensique 100% local.
 * - v7.0 : Écouteur de scroll et temporisation 1s.
 * - v8.0 : Détection par ancres d'interface (TikTok/Reels), zéro clignotement vidéo.
 * - v9.0 : Vérification stricte de licence d'évaluation (7 jours) :
 *          Si la période d'essai est expirée et que l'app n'est pas activée,
 *          le service refuse immédiatement de démarrer et coupe toute capture.
 */
class ScreenWatcherService : Service() {

    companion object {
        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_watcher_channel"
        private const val TAG = "ScreenWatcherService"
    }

    private var screenCaptureManager: ScreenCaptureManager? = null
    private var floatingOverlay: FloatingPillOverlay? = null
    private var mediaProjection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        floatingOverlay = FloatingPillOverlay(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stop requested via action")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // Vérification de la licence d'évaluation (7 jours)
                if (!TrialLicenseManager.isAppUsable(this)) {
                    Log.e(TAG, "Période d'évaluation de 7 jours expirée. Démarrage refusé.")
                    stopSelf()
                    return START_NOT_STICKY
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != 0 && resultData != null) {
                    startForegroundWithNotification()
                    initMediaProjectionAndCapture(resultCode, resultData)
                    ServiceStateHolder.setServiceRunning(true)
                } else {
                    Log.e(TAG, "Missing MediaProjection resultCode or data")
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val stopIntent = Intent(this, ScreenWatcherService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainActivityIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Détection IA active")
            .setContentText("Pastille flottante (🟢 Aucun indice / 🔴 IA détectée)")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arrêter", stopPendingIntent)
            .build()

        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundServiceType
        )
    }

    private fun initMediaProjectionAndCapture(resultCode: Int, resultData: Intent) {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                Log.e(TAG, "Failed to obtain MediaProjection instance")
                stopSelf()
                return
            }
            mediaProjection = projection

            // Show floating overlay over other apps
            floatingOverlay?.show()

            // Initialize capture manager V8 with UI Anchor tracking and zero video flickering
            screenCaptureManager = ScreenCaptureManager(
                context = this,
                mediaProjection = projection,
                onStateUpdated = { state ->
                    floatingOverlay?.onAnalysisReady(state)
                    ServiceStateHolder.updateAnalysis(state)
                },
                onScrollStateChanged = { isScrolling ->
                    if (isScrolling) {
                        floatingOverlay?.onScrollStarted()
                    }
                    ServiceStateHolder.setScrolling(isScrolling)
                },
                onVideoStateChanged = { isVideoActive ->
                    ServiceStateHolder.setVideoActive(isVideoActive)
                }
            ).also {
                it.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing capture: ${e.localizedMessage}", e)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Surveillance écran",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification requise pour la capture d'écran et la pastille"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ScreenWatcherService destroying")
        screenCaptureManager?.stop()
        screenCaptureManager = null

        floatingOverlay?.hide()
        floatingOverlay = null

        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Exception) {}

        ServiceStateHolder.setServiceRunning(false)
    }
}
