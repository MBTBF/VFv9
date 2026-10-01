package com.example.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Gère la capture continue de l'écran (MediaProjection) — VERSION 9.1 (30/09/2026)
 *
 * HISTORIQUE :
 * - V8 : Détection par ancres d'interface (TikTok / Reels / Shorts / X), zéro clignotement
 *        vidéo, stabilisation 160 ms.
 * - V9 : Inchangé (la V9 a porté sur la licence d'évaluation).
 * - V9.1 : CORRECTION DE LA FLUIDITÉ DES IMAGES FIXES AU SCROLL
 *   1. HORLOGE INDÉPENDANTE DES TRAMES (bug principal) : en V9, la stabilité n'était
 *      évaluée qu'à l'arrivée d'une nouvelle trame. Or l'écran virtuel n'envoie plus de
 *      trame quand l'écran est immobile : après un scroll, le point pouvait rester caché
 *      jusqu'au prochain changement quelconque (horloge, animation…). Désormais la
 *      dernière trame est conservée et la stabilité est évaluée à chaque cycle (90 ms),
 *      même sans nouvelle trame. Absence de trame = écran immobile.
 *   2. ANALYSE ASYNCHRONE ET ANNULABLE : l'analyse ne bloque plus la boucle de capture.
 *      Chaque scroll incrémente un numéro de scène ; un verdict qui arrive pour une scène
 *      dépassée est IGNORÉ (il ne peut plus faire réapparaître le point pendant un scroll,
 *      ni afficher le verdict de l'image précédente sur la nouvelle). Publication du verdict
 *      et début de scroll sont sérialisés (verrou) pour éliminer toute course.
 *      Une seule analyse à la fois (protection CPU sur puces d'entrée de gamme).
 *   3. HYSTÉRÉSIS DE FIN DE SCROLL : le scroll n'est déclaré terminé qu'après 220 ms sans
 *      aucun indice de défilement (fin de « fling » lente incluse).
 *   4. MESURE DU DÉCALAGE (vertical et horizontal) : en plus des ancres V8, chaque trame est
 *      comparée à la précédente par profils de luminance (lignes / colonnes). Un contenu qui
 *      a simplement GLISSÉ (scroll de fil Instagram, carrousel) est reconnu comme un scroll,
 *      même lent ou sur fond uniforme, et n'est plus pris pour une vidéo.
 *      Pendant une vidéo déjà établie, un décalage n'est retenu que si les ancres bougent
 *      aussi (protège la règle V8 « zéro clignotement » lors des panoramiques de caméra).
 *   5. PASTILLE EXCLUE : la zone de la pastille (fournie par overlayBoundsProvider) est
 *      ignorée par la détection de mouvement et masquée dans l'image envoyée aux détecteurs.
 *      Une trame où la pastille a changé de zone n'est pas utilisée pour classer le mouvement.
 *   6. FIN DE VIDÉO / CARROUSEL : quand le centre cesse de bouger, la scène fixe obtenue est
 *      ré-analysée (en V9 elle conservait le verdict calculé pendant le mouvement).
 *   7. Le CoroutineScope utilise un SupervisorJob : une erreur d'analyse ne peut plus
 *      arrêter la boucle de capture.
 *   Inchangés : résolution de capture (640 px / 960 px), seuils V8 des ancres (0,055) et du
 *   centre (0,028), positions d'échantillonnage V8, intervalle vidéo 700 ms, cycle 90 ms,
 *   conversion Image -> Bitmap, arrêt / libération des ressources.
 *
 * PRINCIPE V8 CONSERVÉ — DÉTECTION PAR ANCRES D'INTERFACE (UI Anchors) :
 *    - Pendant la lecture d'une vidéo, les pixels bougent au centre MAIS les ancres
 *      d'interface (Like, commentaires, partage, disque audio, @pseudo) restent fixes.
 *    - Lors d'un swipe, ces ancres se déplacent brutalement.
 *      * Ancres fixes + centre qui bouge -> VIDÉO EN LECTURE -> la pastille reste affichée.
 *      * Ancres qui bougent (ou contenu qui glisse) -> SCROLL -> disparition immédiate.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val onStateUpdated: (AnalysisState) -> Unit,
    private val onScrollStateChanged: (isScrolling: Boolean) -> Unit = {},
    private val onVideoStateChanged: (isVideoActive: Boolean) -> Unit = {},
    // V9.1 : zone occupée par la pastille, en pixels écran réels (null = inconnue)
    private val overlayBoundsProvider: () -> Rect? = { null }
) {

    private val tag = "ScreenCaptureManager"
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val analyzer: FrameAnalyzerEngine = MultiDetectorAIEngine()

    private var captureJob: Job? = null
    // V9.1 : SupervisorJob -> l'échec d'une analyse n'interrompt pas la capture
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isCapturing = false

    // Dimensions réelles de l'écran (pour convertir la zone de la pastille en coordonnées capture)
    private var screenWidthPx = 0
    private var screenHeightPx = 0

    // Échantillons d'ancres d'interface (Right Rail + Bottom Metadata + Top Tabs)
    private var previousAnchorSamples: IntArray? = null
    // Échantillons du centre de l'écran (Vidéo / Image)
    private var previousCenterSamples: IntArray? = null
    // V9.1 : profils de luminance de la trame précédente (mesure du décalage)
    private var previousRowProfile: FloatArray? = null
    private var previousColProfile: FloatArray? = null
    // V9.1 : zone de la pastille (coordonnées capture) utilisée pour la trame précédente
    private var previousMaskRect: Rect? = null
    // V9.1 : tampon de pixels réutilisé (évite une allocation par trame)
    private var pixelBuffer: IntArray? = null

    // V9.1 : dernière trame reçue, conservée pour analyser un écran devenu immobile
    // (lue et écrite uniquement par la boucle de capture)
    private var latestBitmap: Bitmap? = null

    // V9.1 : numéro de scène (incrémenté à chaque début de scroll) et état de scroll,
    // partagés entre la boucle de capture et la tâche d'analyse
    @Volatile
    private var sceneGeneration = 0L
    @Volatile
    private var isScrollingNow = false
    private val publishLock = Any()
    private var analysisJob: Job? = null

    // Temporisation de stabilisation naturelle ultra-rapide (160 ms) — inchangée
    private val settlingThresholdMs = 160L
    // Période d'analyse continue pendant la lecture d'une vidéo (700 ms) — inchangée
    private val videoPeriodicAnalysisIntervalMs = 700L

    // Seuils V8 (inchangés)
    private val scrollAnchorThreshold = 0.055f
    private val videoCenterThreshold = 0.028f

    // V9.1 : paramètres de la nouvelle logique (ajustables après essais terrain)
    private val loopPeriodMs = 90L                 // cycle de surveillance (inchangé)
    private val scrollExitQuietMs = 220L           // silence requis pour déclarer la fin du scroll
    private val videoHoldMs = 400L                 // durée sans mouvement central avant de quitter le mode vidéo
    private val anchorConfirmDuringVideo = 0.040f  // pendant une vidéo : décalage + ancres >= 0,040 requis
    private val shiftMinChange = 0.006f            // changement minimal pour chercher un décalage
    private val shiftMatchRatio = 0.35f            // décalage retenu si l'erreur tombe sous 35 % de l'erreur sans décalage
    private val shiftMaxFraction = 0.30f           // décalage maximal recherché (30 % du profil)
    private val rowProfileStep = 2                 // une ligne sur 2
    private val rowProfileColumnStep = 4           // une colonne sur 4 dans chaque ligne
    private val colProfileStep = 2                 // une colonne sur 2
    private val colProfileRowStep = 4              // une ligne sur 4 dans chaque colonne
    private val maskMarginPx = 6                   // marge autour de la pastille (pixels capture)

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
        screenWidthPx = screenWidth
        screenHeightPx = screenHeight

        val isLandscape = screenWidth >= screenHeight
        // Optimisation de résolution pour processeur 1.6 GHz : 540p / 640p
        // Permet une inférence en ~70-90 ms sans aucune perte de qualité de détection
        val targetWidth = if (isLandscape) {
            minOf(screenWidth, 960)
        } else {
            minOf(screenWidth, 640)
        }
        val targetHeight = ((screenHeight.toFloat() / screenWidth.toFloat()) * targetWidth).toInt().coerceAtLeast(480)

        Log.d(tag, "Initializing screen capture V9.1: ${targetWidth}x${targetHeight}, screen=${screenWidth}x${screenHeight}")

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
            var isCurrentlyVideo = false
            var lastScrollEvidenceTime = 0L
            var lastCenterMotionTime = 0L
            var lastAnalysisLaunchTime = 0L
            var sceneAnalyzedAfterSettling = false

            try {
                while (isActive && isCapturing) {
                    try {
                        val now = System.currentTimeMillis()
                        val reader = imageReader ?: break
                        val image: Image? = try {
                            reader.acquireLatestImage()
                        } catch (_: Exception) {
                            null
                        }

                        // ── 1. NOUVELLE TRAME : mesure du mouvement ─────────────────────────
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
                                val motion = analyzeSpatialMotion(bitmap)

                                // La trame la plus récente est conservée (V9.1)
                                latestBitmap?.recycle()
                                latestBitmap = bitmap

                                if (motion.isReliable) {
                                    val shiftDetected = motion.verticalShiftDetected || motion.horizontalShiftDetected

                                    // Indice de scroll : ancres qui bougent (règle V8) OU contenu qui glisse
                                    // (pendant une vidéo établie, le glissement doit être confirmé par les ancres)
                                    val isScrollEvidence = motion.anchorDiff >= scrollAnchorThreshold ||
                                            (shiftDetected && (!isCurrentlyVideo || motion.anchorDiff >= anchorConfirmDuringVideo))

                                    if (isScrollEvidence) {
                                        lastScrollEvidenceTime = now
                                        lastCenterMotionTime = 0L
                                        sceneAnalyzedAfterSettling = false
                                        if (!isScrollingNow) {
                                            enterScrollState()
                                        }
                                        if (isCurrentlyVideo) {
                                            isCurrentlyVideo = false
                                            onVideoStateChanged(false)
                                        }
                                    } else if (motion.centerDiff >= videoCenterThreshold && !shiftDetected) {
                                        // Ancres fixes + centre qui change sans glisser -> lecture vidéo (règle V8)
                                        lastCenterMotionTime = now
                                    }
                                }
                            }
                        }

                        // ── 2. ÉVALUATION TEMPORELLE (à chaque cycle, même sans nouvelle trame) ──
                        // Fin de scroll : aucun indice de défilement depuis scrollExitQuietMs
                        if (isScrollingNow && now - lastScrollEvidenceTime >= scrollExitQuietMs) {
                            isScrollingNow = false
                            onScrollStateChanged(false)
                        }

                        if (!isScrollingNow) {
                            // Vidéo en lecture tant que le centre a bougé récemment
                            val videoNow = lastCenterMotionTime > 0L && now - lastCenterMotionTime < videoHoldMs
                            if (videoNow != isCurrentlyVideo) {
                                isCurrentlyVideo = videoNow
                                onVideoStateChanged(videoNow)
                                if (!videoNow) {
                                    // Vidéo / carrousel -> image fixe : la scène fixe doit être ré-analysée
                                    sceneAnalyzedAfterSettling = false
                                }
                            }

                            val frame = latestBitmap
                            val analysisIdle = analysisJob?.isActive != true
                            if (frame != null && analysisIdle) {
                                val stableSinceScroll = now - lastScrollEvidenceTime
                                val shouldAnalyze = if (isCurrentlyVideo) {
                                    // VIDÉO EN LECTURE : analyse dès la stabilisation puis toutes les 700 ms
                                    (!sceneAnalyzedAfterSettling && stableSinceScroll >= settlingThresholdMs) ||
                                            (now - lastAnalysisLaunchTime >= videoPeriodicAnalysisIntervalMs)
                                } else {
                                    // IMAGE FIXE OU TEXTE : une analyse par scène stabilisée
                                    !sceneAnalyzedAfterSettling && stableSinceScroll >= settlingThresholdMs
                                }

                                if (shouldAnalyze && launchAnalysis(frame)) {
                                    sceneAnalyzedAfterSettling = true
                                    lastAnalysisLaunchTime = now
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(tag, "Error during capture/analysis loop", e)
                    }

                    // Cycle de surveillance à 90 ms pour une réactivité immédiate sans saturer le CPU
                    delay(loopPeriodMs)
                }
            } finally {
                // Libération de la dernière trame conservée (exécuté sur le thread de la boucle)
                latestBitmap?.recycle()
                latestBitmap = null
            }
        }
    }

    /**
     * V9.1 : Début de scroll. Le numéro de scène change sous verrou : tout verdict encore
     * en cours de calcul devient périmé et ne sera jamais affiché.
     */
    private fun enterScrollState() {
        synchronized(publishLock) {
            sceneGeneration++
            isScrollingNow = true
            onScrollStateChanged(true)
        }
        analysisJob?.cancel()
    }

    /**
     * V9.1 : Lance l'analyse d'une COPIE de la trame (la boucle continue pendant ce temps).
     * La zone de la pastille est masquée dans la copie. Le verdict n'est publié que si la
     * scène n'a pas changé entre-temps. Renvoie false si la copie n'a pas pu être créée.
     */
    private fun launchAnalysis(source: Bitmap): Boolean {
        val snapshot: Bitmap = try {
            source.copy(Bitmap.Config.ARGB_8888, true)
        } catch (t: Throwable) {
            Log.e(tag, "Unable to copy frame for analysis", t)
            null
        } ?: return false

        try {
            maskOverlayArea(snapshot)
        } catch (e: Exception) {
            Log.e(tag, "Unable to mask overlay area", e)
        }

        val generation = sceneGeneration
        analysisJob = scope.launch {
            try {
                val state = analyzer.analyzeFrame(snapshot)
                synchronized(publishLock) {
                    if (generation == sceneGeneration && !isScrollingNow && isCapturing) {
                        onStateUpdated(state)
                    } else {
                        Log.d(tag, "Verdict périmé ignoré (scène $generation, actuelle $sceneGeneration)")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Error during frame analysis", e)
            } finally {
                snapshot.recycle()
            }
        }
        return true
    }

    /**
     * V9.1 : Remplace la zone de la pastille par la couleur moyenne de son pourtour,
     * pour que les détecteurs (OCR compris) n'analysent jamais la pastille elle-même.
     */
    private fun maskOverlayArea(bitmap: Bitmap) {
        val rect = computeMaskRect(bitmap.width, bitmap.height) ?: return

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        fun sample(x: Int, y: Int) {
            if (x < 0 || y < 0 || x >= bitmap.width || y >= bitmap.height) return
            val c = bitmap.getPixel(x, y)
            sumR += (c shr 16) and 0xFF
            sumG += (c shr 8) and 0xFF
            sumB += c and 0xFF
            count++
        }
        val steps = 12
        for (i in 0..steps) {
            val x = rect.left + (rect.width() * i) / steps
            val y = rect.top + (rect.height() * i) / steps
            sample(x, rect.top - 2)
            sample(x, rect.bottom + 1)
            sample(rect.left - 2, y)
            sample(rect.right + 1, y)
        }

        val fillColor = if (count > 0) {
            Color.rgb((sumR / count).toInt(), (sumG / count).toInt(), (sumB / count).toInt())
        } else {
            Color.BLACK
        }
        val paint = Paint().apply {
            color = fillColor
            style = Paint.Style.FILL
        }
        Canvas(bitmap).drawRect(rect, paint)
    }

    /**
     * V9.1 : Zone de la pastille convertie en coordonnées de la trame capturée (+ marge).
     */
    private fun computeMaskRect(bitmapWidth: Int, bitmapHeight: Int): Rect? {
        val screenRect = try {
            overlayBoundsProvider()
        } catch (_: Exception) {
            null
        } ?: return null
        if (screenWidthPx <= 0 || screenHeightPx <= 0 || screenRect.isEmpty) return null

        val sx = bitmapWidth.toFloat() / screenWidthPx
        val sy = bitmapHeight.toFloat() / screenHeightPx
        val rect = Rect(
            ((screenRect.left * sx).toInt() - maskMarginPx).coerceAtLeast(0),
            ((screenRect.top * sy).toInt() - maskMarginPx).coerceAtLeast(0),
            ((screenRect.right * sx).toInt() + maskMarginPx).coerceAtMost(bitmapWidth),
            ((screenRect.bottom * sy).toInt() + maskMarginPx).coerceAtMost(bitmapHeight)
        )
        return if (rect.width() > 0 && rect.height() > 0) rect else null
    }

    /**
     * Analyse spatiale discriminante :
     * Sépare les zones d'ancrage d'interface (Right Rail + Bottom + Top)
     * de la zone centrale où joue le média (Vidéo / Image).
     * V9.1 : ajoute la mesure du décalage (contenu qui glisse) et exclut la pastille.
     */
    private data class SpatialMotion(
        val anchorDiff: Float,
        val centerDiff: Float,
        val verticalShiftDetected: Boolean = false,
        val horizontalShiftDetected: Boolean = false,
        // false si la trame ne peut pas être comparée à la précédente
        // (première trame, ou pastille déplacée / apparue / bulle ouverte entre les deux)
        val isReliable: Boolean = true
    )

    private fun analyzeSpatialMotion(bitmap: Bitmap): SpatialMotion {
        val w = bitmap.width
        val h = bitmap.height

        // Lecture unique de tous les pixels (bien plus rapide que des getPixel répétés)
        val needed = w * h
        val pixels = pixelBuffer?.takeIf { it.size == needed } ?: IntArray(needed).also { pixelBuffer = it }
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val mask = computeMaskRect(w, h)
        val maskChanged = mask != previousMaskRect
        previousMaskRect = mask

        fun isMasked(x: Int, y: Int): Boolean =
            mask != null && x >= mask.left && x < mask.right && y >= mask.top && y < mask.bottom

        // 1. Échantillonnage des ancres d'interface (positions V8 inchangées) :
        // Zone A : Colonne latérale droite (Like, Commentaires, Partage dans TikTok / Reels / Shorts)
        // [X: 90%, Y: 35%..82%]
        // Zone B : Bandeau de métadonnées en bas (@pseudo, légende, musique)
        // [X: 10%..75%, Y: 89%]
        // Zone C : En-tête (Pour toi, recherche ou barre d'état)
        // [X: 20%..80%, Y: 6%]
        val anchorCount = 24 + 16 + 12
        val currentAnchorArray = IntArray(anchorCount)
        val anchorExcluded = BooleanArray(anchorCount)
        var aIdx = 0

        // 24 points sur la colonne droite
        for (i in 0 until 24) {
            val px = (w * 0.90f).toInt().coerceIn(0, w - 1)
            val py = (h * (0.35f + (i / 24f) * 0.47f)).toInt().coerceIn(0, h - 1)
            anchorExcluded[aIdx] = isMasked(px, py)
            currentAnchorArray[aIdx++] = pixels[py * w + px]
        }

        // 16 points sur le bandeau bas
        for (i in 0 until 16) {
            val px = (w * (0.10f + (i / 16f) * 0.65f)).toInt().coerceIn(0, w - 1)
            val py = (h * 0.89f).toInt().coerceIn(0, h - 1)
            anchorExcluded[aIdx] = isMasked(px, py)
            currentAnchorArray[aIdx++] = pixels[py * w + px]
        }

        // 12 points sur l'en-tête
        for (i in 0 until 12) {
            val px = (w * (0.20f + (i / 12f) * 0.60f)).toInt().coerceIn(0, w - 1)
            val py = (h * 0.06f).toInt().coerceIn(0, h - 1)
            anchorExcluded[aIdx] = isMasked(px, py)
            currentAnchorArray[aIdx++] = pixels[py * w + px]
        }

        val prevAnchorArray = previousAnchorSamples
        previousAnchorSamples = currentAnchorArray

        val anchorDiff = if (prevAnchorArray != null && prevAnchorArray.size == currentAnchorArray.size) {
            computeArrayLumaDiff(currentAnchorArray, prevAnchorArray, anchorExcluded)
        } else {
            0f
        }

        // 2. Échantillonnage de la zone centrale (où joue la vidéo) :
        // [X: 25%..75%, Y: 25%..75%] (Grille 6x6 = 36 points)
        val centerPoints = IntArray(36)
        val centerExcluded = BooleanArray(36)
        var cIdx = 0
        for (y in 0 until 6) {
            val py = (h * (0.25f + (y / 6f) * 0.50f)).toInt().coerceIn(0, h - 1)
            for (x in 0 until 6) {
                val px = (w * (0.25f + (x / 6f) * 0.50f)).toInt().coerceIn(0, w - 1)
                centerExcluded[cIdx] = isMasked(px, py)
                centerPoints[cIdx++] = pixels[py * w + px]
            }
        }

        val prevCenterArray = previousCenterSamples
        previousCenterSamples = centerPoints

        val centerDiff = if (prevCenterArray != null && prevCenterArray.size == centerPoints.size) {
            computeArrayLumaDiff(centerPoints, prevCenterArray, centerExcluded)
        } else {
            0f
        }

        // 3. V9.1 : profils de luminance et recherche de décalage
        val rowProfile = buildRowProfile(pixels, w, h, mask)
        val colProfile = buildColumnProfile(pixels, w, h, mask)
        val prevRow = previousRowProfile
        val prevCol = previousColProfile
        previousRowProfile = rowProfile
        previousColProfile = colProfile

        val isReliable = !maskChanged && prevAnchorArray != null && prevCenterArray != null
        if (!isReliable) {
            return SpatialMotion(anchorDiff = 0f, centerDiff = 0f, isReliable = false)
        }

        val verticalShift = isShiftDetected(prevRow, rowProfile)
        val horizontalShift = isShiftDetected(prevCol, colProfile)

        return SpatialMotion(
            anchorDiff = anchorDiff,
            centerDiff = centerDiff,
            verticalShiftDetected = verticalShift,
            horizontalShiftDetected = horizontalShift,
            isReliable = true
        )
    }

    /**
     * V9.1 : Luminance moyenne de chaque ligne (barre d'état et barre de navigation exclues :
     * 5 % en haut et en bas). La zone de la pastille est ignorée.
     */
    private fun buildRowProfile(pixels: IntArray, w: Int, h: Int, mask: Rect?): FloatArray {
        val yStart = (h * 0.05f).toInt()
        val yEnd = (h * 0.95f).toInt()
        val count = max(0, (yEnd - yStart) / rowProfileStep)
        val profile = FloatArray(count)
        for (i in 0 until count) {
            val y = yStart + i * rowProfileStep
            val rowOffset = y * w
            val rowInMask = mask != null && y >= mask.top && y < mask.bottom
            var sum = 0L
            var n = 0
            var x = 0
            while (x < w) {
                if (!(rowInMask && x >= mask!!.left && x < mask.right)) {
                    sum += luma(pixels[rowOffset + x])
                    n++
                }
                x += rowProfileColumnStep
            }
            profile[i] = if (n > 0) sum.toFloat() / n else 0f
        }
        return profile
    }

    /**
     * V9.1 : Luminance moyenne de chaque colonne sur la bande centrale (20 %..80 % de la
     * hauteur) — détecte les glissements horizontaux (carrousels Instagram).
     */
    private fun buildColumnProfile(pixels: IntArray, w: Int, h: Int, mask: Rect?): FloatArray {
        val yStart = (h * 0.20f).toInt()
        val yEnd = (h * 0.80f).toInt()
        val count = w / colProfileStep
        val profile = FloatArray(count)
        for (i in 0 until count) {
            val x = i * colProfileStep
            val colInMask = mask != null && x >= mask.left && x < mask.right
            var sum = 0L
            var n = 0
            var y = yStart
            while (y < yEnd) {
                if (!(colInMask && y >= mask!!.top && y < mask.bottom)) {
                    sum += luma(pixels[y * w + x])
                    n++
                }
                y += colProfileRowStep
            }
            profile[i] = if (n > 0) sum.toFloat() / n else 0f
        }
        return profile
    }

    /**
     * V9.1 : Vrai si la trame courante s'explique par un simple GLISSEMENT de la précédente.
     * - Changement sans décalage trop faible -> rien à signaler (écran immobile).
     * - Un décalage non nul réduit fortement l'écart -> le contenu a glissé (scroll).
     * - Aucun décalage n'explique le changement -> contenu qui change sur place (vidéo).
     */
    private fun isShiftDetected(prev: FloatArray?, cur: FloatArray): Boolean {
        if (prev == null || prev.size != cur.size || cur.size < 16) return false
        val err0 = profileError(prev, cur, 0)
        if (err0 < shiftMinChange) return false

        val maxShift = max(1, (cur.size * shiftMaxFraction).toInt())
        var best = Float.MAX_VALUE
        for (s in -maxShift..maxShift) {
            if (s == 0) continue
            val e = profileError(prev, cur, s)
            if (e < best) best = e
        }
        return best <= err0 * shiftMatchRatio
    }

    /**
     * Écart moyen normalisé (0..1) entre cur[i] et prev[i + shift] sur la zone de recouvrement.
     */
    private fun profileError(prev: FloatArray, cur: FloatArray, shift: Int): Float {
        val n = cur.size
        val start = max(0, -shift)
        val end = min(n, n - shift)
        if (end - start < n / 2) return Float.MAX_VALUE
        var sum = 0f
        for (i in start until end) {
            sum += abs(cur[i] - prev[i + shift])
        }
        return sum / ((end - start) * 255f)
    }

    private fun luma(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    /**
     * Écart de couleur moyen (formule V8 inchangée). V9.1 : les points situés sous la
     * pastille sont ignorés.
     */
    private fun computeArrayLumaDiff(a: IntArray, b: IntArray, excluded: BooleanArray? = null): Float {
        var totalDiff = 0L
        var used = 0
        val size = a.size
        for (i in 0 until size) {
            if (excluded != null && excluded[i]) continue
            val c1 = a[i]
            val c2 = b[i]
            val rDiff = abs(((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF))
            val gDiff = abs(((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF))
            val bDiff = abs((c1 and 0xFF) - (c2 and 0xFF))
            totalDiff += (rDiff + gDiff + bDiff)
            used++
        }
        if (used == 0) return 0f
        return totalDiff.toFloat() / (used * 3f * 255f)
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
        // V9.1 : toute analyse en cours est annulée et ne publiera rien (isCapturing = false)
        analysisJob?.cancel()
        analysisJob = null
        previousAnchorSamples = null
        previousCenterSamples = null
        previousRowProfile = null
        previousColProfile = null
        previousMaskRect = null

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
