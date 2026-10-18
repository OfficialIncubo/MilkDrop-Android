package com.milkdrop.visualizer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.milkdrop.visualizer.R
import com.milkdrop.visualizer.audio.AudioCaptureManager
import com.milkdrop.visualizer.audio.AudioInput
import com.milkdrop.visualizer.cast.CastingController
import com.milkdrop.visualizer.databinding.ActivityMainBinding
import com.milkdrop.visualizer.presets.PresetListCache
import com.milkdrop.visualizer.presets.PresetPaths
import com.milkdrop.visualizer.presets.PresetRatings
import com.milkdrop.visualizer.presets.PresetRatingsStore
import com.milkdrop.visualizer.render.MilkDropRenderer.MeshSize
import com.milkdrop.visualizer.render.MilkDropRenderer.Navigation
import com.milkdrop.visualizer.render.MilkDropSurfaceView
import com.milkdrop.visualizer.render.GraphicsSupport
import com.milkdrop.visualizer.settings.AppSettings
import com.milkdrop.visualizer.settings.AudioTuning
import com.milkdrop.visualizer.settings.PresetTiming
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var surfaceView: MilkDropSurfaceView
    private lateinit var audioCaptureManager: AudioCaptureManager
    private lateinit var presetAdapter: PresetListAdapter
    private lateinit var settings: AppSettings
    private var castingController: CastingController? = null

    private var autoAdvanceEnabled = true
    private var shuffleEnabled = true
    private var hardCutEnabled = false
    private var presetDurationIndex = 1
    private var additionalRandomIndex = 0
    private var transitionTimeIndex = 3
    private var beatCutsEnabled = false
    private lateinit var ratings: PresetRatings
    private lateinit var ratingsStore: PresetRatingsStore
    private var hadStorageAccess = false
    private var presetScanGeneration = 0

    /** Every scanned preset, hidden ones included; the playlist is this minus the hidden ones. */
    private var allPresetPaths: List<String> = emptyList()
    private var presetEntries: List<PresetEntry> = emptyList()
    private var currentPresetPath: String? = null
    private var favouritesOnly = false
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val ratingsWriter = Executors.newSingleThreadExecutor()
    private var pendingSearch = Runnable {}
    private var searchGeneration = 0
    private var fpsIndex = 0
    private var qualityIndex = 0
    private var resumeAutomaticAudio = false
    private var graphicsBlocked = false
    private var remoteNavigation = false
    private var restoreControlsAfterPlaylist = false
    private val consumedRemoteKeys = mutableSetOf<Int>()
    private var activeTouchPointer = -1
    private val pointerWindowOrigin = IntArray(2)

    /** Set while code (not the user) changes a setting's selection, so its listener ignores it. */
    private var updatingControls = false

    /** Loading/error message for the top label; while set, it shows instead of the preset name. */
    @StringRes
    private var statusMessage: Int? = R.string.presets_loading
    private var visualizationFailed = false
    private var currentPresetName: String? = null
    private val hidePresetName = Runnable { updateTopLabel() }
    private var presetNameShownUntil = 0L

    /** Back closes the open panel (preset list or settings) instead of exiting the app. */
    private val closePanelOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                binding.playlistPanel.isVisible -> closePlaylistPanel()
                binding.settingsSheet.isVisible -> closeSettingsSheet(showControls = true)
                else -> {
                    hideRemoteControls()
                }
            }
        }
    }

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startPersistedAudioSource()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setImmersiveFullscreen()

        val graphics = GraphicsSupport.check(this)
        if (!graphics.supported) {
            graphicsBlocked = true
            setContentView(R.layout.activity_unsupported_graphics)
            findViewById<TextView>(R.id.graphicsUnsupportedMessage).text =
                getString(R.string.graphics_unsupported_message, graphics.version)
            findViewById<View>(R.id.btnExitUnsupported).apply {
                setOnClickListener { finish() }
                requestFocus()
            }
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = AppSettings(this)
        // Two small files, read once here; writes go to the background executor.
        hadStorageAccess = hasAllFilesAccess()
        ratingsStore = PresetRatingsStore(
            PresetPaths.presetsDir, PresetPaths.favouritesFile, PresetPaths.hiddenFile,
            canAccessFiles = ::hasAllFilesAccess,
            onFileError = { file, error -> Log.w(TAG, "Unable to access ${file.name}", error) },
            writeExecutor = ratingsWriter,
        )
        ratings = PresetRatings(ratingsStore.loadFavourites(), ratingsStore.loadHidden(), ratingsStore::save)
        autoAdvanceEnabled = settings.autoAdvanceEnabled
        shuffleEnabled = settings.shuffleEnabled
        hardCutEnabled = settings.hardCutEnabled
        presetDurationIndex = PresetTiming.durations.indexOf(settings.presetDurationSeconds)
        additionalRandomIndex = PresetTiming.additionalRandom.indexOf(settings.additionalRandomSeconds)
        transitionTimeIndex = PresetTiming.transitions.indexOfFirst { it == settings.transitionSeconds }
        beatCutsEnabled = settings.beatCutsEnabled
        fpsIndex = settings.fpsIndex
        qualityIndex = settings.qualityIndex
        restoreControlStates()

        surfaceView = MilkDropSurfaceView(this, PresetPaths.texturesDir)
        surfaceView.milkDropRenderer.onInitializationFailed = {
            runOnUiThread {
                visualizationFailed = true
                setStatusMessage(R.string.visualization_initialization_failed)
            }
        }
        binding.surfaceContainer.addView(surfaceView)
        applyRestoredRenderSettings()

        audioCaptureManager = AudioCaptureManager(this) { samples, frameCount, channels, sampleRateHz, snapshot ->
            surfaceView.milkDropRenderer.feedPcm(samples, frameCount, channels, sampleRateHz, snapshot)
        }
        audioCaptureManager.onAutomaticSourceChanged = {
            runOnUiThread {
                if (!isDestroyed && settings.audioInput == AudioInput.AUTOMATIC) {
                    val description = when (audioCaptureManager.automaticInput) {
                        AudioInput.INTERNAL -> R.string.audio_source_automatic_internal
                        AudioInput.MIC -> R.string.audio_source_automatic_mic
                        else -> R.string.audio_source_automatic_unavailable
                    }
                    binding.audioSourceDescription.setText(description)
                }
            }
        }

        setupGestures()
        castingController = CastingController(this, surfaceView, onState = { state ->
            val showCast = state.available || state.device != null
            if (binding.btnCast.isVisible != showCast) {
                binding.btnCast.isVisible = showCast
                ViewCompat.requestApplyInsets(binding.root)
            }
            binding.btnCast.setText(when {
                state.connecting -> R.string.cast_connecting
                state.device != null -> R.string.cast_stop
                else -> R.string.cast_start
            })
            binding.btnCast.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (state.device != null) R.drawable.ic_cast_off else R.drawable.ic_cast, 0, 0, 0)
            binding.castStatus.isVisible = state.casting
            binding.castDeviceName.text = state.device
        }, onError = { Toast.makeText(this, R.string.cast_failed, Toast.LENGTH_LONG).show() })
        binding.btnCast.setOnClickListener { castingController?.chooseOrStop() }
        setupControlBar()
        setupSettingsSheet()
        setupPlaylistPanel()
        onBackPressedDispatcher.addCallback(this, closePanelOnBack)
        applyOrientation(resources.configuration.orientation)
        applySystemBarInsets()
        if (isTelevision()) {
            remoteNavigation = true
            showRemoteControls()
        }
        requestPermissionsThenStartAudio()
        loadPresetsInBackground()
    }

    /** The layout shows defaults; set every control to match what was restored. */
    private fun restoreControlStates() {
        updateShuffleButton()
        updatingControls = true
        binding.switchAutoAdvance.isChecked = autoAdvanceEnabled
        binding.switchBeatCuts.isChecked = beatCutsEnabled
        binding.switchPresetNameDuringTransitions.isChecked = settings.showPresetNameDuringTransitions
        binding.switchScreenDependentRendering.isChecked = settings.screenDependentRendering
        configureTimingSpinner(binding.spinnerPresetDuration, R.array.preset_duration_options, presetDurationIndex)
        configureTimingSpinner(binding.spinnerAdditionalRandom, R.array.additional_random_options, additionalRandomIndex)
        configureTimingSpinner(binding.spinnerTransitionTime, R.array.transition_time_options, transitionTimeIndex)
        binding.groupTransition.check(if (hardCutEnabled) R.id.transitionInstant else R.id.transitionSmooth)
        binding.groupFrameRate.check(FPS_BUTTON_IDS[fpsIndex])
        binding.groupQuality.check(QUALITY_BUTTON_IDS[qualityIndex])
        binding.groupAudioSource.check(AUDIO_INPUT_BUTTON_IDS.getValue(settings.audioInput))
        binding.sliderAudioSensitivity.max = (AudioTuning.SENSITIVITY_MAX * 10).roundToInt()
        binding.sliderAudioSensitivity.progress = (settings.audioSensitivity * 10).roundToInt()
        binding.sliderFftShaderScaling.max = (AudioTuning.FFT_SCALING_MAX * 100).roundToInt()
        binding.sliderFftShaderScaling.progress = (settings.fftShaderScaling * 100).roundToInt()
        binding.sliderFftShaderNoiseFloor.max = (AudioTuning.FFT_NOISE_FLOOR_MAX * 100).roundToInt()
        binding.sliderFftShaderNoiseFloor.progress = (settings.fftShaderNoiseFloor * 100).roundToInt()
        updateAudioTuningLabels()
        updatingControls = false
        binding.audioSourceDescription.setText(AUDIO_INPUT_DESCRIPTIONS.getValue(settings.audioInput))
    }

    /**
     * Restored control states are cosmetic only — actually apply the settings. The renderer picks
     * up this state itself once its GL surface exists (see MilkDropRenderer's class doc).
     */
    private fun applyRestoredRenderSettings() {
        surfaceView.milkDropRenderer.shuffleEnabled = shuffleEnabled
        surfaceView.milkDropRenderer.autoAdvanceEnabled = autoAdvanceEnabled
        surfaceView.milkDropRenderer.instantTransitions = hardCutEnabled
        surfaceView.milkDropRenderer.presetDurationSeconds = settings.presetDurationSeconds.toDouble()
        surfaceView.milkDropRenderer.additionalRandomSeconds = settings.additionalRandomSeconds.toDouble()
        surfaceView.milkDropRenderer.transitionSeconds = settings.transitionSeconds.toDouble()
        surfaceView.milkDropRenderer.beatCutsEnabled = beatCutsEnabled
        surfaceView.milkDropRenderer.screenDependentRendering = settings.screenDependentRendering
        surfaceView.milkDropRenderer.audioSensitivity = settings.audioSensitivity
        surfaceView.milkDropRenderer.fftShaderScaling = settings.fftShaderScaling
        surfaceView.milkDropRenderer.fftShaderNoiseFloor = settings.fftShaderNoiseFloor
        surfaceView.targetFps = FPS_OPTIONS[fpsIndex]
        applyQuality()
    }

    private fun applyQuality() {
        surfaceView.setResolutionScale(QUALITY_SCALES[qualityIndex])
        surfaceView.milkDropRenderer.meshSize = QUALITY_MESH_SIZES[qualityIndex]
    }

    /**
     * Scanning the presets directory takes seconds, so a launch starts from the list cached by the
     * previous run (and resumes the last preset) almost immediately, then rescans in the background
     * and swaps in the new list only if the folder changed. Without a cache, the first discovered
     * preset starts while the rest of the tree is scanned. Folder setup happens on this worker too.
     */
    private fun loadPresetsInBackground() {
        val generation = ++presetScanGeneration
        val renderer = surfaceView.milkDropRenderer
        renderer.startPresetPath = settings.lastPresetPath
        renderer.onPresetChanged = { path ->
            settings.lastPresetPath = path
            runOnUiThread {
                currentPresetPath = path
                updateFavouriteButton()
                showPresetName(File(path).nameWithoutExtension)
            }
        }
        renderer.onPresetsReady = { runOnUiThread { setStatusMessage(null) } }
        if (!hasAllFilesAccess()) {
            setStatusMessage(R.string.presets_need_access)
            return
        }
        setStatusMessage(R.string.presets_loading)

        val cache = PresetListCache(File(filesDir, PRESET_CACHE_FILE))
        fun publish(action: () -> Unit) {
            runOnUiThread {
                if (generation == presetScanGeneration && !isDestroyed) action()
            }
        }
        Thread({
            try {
                PresetPaths.ensureDirsExist()
                publish { renderer.refreshTextureSearchPaths() }
                val cached = cache.read()
                if (cached.isNotEmpty()) {
                    publish { setAllPresets(cached) }
                }

                val startMs = SystemClock.elapsedRealtime()
                val scanned = PresetPaths.scanPresets { first ->
                    if (cached.isEmpty()) publish { setAllPresets(listOf(first)) }
                }
                Log.d(TAG, "Scanned ${scanned.size} presets in ${SystemClock.elapsedRealtime() - startMs} ms")
                // Only publish/cache a completed scan while storage access is still available.
                if (!hasAllFilesAccess()) {
                    publish { setStatusMessage(R.string.presets_need_access) }
                    return@Thread
                }
                if (scanned != cached) {
                    cache.write(scanned)
                    publish { setAllPresets(scanned) }
                }
                if (scanned.isEmpty()) {
                    publish { setStatusMessage(R.string.presets_none_found) }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Unable to prepare preset folders", e)
                publish { setStatusMessage(R.string.presets_storage_unavailable) }
            } catch (e: SecurityException) {
                Log.w(TAG, "Unable to access preset folders", e)
                publish { setStatusMessage(R.string.presets_need_access) }
            }
        }, "milkdrop-preset-scan").start()
    }

    private fun setAllPresets(paths: List<String>) {
        allPresetPaths = paths
        applyPlaylist()
    }

    /**
     * Hands projectM the playlist without hidden presets (so Next, Shuffle and Auto-advance skip
     * them), and rebuilds the List entries with each preset's index in that playlist.
     */
    private fun applyPlaylist() {
        val playable = ratings.playable(allPresetPaths)
        surfaceView.milkDropRenderer.loadScannedPresets(playable)
        val indexByPath = HashMap<String, Int>(playable.size * 2)
        playable.forEachIndexed { index, path -> indexByPath[path] = index }
        presetEntries = allPresetPaths.map { PresetEntry(it, indexByPath[it]) }
        if (binding.playlistPanel.isVisible) scheduleSearch()
    }

    private fun hasAllFilesAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        else -> true
    }

    private fun setStatusMessage(@StringRes message: Int?) {
        statusMessage = if (visualizationFailed) R.string.visualization_initialization_failed else message
        updateTopLabel()
    }

    /** Keep the sidebar name current; optionally flash it when a preset changes. */
    private fun showPresetName(name: String) {
        currentPresetName = name
        binding.presetStatus.removeCallbacks(hidePresetName)
        presetNameShownUntil = if (settings.showPresetNameDuringTransitions) {
            SystemClock.uptimeMillis() + PRESET_NAME_FLASH_MS
        } else 0L
        if (settings.showPresetNameDuringTransitions) {
            binding.presetStatus.postDelayed(hidePresetName, PRESET_NAME_FLASH_MS)
        }
        updateTopLabel()
    }

    private fun updateTopLabel() {
        val label = binding.presetStatus
        val message = statusMessage
        if (message != null) {
            label.setText(message)
            label.isVisible = true
            return
        }
        // Not with the settings sheet open: in landscape the sheet reaches the top, under the name.
        val name = currentPresetName
        val shown = binding.controlBar.isVisible ||
            (settings.showPresetNameDuringTransitions && !binding.settingsSheet.isVisible &&
                SystemClock.uptimeMillis() < presetNameShownUntil)
        label.text = name
        label.isVisible = name != null && shown
    }

    /**
     * Keeps the controls clear of the system bars even though they're hidden: in immersive mode
     * the bars reappear transiently over the app, and the bottom row of buttons sat right under
     * the navigation bar's Home button. In landscape the navigation bar and cutout sit at a side.
     */
    private fun applySystemBarInsets() {
        val barPadding = Padding.of(binding.controlBar)
        val sheetPadding = Padding.of(binding.settingsSheet)
        val panelPadding = Padding.of(binding.playlistPanel)
        val statusTopMargin = (binding.presetStatus.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        val castMargin = (binding.btnCast.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.controlBar.updatePadding(
                left = barPadding.left + bars.left, right = barPadding.right + bars.right,
                bottom = barPadding.bottom + bars.bottom,
            )
            binding.settingsSheet.updatePadding(
                left = sheetPadding.left + bars.left, right = sheetPadding.right + bars.right,
                bottom = sheetPadding.bottom + bars.bottom,
            )
            binding.playlistPanel.updatePadding(
                left = panelPadding.left + bars.left, top = panelPadding.top + bars.top,
                right = panelPadding.right + bars.right, bottom = panelPadding.bottom + bars.bottom,
            )
            binding.presetStatus.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                val castGap = if (binding.btnCast.isVisible) 56 else 0
                topMargin = statusTopMargin + bars.top +
                    ((STATUS_TOP_GAP_DP + castGap) * resources.displayMetrics.density).toInt()
            }
            binding.btnCast.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = castMargin + bars.top
                marginEnd = castMargin + if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) bars.left else bars.right
            }
            insets
        }
    }

    private data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        companion object {
            fun of(view: View) = Padding(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        }
    }

    /** Rotation doesn't recreate the activity (see the manifest), so the control layout switches here. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (graphicsBlocked) return
        applyOrientation(newConfig.orientation)
    }

    /**
     * Portrait: preset controls, a divider, then the media keys below. Landscape: a single row —
     * preset controls (Settings last), a vertical divider, then the media keys.
     */
    private fun applyOrientation(orientation: Int) {
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val density = resources.displayMetrics.density
        binding.controlBar.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        binding.presetControls.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (landscape) 0 else ViewGroup.LayoutParams.MATCH_PARENT
            weight = if (landscape) 1f else 0f
        }
        binding.controlDivider.updateLayoutParams<LinearLayout.LayoutParams> {
            val gap = (DIVIDER_GAP_DP * density).toInt()
            if (landscape) {
                width = density.toInt().coerceAtLeast(1)
                height = (LANDSCAPE_DIVIDER_HEIGHT_DP * density).toInt()
                setMargins(gap, 0, gap, 0)
            } else {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = density.toInt().coerceAtLeast(1)
                setMargins(0, gap, 0, gap)
            }
        }
        binding.settingsSheet.updateLayoutParams<FrameLayout.LayoutParams> {
            width = if (landscape) (LANDSCAPE_SHEET_WIDTH_DP * density).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onResume() {
        super.onResume()
        if (graphicsBlocked) return
        surfaceView.onResume()
        castingController?.start()
        if (resumeAutomaticAudio) {
            resumeAutomaticAudio = false
            if (settings.audioInput == AudioInput.AUTOMATIC) startPersistedAudioSource()
        }
        val storageAccess = hasAllFilesAccess()
        if (storageAccess && !hadStorageAccess) {
            ratings = PresetRatings(ratingsStore.loadFavourites(), ratingsStore.loadHidden(), ratingsStore::save)
            updateFavouriteButton()
            loadPresetsInBackground()
        }
        hadStorageAccess = storageAccess
    }

    /** Observe at the Activity boundary so buttons, sliders and the preset list still feed touches. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::surfaceView.isInitialized) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activeTouchPointer = event.getPointerId(event.actionIndex)
                    sendPointer(event, event.actionIndex, true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(activeTouchPointer)
                    if (index >= 0) sendPointer(event, index, true)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == activeTouchPointer) {
                        val next = (0 until event.pointerCount).firstOrNull { it != event.actionIndex }
                        if (next != null) {
                            activeTouchPointer = event.getPointerId(next)
                            sendPointer(event, next, true)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    sendPointer(event, event.actionIndex, false, released = true)
                    activeTouchPointer = -1
                }
                MotionEvent.ACTION_CANCEL -> {
                    activeTouchPointer = -1
                    surfaceView.milkDropRenderer.clearPointer()
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (::surfaceView.isInitialized && event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE,
                MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                    val down = event.buttonState and (MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0
                    val released = event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE &&
                        event.actionButton and (MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0
                    sendPointer(event, 0, down, released)
                }
                MotionEvent.ACTION_HOVER_EXIT -> surfaceView.milkDropRenderer.clearPointer()
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun sendPointer(event: MotionEvent, index: Int, down: Boolean, released: Boolean = false) {
        val viewport = binding.surfaceContainer
        if (viewport.width <= 0 || viewport.height <= 0) return
        viewport.getLocationInWindow(pointerWindowOrigin)
        val x = (event.getX(index) - pointerWindowOrigin[0]) / viewport.width
        val y = (event.getY(index) - pointerWindowOrigin[1]) / viewport.height
        val inside = x in 0.0f..1.0f && y in 0.0f..1.0f
        surfaceView.milkDropRenderer.updatePointer(
            if (inside) x else -1.0f, if (inside) y else -1.0f, down, released,
        )
    }

    override fun onPause() {
        super.onPause()
        if (graphicsBlocked) return
        castingController?.pause()
        consumedRemoteKeys.clear()
        if (settings.audioInput == AudioInput.AUTOMATIC) {
            resumeAutomaticAudio = true
            audioCaptureManager.stopAll()
        }
        activeTouchPointer = -1
        surfaceView.milkDropRenderer.clearPointer()
        surfaceView.onPause()
    }

    override fun onDestroy() {
        castingController?.close()
        castingController = null
        super.onDestroy()
        searchGeneration++
        if (::audioCaptureManager.isInitialized) {
            audioCaptureManager.onAutomaticSourceChanged = null
            audioCaptureManager.stopAll()
        }
        if (::binding.isInitialized) {
            binding.playlistSearch.removeCallbacks(pendingSearch)
            binding.presetStatus.removeCallbacks(hidePresetName)
        }
        searchExecutor.shutdownNow()
        ratingsWriter.shutdown() // lets a pending save finish
    }

    private fun setImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Taps toggle controls; surface swipes provide preset/list/shuffle shortcuts.
     * Overlay views keep their own touch handling, and taps outside settings still close it.
     */
    private fun setupGestures() {
        surfaceView.isFocusable = false // Remote focus belongs to the overlay controls.
        surfaceView.isSoundEffectsEnabled = false
        surfaceView.setOnClickListener {
            if (binding.settingsSheet.isVisible) closeSettingsSheet(showControls = false) else toggleControlBar()
        }
        for (target in listOf<View>(surfaceView, binding.castStatus)) {
            target.isFocusable = false
            target.isSoundEffectsEnabled = false
            if (target !== surfaceView) target.setOnClickListener { surfaceView.performClick() }
            target.setOnTouchListener(VisualizationTouchListener(this,
                canSwipe = { !binding.settingsSheet.isVisible && !binding.playlistPanel.isVisible },
                onSwipe = { swipe ->
                    when (swipe) {
                        VisualizationTouchListener.Swipe.LEFT -> surfaceView.milkDropRenderer.requestNavigation(Navigation.Next)
                        VisualizationTouchListener.Swipe.RIGHT -> surfaceView.milkDropRenderer.requestNavigation(Navigation.Previous)
                        VisualizationTouchListener.Swipe.UP -> {
                            remoteNavigation = false
                            openPlaylistPanel(restoreControls = false)
                        }
                        VisualizationTouchListener.Swipe.DOWN -> toggleShuffleWithFeedback()
                    }
                },
            ))
        }
    }

    private fun toggleShuffleWithFeedback() {
        toggleShuffle()
        Toast.makeText(this, if (shuffleEnabled) R.string.shuffle_enabled else
            R.string.shuffle_disabled, Toast.LENGTH_SHORT).show()
    }

    private fun toggleShuffle() {
        shuffleEnabled = !shuffleEnabled
        settings.shuffleEnabled = shuffleEnabled
        updateShuffleButton()
        surfaceView.milkDropRenderer.shuffleEnabled = shuffleEnabled
    }

    private fun setupControlBar() {
        binding.btnNext.setOnClickListener {
            surfaceView.milkDropRenderer.requestNavigation(Navigation.Next)
        }
        binding.btnPrevious.setOnClickListener {
            surfaceView.milkDropRenderer.requestNavigation(Navigation.Previous)
        }
        binding.btnShuffle.setOnClickListener { toggleShuffle() }
        binding.btnPlaylist.setOnClickListener { openPlaylistPanel() }
        binding.btnSettings.setOnClickListener { openSettingsSheet() }
        binding.btnFavourite.setOnClickListener {
            val path = currentPresetPath ?: return@setOnClickListener
            ratings.toggleFavourite(path)
            updateFavouriteButton()
        }
        binding.btnMediaPrevious.setOnClickListener { dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        binding.btnMediaPlayPause.setOnClickListener { dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        binding.btnMediaNext.setOnClickListener { dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
    }

    private fun updateFavouriteButton() {
        val favourite = currentPresetPath?.let { ratings.isFavourite(it) } == true
        binding.btnFavourite.isActivated = favourite
        ViewCompat.setStateDescription(
            binding.btnFavourite,
            getString(if (favourite) R.string.state_on else R.string.state_off),
        )
    }

    private fun updateShuffleButton() {
        binding.btnShuffle.isActivated = shuffleEnabled
        ViewCompat.setStateDescription(
            binding.btnShuffle,
            getString(if (shuffleEnabled) R.string.state_on else R.string.state_off),
        )
    }

    private fun setupSettingsSheet() {
        listOf(binding.switchAutoAdvance, binding.switchBeatCuts, binding.switchPresetNameDuringTransitions,
            binding.switchScreenDependentRendering,
            binding.sliderAudioSensitivity, binding.sliderFftShaderScaling, binding.sliderFftShaderNoiseFloor).forEach {
            it.setBackgroundResource(R.drawable.remote_focus_background)
        }
        binding.btnCloseSettings.setOnClickListener { closeSettingsSheet(showControls = true) }
        binding.switchPresetNameDuringTransitions.setOnCheckedChangeListener { _, checked ->
            if (updatingControls) return@setOnCheckedChangeListener
            settings.showPresetNameDuringTransitions = checked
            if (!checked) {
                presetNameShownUntil = 0L
                binding.presetStatus.removeCallbacks(hidePresetName)
            }
            updateTopLabel()
        }
        binding.switchScreenDependentRendering.setOnCheckedChangeListener { _, checked ->
            if (updatingControls) return@setOnCheckedChangeListener
            settings.screenDependentRendering = checked
            surfaceView.milkDropRenderer.screenDependentRendering = checked
        }
        binding.switchAutoAdvance.setOnCheckedChangeListener { _, checked ->
            if (updatingControls) return@setOnCheckedChangeListener
            autoAdvanceEnabled = checked
            settings.autoAdvanceEnabled = checked
            surfaceView.milkDropRenderer.autoAdvanceEnabled = checked
        }
        binding.switchBeatCuts.setOnCheckedChangeListener { _, checked ->
            if (updatingControls) return@setOnCheckedChangeListener
            beatCutsEnabled = checked
            settings.beatCutsEnabled = checked
            surfaceView.milkDropRenderer.beatCutsEnabled = checked
        }
        binding.spinnerPresetDuration.onUserSelection { index ->
            if (index != presetDurationIndex) {
                presetDurationIndex = index
                settings.presetDurationSeconds = PresetTiming.durations[index]
                surfaceView.milkDropRenderer.presetDurationSeconds = settings.presetDurationSeconds.toDouble()
            }
        }
        binding.spinnerAdditionalRandom.onUserSelection { index ->
            if (index != additionalRandomIndex) {
                additionalRandomIndex = index
                settings.additionalRandomSeconds = PresetTiming.additionalRandom[index]
                surfaceView.milkDropRenderer.additionalRandomSeconds = settings.additionalRandomSeconds.toDouble()
            }
        }
        binding.spinnerTransitionTime.onUserSelection { index ->
            if (index != transitionTimeIndex) {
                transitionTimeIndex = index
                settings.transitionSeconds = PresetTiming.transitions[index]
                surfaceView.milkDropRenderer.transitionSeconds = settings.transitionSeconds.toDouble()
            }
        }
        binding.groupTransition.onUserSelection { id ->
            hardCutEnabled = id == R.id.transitionInstant
            settings.hardCutEnabled = hardCutEnabled
            surfaceView.milkDropRenderer.instantTransitions = hardCutEnabled
        }
        binding.groupFrameRate.onUserSelection { id ->
            fpsIndex = FPS_BUTTON_IDS.indexOf(id)
            settings.fpsIndex = fpsIndex
            surfaceView.targetFps = FPS_OPTIONS[fpsIndex]
        }
        binding.groupQuality.onUserSelection { id ->
            qualityIndex = QUALITY_BUTTON_IDS.indexOf(id)
            settings.qualityIndex = qualityIndex
            applyQuality()
        }
        binding.groupAudioSource.onUserSelection { id ->
            audioCaptureManager.stopAll()
            startAudioInput(AUDIO_INPUT_BUTTON_IDS.entries.first { it.value == id }.key)
        }
        binding.sliderAudioSensitivity.onUserProgress { progress ->
            settings.audioSensitivity = progress / 10.0f
            surfaceView.milkDropRenderer.audioSensitivity = settings.audioSensitivity
            updateAudioTuningLabels()
        }
        binding.sliderFftShaderScaling.onUserProgress { progress ->
            settings.fftShaderScaling = progress / 100.0f
            surfaceView.milkDropRenderer.fftShaderScaling = settings.fftShaderScaling
            updateAudioTuningLabels()
        }
        binding.sliderFftShaderNoiseFloor.onUserProgress { progress ->
            settings.fftShaderNoiseFloor = progress / 100.0f
            surfaceView.milkDropRenderer.fftShaderNoiseFloor = settings.fftShaderNoiseFloor
            updateAudioTuningLabels()
        }
    }

    private fun updateAudioTuningLabels() {
        binding.audioSensitivityLabel.text = getString(R.string.audio_sensitivity_value, settings.audioSensitivity)
        binding.fftShaderScalingLabel.text = getString(R.string.fft_shader_scaling_value, settings.fftShaderScaling)
        binding.fftShaderNoiseFloorLabel.text = getString(R.string.fft_shader_noise_floor_value, settings.fftShaderNoiseFloor)
    }

    private fun SeekBar.onUserProgress(action: (Int) -> Unit) {
        keyProgressIncrement = 1
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !updatingControls) action(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun configureTimingSpinner(spinner: Spinner, labels: Int, index: Int) {
        spinner.adapter = ArrayAdapter.createFromResource(this, labels, android.R.layout.simple_spinner_item).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(index)
    }

    private fun Spinner.onUserSelection(action: (Int) -> Unit) {
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!updatingControls && position >= 0) action(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** Runs [action] when the user picks a different option, not when code changes the selection. */
    private fun RadioGroup.onUserSelection(action: (checkedId: Int) -> Unit) {
        setOnCheckedChangeListener { _, checkedId ->
            if (!updatingControls && checkedId != View.NO_ID) action(checkedId)
        }
    }

    private fun openSettingsSheet() {
        binding.controlBar.isVisible = false
        binding.settingsSheet.isVisible = true
        binding.settingsScroll.scrollTo(0, 0)
        closePanelOnBack.isEnabled = true
        if (remoteNavigation) binding.switchAutoAdvance.requestFocus()
        updateTopLabel()
    }

    private fun closeSettingsSheet(showControls: Boolean) {
        binding.settingsSheet.isVisible = false
        binding.controlBar.isVisible = showControls
        closePanelOnBack.isEnabled = showControls || binding.playlistPanel.isVisible
        if (remoteNavigation && showControls) binding.btnSettings.requestFocus()
        updateTopLabel()
    }

    private fun setupPlaylistPanel() {
        binding.playlistRecyclerView.layoutManager = LinearLayoutManager(this)
        presetAdapter = PresetListAdapter(
            ratings,
            onPresetClick = { entry ->
                if (entry.playlistIndex != null) {
                    surfaceView.milkDropRenderer.requestNavigation(Navigation.JumpTo(entry.path))
                }
                closePlaylistPanel()
            },
            onFavouriteToggled = { entry ->
                ratings.toggleFavourite(entry.path)
                if (entry.path == currentPresetPath) updateFavouriteButton()
                if (favouritesOnly) scheduleSearch()
            },
            onHiddenToggled = { entry ->
                ratings.toggleHidden(entry.path)
                applyPlaylist()
            },
        )
        binding.playlistRecyclerView.adapter = presetAdapter

        binding.btnClosePlaylist.setOnClickListener { closePlaylistPanel() }
        binding.btnFavouritesOnly.setOnClickListener {
            favouritesOnly = !favouritesOnly
            updateFavouritesOnlyButton()
            scheduleSearch()
        }
        updateFavouritesOnlyButton()
        binding.playlistSearch.doAfterTextChanged { scheduleSearch() }
        binding.playlistSearch.setOnKeyListener { _, key, event ->
            val keyboardVisible = ViewCompat.getRootWindowInsets(binding.playlistSearch)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (remoteNavigation && !keyboardVisible && event.action == KeyEvent.ACTION_DOWN &&
                (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                // EditText otherwise consumes these arrows for its cursor, trapping header focus.
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) binding.btnFavouritesOnly.requestFocus()
                true
            } else false
        }
    }

    private fun updateFavouritesOnlyButton() {
        binding.btnFavouritesOnly.isActivated = favouritesOnly
        ViewCompat.setStateDescription(
            binding.btnFavouritesOnly,
            getString(if (favouritesOnly) R.string.state_on else R.string.state_off),
        )
    }

    private fun openPlaylistPanel(restoreControls: Boolean = binding.controlBar.isVisible) {
        restoreControlsAfterPlaylist = restoreControls
        binding.controlBar.isVisible = false
        binding.playlistSearch.setText("")
        binding.playlistPanel.isVisible = true
        closePanelOnBack.isEnabled = true
        updateTopLabel()

        val entries = visibleEntries()
        showSearchResults(entries, "")
        val current = entries.indexOfFirst { it.path == currentPresetPath }
        if (current >= 0) binding.playlistRecyclerView.scrollToPosition(current)
        if (remoteNavigation) {
            binding.btnClosePlaylist.requestFocus()
            binding.playlistRecyclerView.post {
                if (binding.playlistPanel.isVisible) {
                    binding.playlistRecyclerView.layoutManager?.findViewByPosition(current.coerceAtLeast(0))?.requestFocus()
                }
            }
        }
    }

    /** The List before the search query: all presets, or only favourites. */
    private fun visibleEntries(): List<PresetEntry> =
        if (favouritesOnly) presetEntries.filter { ratings.isFavourite(it.path) } else presetEntries

    private fun closePlaylistPanel() {
        searchGeneration++
        binding.playlistSearch.removeCallbacks(pendingSearch)
        // The search box's keyboard otherwise stays up after picking a preset, covering (and
        // swallowing taps meant for) the controls.
        binding.playlistSearch.clearFocus()
        WindowCompat.getInsetsController(window, binding.playlistSearch).hide(WindowInsetsCompat.Type.ime())
        binding.playlistPanel.isVisible = false
        binding.controlBar.isVisible = restoreControlsAfterPlaylist
        closePanelOnBack.isEnabled = restoreControlsAfterPlaylist || binding.settingsSheet.isVisible
        if (remoteNavigation && restoreControlsAfterPlaylist) binding.btnPlaylist.requestFocus()
        updateTopLabel()
    }

    /**
     * Waits for a pause in typing, then filters ~15k paths off the main thread, so each keystroke
     * doesn't re-filter and rebind the whole list. Results from an older query are dropped.
     */
    private fun scheduleSearch() {
        binding.playlistSearch.removeCallbacks(pendingSearch)
        val generation = ++searchGeneration
        pendingSearch = Runnable {
            val query = binding.playlistSearch.text?.toString().orEmpty()
            val entries = visibleEntries()
            searchExecutor.execute {
                val filtered = PresetSearch.filter(entries, query)
                runOnUiThread {
                    if (!isDestroyed && binding.playlistPanel.isVisible && generation == searchGeneration) {
                        showSearchResults(filtered, query)
                    }
                }
            }
        }
        binding.playlistSearch.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS)
    }

    private fun showSearchResults(entries: List<PresetEntry>, query: String) {
        presetAdapter.submit(entries, currentPresetPath)
        binding.playlistResultsStatus.apply {
            text = when {
                entries.isNotEmpty() -> resources.getQuantityString(R.plurals.playlist_result_count, entries.size, entries.size)
                query.isNotBlank() && favouritesOnly -> getString(R.string.playlist_no_favourite_results)
                query.isNotBlank() -> getString(R.string.playlist_no_results)
                favouritesOnly -> getString(R.string.playlist_no_favourites)
                else -> getString(R.string.playlist_empty)
            }
            isVisible = true
        }
    }

    private fun toggleControlBar() {
        binding.controlBar.isVisible = !binding.controlBar.isVisible
        closePanelOnBack.isEnabled = binding.controlBar.isVisible
        if (remoteNavigation && binding.controlBar.isVisible) binding.btnNext.requestFocus()
        updateTopLabel()
    }

    private fun isTelevision(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private fun showRemoteControls() {
        binding.controlBar.isVisible = true
        closePanelOnBack.isEnabled = true
        binding.btnNext.requestFocus()
        updateTopLabel()
    }

    private fun hideRemoteControls() {
        // A held OK may have pressed a button before it became a hide gesture.
        currentFocus?.apply {
            isPressed = false
            cancelPendingInputEvents()
            clearFocus()
        }
        binding.controlBar.isVisible = false
        closePanelOnBack.isEnabled = false
        updateTopLabel()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (graphicsBlocked || !::binding.isInitialized) return super.dispatchKeyEvent(event)
        val key = event.keyCode
        // Keep repeats and key-up away from whichever panel a shortcut just opened.
        if (key in consumedRemoteKeys) {
            if (event.action == KeyEvent.ACTION_UP) consumedRemoteKeys.remove(key)
            return true
        }
        val confirmKey = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER ||
            key == KeyEvent.KEYCODE_NUMPAD_ENTER
        val navigationKey = key in intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        if (navigationKey && event.action == KeyEvent.ACTION_DOWN) {
            remoteNavigation = true
            if (!binding.settingsSheet.isVisible && !binding.playlistPanel.isVisible) {
                if (!binding.controlBar.isVisible) {
                    consumedRemoteKeys.add(key)
                    if (event.repeatCount == 0) {
                        when (key) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> binding.btnPrevious.performClick()
                            KeyEvent.KEYCODE_DPAD_RIGHT -> binding.btnNext.performClick()
                            KeyEvent.KEYCODE_DPAD_DOWN -> toggleShuffleWithFeedback()
                            KeyEvent.KEYCODE_DPAD_UP -> openPlaylistPanel()
                            else -> showRemoteControls()
                        }
                    }
                    return true
                }
                if (confirmKey && event.isLongPress) {
                    consumedRemoteKeys.add(key)
                    hideRemoteControls()
                    return true
                }
                if (currentFocus == null) {
                    showRemoteControls()
                    consumedRemoteKeys.add(key)
                    return true
                }
            }
        }
        // Ordinary D-pad/OK/Back navigation is sufficient; Menu is an optional shortcut.
        if (key == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                remoteNavigation = true
                if (binding.playlistPanel.isVisible) closePlaylistPanel()
                if (binding.settingsSheet.isVisible) closeSettingsSheet(true) else openSettingsSheet()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Sends a media key to whichever app is playing, as headset buttons would. */
    private fun dispatchMediaKey(keyCode: Int) {
        val audioManager = getSystemService(AudioManager::class.java)
        val eventTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun requestPermissionsThenStartAudio() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            for (permission in arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    needed += permission
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }

        if (needed.isNotEmpty()) {
            permissionsLauncher.launch(needed.toTypedArray())
        } else {
            startPersistedAudioSource()
        }
    }

    private fun startPersistedAudioSource() = startAudioInput(settings.audioInput)

    private fun startAudioInput(input: AudioInput) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            audioCaptureManager.stopAll()
            showAudioInput(input)
            binding.audioSourceDescription.setText(R.string.audio_source_permission_required)
            return
        }
        when (input) {
            AudioInput.MIC -> switchToMic()
            AudioInput.AUTOMATIC -> {
                if (audioCaptureManager.startAutomatic()) {
                    showAudioInput(AudioInput.AUTOMATIC)
                } else switchToMic()
            }
            AudioInput.INTERNAL ->
                if (audioCaptureManager.startInternal()) {
                    showAudioInput(AudioInput.INTERNAL)
                } else if (switchToMic()) {
                    binding.audioSourceDescription.setText(R.string.audio_source_internal_unavailable)
                }
        }
    }

    /** Fallback when another source is unavailable; also updates the saved selection to Mic. */
    private fun switchToMic(): Boolean {
        val started = audioCaptureManager.startMic()
        showAudioInput(AudioInput.MIC)
        if (!started) binding.audioSourceDescription.setText(R.string.audio_source_mic_unavailable)
        return started
    }

    /** Saves [input] and shows it as selected, with its description. */
    private fun showAudioInput(input: AudioInput) {
        settings.audioInput = input
        updatingControls = true
        binding.groupAudioSource.check(AUDIO_INPUT_BUTTON_IDS.getValue(input))
        updatingControls = false
        binding.audioSourceDescription.setText(AUDIO_INPUT_DESCRIPTIONS.getValue(input))
    }

    private companion object {
        const val TAG = "MainActivity"
        const val PRESET_CACHE_FILE = "preset_list_cache.txt"
        const val SEARCH_DEBOUNCE_MS = 250L
        const val STATUS_TOP_GAP_DP = 12
        const val PRESET_NAME_FLASH_MS = 2000L
        const val DIVIDER_GAP_DP = 4
        const val LANDSCAPE_DIVIDER_HEIGHT_DP = 32
        const val LANDSCAPE_SHEET_WIDTH_DP = 480

        // 0 = uncapped ("Max"). Index 0 (30fps) is the default performance-friendly setting.
        val FPS_OPTIONS = intArrayOf(30, 45, 60, 0)
        val FPS_BUTTON_IDS = intArrayOf(R.id.fps30, R.id.fps45, R.id.fps60, R.id.fpsMax)

        // Fraction of native resolution to render at; lower cuts fragment shader cost for heavy presets.
        val QUALITY_SCALES = floatArrayOf(1.0f, 0.75f, 0.5f)
        // Coarser warp mesh at Low: fewer per-vertex equation evaluations on the CPU each frame.
        val QUALITY_MESH_SIZES = arrayOf(MeshSize.DEFAULT, MeshSize.DEFAULT, MeshSize(24, 18))
        val QUALITY_BUTTON_IDS = intArrayOf(R.id.qualityHigh, R.id.qualityMedium, R.id.qualityLow)

        val AUDIO_INPUT_BUTTON_IDS = mapOf(
            AudioInput.MIC to R.id.audioSourceMic,
            AudioInput.INTERNAL to R.id.audioSourceInternal,
            AudioInput.AUTOMATIC to R.id.audioSourceAutomatic,
        )
        val AUDIO_INPUT_DESCRIPTIONS = mapOf(
            AudioInput.MIC to R.string.audio_source_mic_description,
            AudioInput.INTERNAL to R.string.audio_source_internal_description,
            AudioInput.AUTOMATIC to R.string.audio_source_automatic_description,
        )
    }
}
