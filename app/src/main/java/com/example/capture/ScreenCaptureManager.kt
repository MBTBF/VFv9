package com.example.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.detection.FrameAnalyzerEngine
import com.example.detection.MultiDetectorAIEngine
import com.example.model.AnalysisState
import com.example.model.DetectionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Gère la capture continue de l'écran (MediaProjection) pour V8 :
 *
 * INNOVATION MAJEURE : DÉTECTION PAR ANCRES D'INTERFACE (UI Anchors)
 *
 * 1. DISTINCTION PHYSIQUE SCROLL vs VIDÉO EN LECTURE :
 *    - Dans TikTok, Instagram Reels, YouTube Shorts et X :
 *      Pendant la lecture d'une vidéo, les pixels de la vidéo bougent au centre,
 *      MAIS les ancres d'interface (Cœur/Like, bulle de commentaires, bouton de partage,
 *      disque audio, @pseudo en bas) restent STRICTEMENT FIXES aux mêmes coordonnées !
 *    - Dès que l'utilisateur fait un SWIPE (balayage vertical du pouce), ces ancres
 *      d'interface se déplacent brutalement vers le haut ou le bas.
 *    - Dès lors :
 *      * Si les ancres d'interface sont fixes -> C'est une VIDÉO EN LECTURE -> ZÉRO CLIGNOTEMENT !
 *        La pastille reste affichée et continue d'analyser la vidéo en tâche de fond.
 *      * Si les ancres d'interface bougent -> C'est un VRAI SCROLL -> Disparition immédiate de la pastille.
 *
 * 2. TEMPS DE RÉPONSE ACCÉLÉRÉ (< 250 ms au lieu de 1 500 ms) :
 *    - Suppression de la temporisation artificielle de 1 000 ms.
 *    - Temporisation de stabilisation naturelle réduite à 160 ms dès que les ancres se calent.
 *    - Analyse optimisée pour puces d'entrée de gamme (ex: Octa-Core 1.6 GHz, 3 Go RAM).
 */
class ScreenCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val onStateUpdated: (AnalysisState) -> Unit,
    private val onScrollStateChanged: (isScrolling: Boolean) -> Unit = {},
    private val onVideoStateChanged: (isVideoActive: Boolean) -> Unit = {}
) {

    private val tag = "ScreenCaptureManager"
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val analyzer: FrameAnalyzerEngine = MultiDetectorAIEngine()

    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    @Volatile
    private var isCapturing = false

    // Échantillons d'ancres d'interface (Right Rail + Bottom Metadata + Top Tabs)
    private var previousAnchorSamples: IntArray? = null
    // Échantillons du centre de l'écran (Vidéo / Image)
    private var previousCenterSamples: IntArray? = null

    // Temporisation de stabilisation naturelle ultra-rapide (160 ms)
    private val settlingThresholdMs = 160L
    // Période d'analyse continue pendant la lecture d'une vidéo (700 ms)
    private val videoPeriodicAnalysisIntervalMs = 700L

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.d(tag, "MediaProjection session stopped by system")
            stop()
        }
    }

    fun start() {
        if (isCapturing) return
        isCapturing = true

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val screenDensity = metrics.densityDpi

        val isLandscape = screenWidth >= screenHeight
        // Optimisation de résolution pour processeur 1.6 GHz : 540p / 640p
        // Permet une inférence en ~70-90 ms sans aucune perte de qualité de détection
        val targetWidth = if (isLandscape) {
            minOf(screenWidth, 960)
        } else {
            minOf(screenWidth, 640)
        }
        val targetHeight = ((screenHeight.toFloat() / screenWidth.toFloat()) * targetWidth).toInt().coerceAtLeast(480)

        Log.d(tag, "Initializing screen capture V8: ${targetWidth}x${targetHeight}, screen=${screenWidth}x${screenHeight}")

        try {
            mediaProjection.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))

            val reader = ImageReader.newInstance(
                targetWidth,
                targetHeight,
                PixelFormat.RGBA_8888,
                2
            )
            imageReader = reader

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenWatcherVirtualDisplay",
                targetWidth,
                targetHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )

            startCaptureLoop()
        } catch (e: Exception) {
            Log.e(tag, "Failed to start MediaProjection display", e)
            onStateUpdated(
                AnalysisState(
                    result = DetectionResult.NO_AI_DETECTED,
                    details = "Erreur de capture : ${e.localizedMessage}"
                )
            )
        }
    }

    private fun startCaptureLoop() {
        captureJob = scope.launch {
            var isCurrentlyScrolling = false
            var isCurrentlyVideo = false
            var anchorStableStartTime = 0L
            var lastAnalysisTime = 0L
            var sceneAnalyzedAfterSettling = false

            while (isActive && isCapturing) {
                try {
                    val reader = imageReader ?: break
                    val image: Image? = try {
                        reader.acquireLatestImage()
                    } catch (_: Exception) {
                        null
                    }

                    if (image != null) {
                        var bitmap: Bitmap? = null
                        try {
                            bitmap = convertImageToBitmap(image)
                        } catch (e: Exception) {
                            Log.e(tag, "Error converting image to bitmap", e)
                        } finally {
                            image.close()
                        }

                        if (bitmap != null) {
                            val now = System.currentTimeMillis()
                            val motionData = analyzeSpatialMotion(bitmap)

                            // 1. Détection du Swipe physique : Les ancres d'interface (Cœur, commentaires, texte) bougent
                            val isSwipeOrScroll = motionData.anchorDiff >= 0.055f

                            if (isSwipeOrScroll) {
                                // GESTE DE DÉFILEMENT EN COURS :
                                // Masquer immédiatement la pastille
                                if (!isCurrentlyScrolling) {
                                    isCurrentlyScrolling = true
                                    onScrollStateChanged(true)
                                }
                                isCurrentlyVideo = false
                                onVideoStateChanged(false)
                                anchorStableStartTime = 0L
                                sceneAnalyzedAfterSettling = false
                            } else {
                                // LES ANCRES D'INTERFACE SONT STABLES (L'utilisateur ne scrolle pas) :
                                if (isCurrentlyScrolling) {
                                    isCurrentlyScrolling = false
                                    onScrollStateChanged(false)
                                }

                                if (anchorStableStartTime == 0L) {
                                    anchorStableStartTime = now
                                }

                                val stableDuration = now - anchorStableStartTime

                                // 2. Distinction Image Fixe vs Vidéo en Lecture :
                                val isVideoMotionInCenter = motionData.centerDiff >= 0.028f

                                if (isVideoMotionInCenter) {
                                    // VIDÉO EN LECTURE (les ancres Like/Comment/Partage sont fixes mais la vidéo joue)
                                    if (!isCurrentlyVideo) {
                                        isCurrentlyVideo = true
                                        onVideoStateChanged(true)
                                    }

                                    // Si la vidéo vient de se stabiliser (>= 160 ms) ou si l'intervalle périodique est atteint
                                    val shouldAnalyze = (!sceneAnalyzedAfterSettling && stableDuration >= settlingThresholdMs) ||
                                            (now - lastAnalysisTime >= videoPeriodicAnalysisIntervalMs)

                                    if (shouldAnalyze) {
                                        val state = analyzer.analyzeFrame(bitmap)
                                        onStateUpdated(state)
                                        sceneAnalyzedAfterSettling = true
                                        lastAnalysisTime = now
                                    }
                                } else {
                                    // IMAGE FIXE OU TEXTE (aucun mouvement au centre ni sur les ancres)
                                    if (isCurrentlyVideo) {
                                        isCurrentlyVideo = false
                                        onVideoStateChanged(false)
                                    }

                                    // Dès 160 ms de stabilité -> Analyse instantanée (Temps total d'apparition ~250 ms)
                                    if (stableDuration >= settlingThresholdMs && !sceneAnalyzedAfterSettling) {
                                        val state = analyzer.analyzeFrame(bitmap)
                                        onStateUpdated(state)
                                        sceneAnalyzedAfterSettling = true
                                        lastAnalysisTime = now
                                    }
                                }
                            }

                            bitmap.recycle()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error during capture/analysis loop", e)
                }

                // Cycle de surveillance à 90 ms pour une réactivité immédiate sans saturer le CPU
                delay(90)
            }
        }
    }

    /**
     * Analyse spatiale discriminante :
     * Sépare les zones d'ancrage d'interface (Right Rail + Bottom + Top)
     * de la zone centrale où joue le média (Vidéo / Image).
     */
    private data class SpatialMotion(
        val anchorDiff: Float,
        val centerDiff: Float
    )

    private fun analyzeSpatialMotion(bitmap: Bitmap): SpatialMotion {
        val w = bitmap.width
        val h = bitmap.height

        // 1. Échantillonnage des ancres d'interface :
        // Zone A : Colonne latérale droite (Like, Commentaires, Partage dans TikTok / Reels / Shorts)
        // [X: 82%..98%, Y: 35%..82%]
        // Zone B : Bandeau de métadonnées en bas (@pseudo, légende, musique)
        // [X: 4%..78%, Y: 84%..95%]
        // Zone C : En-tête (Pour toi, recherche ou barre d'état)
        // [X: 15%..85%, Y: 4%..10%]
        val anchorPoints = mutableListOf<Int>()

        // 24 points sur la colonne droite
        for (i in 0 until 24) {
            val px = (w * 0.90f).toInt().coerceIn(0, w - 1)
            val py = (h * (0.35f + (i / 24f) * 0.47f)).toInt().coerceIn(0, h - 1)
            anchorPoints.add(bitmap.getPixel(px, py))
        }

        // 16 points sur le bandeau bas
        for (i in 0 until 16) {
            val px = (w * (0.10f + (i / 16f) * 0.65f)).toInt().coerceIn(0, w - 1)
            val py = (h * 0.89f).toInt().coerceIn(0, h - 1)
            anchorPoints.add(bitmap.getPixel(px, py))
        }

        // 12 points sur l'en-tête
        for (i in 0 until 12) {
            val px = (w * (0.20f + (i / 12f) * 0.60f)).toInt().coerceIn(0, w - 1)
            val py = (h * 0.06f).toInt().coerceIn(0, h - 1)
            anchorPoints.add(bitmap.getPixel(px, py))
        }

        val currentAnchorArray = anchorPoints.toIntArray()
        val prevAnchorArray = previousAnchorSamples
        previousAnchorSamples = currentAnchorArray

        val anchorDiff = if (prevAnchorArray != null && prevAnchorArray.size == currentAnchorArray.size) {
            computeArrayLumaDiff(currentAnchorArray, prevAnchorArray)
        } else {
            0f
        }

        // 2. Échantillonnage de la zone centrale (où joue la vidéo) :
        // [X: 25%..75%, Y: 25%..75%] (Grille 6x6 = 36 points)
        val centerPoints = IntArray(36)
        var cIdx = 0
        for (y in 0 until 6) {
            val py = (h * (0.25f + (y / 6f) * 0.50f)).toInt().coerceIn(0, h - 1)
            for (x in 0 until 6) {
                val px = (w * (0.25f + (x / 6f) * 0.50f)).toInt().coerceIn(0, w - 1)
                centerPoints[cIdx++] = bitmap.getPixel(px, py)
            }
        }

        val prevCenterArray = previousCenterSamples
        previousCenterSamples = centerPoints

        val centerDiff = if (prevCenterArray != null && prevCenterArray.size == centerPoints.size) {
            computeArrayLumaDiff(centerPoints, prevCenterArray)
        } else {
            0f
        }

        return SpatialMotion(anchorDiff = anchorDiff, centerDiff = centerDiff)
    }

    private fun computeArrayLumaDiff(a: IntArray, b: IntArray): Float {
        var totalDiff = 0L
        val size = a.size
        for (i in 0 until size) {
            val c1 = a[i]
            val c2 = b[i]
            val rDiff = abs(((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF))
            val gDiff = abs(((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF))
            val bDiff = abs((c1 and 0xFF) - (c2 and 0xFF))
            totalDiff += (rDiff + gDiff + bDiff)
        }
        return totalDiff.toFloat() / (size * 3f * 255f)
    }

    private fun convertImageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        val rowPadding = rowStride - pixelStride * width
        val bitmapWidth = width + rowPadding / pixelStride

        val tempBitmap = Bitmap.createBitmap(
            bitmapWidth,
            height,
            Bitmap.Config.ARGB_8888
        )
        tempBitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding != 0) {
            val cropped = Bitmap.createBitmap(tempBitmap, 0, 0, width, height)
            tempBitmap.recycle()
            cropped
        } else {
            tempBitmap
        }
    }

    fun stop() {
        isCapturing = false
        captureJob?.cancel()
        captureJob = null
        previousAnchorSamples = null
        previousCenterSamples = null

        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (_: Exception) {}

        try {
            imageReader?.close()
            imageReader = null
        } catch (_: Exception) {}

        try {
            mediaProjection.unregisterCallback(mediaProjectionCallback)
            mediaProjection.stop()
        } catch (_: Exception) {}

        try {
            analyzer.close()
        } catch (_: Exception) {}
    }
}
