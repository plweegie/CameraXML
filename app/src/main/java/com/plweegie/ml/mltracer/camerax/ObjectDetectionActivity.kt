package com.plweegie.ml.mltracer.camerax

import android.Manifest
import android.animation.AnimatorInflater
import android.animation.AnimatorSet
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import com.plweegie.ml.mltracer.R
import com.plweegie.ml.mltracer.Utils
import com.plweegie.ml.mltracer.camera.WorkflowModel
import com.plweegie.ml.mltracer.productsearch.ProductAdapter
import com.plweegie.ml.mltracer.productsearch.SearchEngine
import com.plweegie.ml.mltracer.settings.PreferenceUtils
import com.plweegie.ml.mltracer.settings.SettingsActivity
import kotlinx.android.synthetic.main.activity_live_object.*
import kotlinx.android.synthetic.main.camera_preview_overlay.*
import kotlinx.android.synthetic.main.product_bottom_sheet.*
import kotlinx.android.synthetic.main.top_action_bar_in_live_camera.*


class ObjectDetectionActivity : AppCompatActivity(), LifecycleOwner, View.OnClickListener {

    private lateinit var promptChipAnimator: AnimatorSet
    private lateinit var searchButtonAnimator: AnimatorSet
    private lateinit var viewModel: WorkflowModel
    private lateinit var currentWorkflowState: WorkflowModel.WorkflowState
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    private var processor: CameraXObjectProcessor? = null
    private var searchEngine: SearchEngine? = null
    private var objectThumbnail: Bitmap? = null

    private var upFromHiddenState = false

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 101
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private val bottomSheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {
        override fun onStateChanged(bottomSheet: View, newState: Int) {
            bottom_sheet_scrim_view.visibility =
                    if (newState == BottomSheetBehavior.STATE_HIDDEN) View.GONE else View.VISIBLE
            camera_preview_graphic_overlay.clear()

            when(newState) {
                BottomSheetBehavior.STATE_HIDDEN -> {
                    viewModel.setWorkflowState(WorkflowModel.WorkflowState.DETECTING)
                }
                BottomSheetBehavior.STATE_COLLAPSED,
                BottomSheetBehavior.STATE_EXPANDED,
                BottomSheetBehavior.STATE_HALF_EXPANDED -> {
                    upFromHiddenState = false
                }
                else -> {}
            }
        }

        override fun onSlide(bottomSheet: View, slideOffset: Float) {
            val searchedObject = viewModel.searchedObject.value
            if (searchedObject == null || java.lang.Float.isNaN(slideOffset)) {
                return
            }

            val collapsedStateHeight = Math.min(bottomSheetBehavior.peekHeight, bottomSheet.height)
            if (upFromHiddenState) {
                val thumbnailSrcRect = camera_preview_graphic_overlay.translateRect(searchedObject.boundingBox)
                bottom_sheet_scrim_view.updateWithThumbnailTranslateAndScale(
                        objectThumbnail,
                        collapsedStateHeight,
                        slideOffset,
                        thumbnailSrcRect)

            } else {
                bottom_sheet_scrim_view.updateWithThumbnailTranslate(
                        objectThumbnail, collapsedStateHeight, slideOffset, bottomSheet)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_object)

        if (allPermissionsGranted()) {
            camera_preview.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        camera_preview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
        }

        searchEngine = SearchEngine(applicationContext)

        camera_preview_graphic_overlay.setOnClickListener(this)

        promptChipAnimator =
                AnimatorInflater.loadAnimator(this, R.animator.bottom_prompt_chip_enter) as AnimatorSet
        promptChipAnimator.setTarget(bottom_prompt_chip)

        product_search_button.setOnClickListener(this)
        searchButtonAnimator =
                AnimatorInflater.loadAnimator(this, R.animator.search_button_enter) as AnimatorSet
        searchButtonAnimator.setTarget(product_search_button)

        setUpBottomSheet()

        close_button.setOnClickListener(this)
        flash_button.setOnClickListener(this)
        settings_button.setOnClickListener(this)

        setUpWorkflowModel()
    }

    override fun onResume() {
        super.onResume()

        viewModel.markCameraFrozen()
        settings_button.isEnabled = true
        currentWorkflowState = WorkflowModel.WorkflowState.NOT_STARTED

        processor = CameraXObjectProcessor(viewModel, camera_preview_graphic_overlay)
        viewModel.setWorkflowState(WorkflowModel.WorkflowState.DETECTING)
    }

    override fun onPause() {
        super.onPause()
        currentWorkflowState = WorkflowModel.WorkflowState.NOT_STARTED
        stopCameraPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        searchEngine?.shutdown()
    }

    override fun onBackPressed() {
        if (bottomSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        } else {
            super.onBackPressed()
        }
    }

    private fun startCamera() {

        val cameraPreviewSize = PreferenceUtils.getUserSpecifiedPreviewSize(this)?.preview!!
        // Create configuration object for the viewfinder use case
        val previewConfig = PreviewConfig.Builder().apply {
            setTargetAspectRatio(Rational(16, 9))
            setTargetResolution(Size(cameraPreviewSize.width, cameraPreviewSize.height))
        }.build()

        val imageAnalyzerConfig = ImageAnalysisConfig.Builder().apply {
            val analyzerThread = HandlerThread("MLKit").apply {
                start()
            }
            setCallbackHandler(Handler(analyzerThread.looper))
            setImageReaderMode(ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE)
            setTargetAspectRatio(Rational(16, 9))
            setTargetResolution(Size(cameraPreviewSize.width, cameraPreviewSize.height))
        }.build()

        // Build the viewfinder use case
        val preview = Preview(previewConfig)

        // Every time the viewfinder is updated, recompute layout
        preview.setOnPreviewOutputUpdateListener {
            camera_preview.surfaceTexture = it.surfaceTexture
            updateTransform()
        }

        val analyzerUseCase = ImageAnalysis(imageAnalyzerConfig).apply {
            setAnalyzer { image, rotationDegrees ->

                val firebaseRotation = when(rotationDegrees) {
                    Surface.ROTATION_270 -> FirebaseVisionImageMetadata.ROTATION_270
                    Surface.ROTATION_90 -> FirebaseVisionImageMetadata.ROTATION_90
                    Surface.ROTATION_180 -> FirebaseVisionImageMetadata.ROTATION_180
                    else -> FirebaseVisionImageMetadata.ROTATION_0
                }
                val imageMetadata = FirebaseVisionImageMetadata.Builder()
                        .setFormat(FirebaseVisionImageMetadata.IMAGE_FORMAT_NV21)
                        .setHeight(cameraPreviewSize.height)
                        .setWidth(cameraPreviewSize.width)
                        .setRotation(firebaseRotation)
                        .build()

                val buffer = image.planes[0].buffer
                val firebaseVisionImage = FirebaseVisionImage.fromByteBuffer(buffer, imageMetadata)
                processor?.detectInImage(firebaseVisionImage)?.addOnSuccessListener {
                    processor?.onSuccess(firebaseVisionImage, it, camera_preview_graphic_overlay)
                }
            }
        }

        // Bind use cases to lifecycle
        // If Android Studio complains about "this" being not a LifecycleOwner
        // try rebuilding the project or updating the appcompat dependency to
        // version 1.1.0 or higher.
        CameraX.bindToLifecycle(this, preview, analyzerUseCase)
    }

    private fun updateTransform() {
        val matrix = Matrix()

        // Compute the center of the view finder
        val centerX = camera_preview.width / 2f
        val centerY = camera_preview.height / 2f

        // Correct preview output to account for display rotation
        val rotationDegrees = when(camera_preview.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)

        // Finally, apply transformations to our TextureView
        camera_preview.setTransform(matrix)
    }

    /**
     * Process result from permission request dialog box, has the request
     * been granted? If yes, start Camera. Otherwise display a toast
     */
    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (Utils.allPermissionsGranted(this)) {
                camera_preview.post { startCamera() }
            } else {
                Toast.makeText(this,
                        "Permissions not granted by the user.",
                        Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun allPermissionsGranted(): Boolean {
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(
                            this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun setUpBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(bottom_sheet).apply {
            setBottomSheetCallback(bottomSheetCallback)
        }
        bottom_sheet_scrim_view.setOnClickListener(this)

        product_recycler_view.apply {
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(this@ObjectDetectionActivity)
            adapter = ProductAdapter(listOf())
        }
    }

    private fun setUpWorkflowModel() {
        viewModel = ViewModelProviders.of(this).get(WorkflowModel::class.java)

        // Observes the workflow state changes, if happens, update the overlay view indicators and
        // camera preview state.
        viewModel.workflowState.observe(this, Observer {
            workflowState -> workflowState?.let {
                if (it == currentWorkflowState) {
                    return@Observer
                }

                currentWorkflowState = workflowState
                Log.d("DETECTOR", "Current workflow state: " + currentWorkflowState.name)

                if (PreferenceUtils.isAutoSearchEnabled(this)) {
                    stateChangeInAutoSearchMode(workflowState)
                } else {
                    stateChangeInManualSearchMode(workflowState)
                }
            }
        })

        // Observes changes on the object to search, if happens, fire product search request.
        viewModel.objectToSearch.observe(this, Observer {
            searchEngine?.search(it, viewModel)
        })

        // Observes changes on the object that has search completed, if happens, show the bottom sheet
        // to present search result.
        viewModel.searchedObject.observe(this, Observer {
            searchedObject -> searchedObject?.let {
                val productList = it.productList
                objectThumbnail = it.objectThumbnail
                bottom_sheet_title.text = resources.getQuantityString(
                        R.plurals.bottom_sheet_title, productList.size, productList.size
                )
                product_recycler_view.adapter = ProductAdapter(productList)
                upFromHiddenState = true
                bottomSheetBehavior.apply {
                    peekHeight = 1280 / 2
                    state = BottomSheetBehavior.STATE_COLLAPSED
                }
            }
        })
    }

    private fun startCameraPreview() {
        if (!viewModel.isCameraLive) {
            viewModel.markCameraLive()
        }
    }

    private fun stopCameraPreview() {
        if (viewModel.isCameraLive) {
            viewModel.markCameraFrozen()
        }
    }

    override fun onClick(view: View?) {
        when(view?.id) {
            R.id.product_search_button -> {
                product_search_button.isEnabled = false
                viewModel.onSearchButtonClicked()
            }
            R.id.bottom_sheet_scrim_view -> {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            }
            R.id.close_button -> {
                onBackPressed()
            }
            R.id.flash_button -> {}
            R.id.settings_button -> {
                settings_button.isEnabled = false
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
    }

    private fun stateChangeInAutoSearchMode(workflowState: WorkflowModel.WorkflowState) {
        val wasPromptChipGone = bottom_prompt_chip.visibility == View.GONE

        product_search_button.visibility = View.GONE
        search_progress_bar.visibility = View.GONE
        when (workflowState) {
            WorkflowModel.WorkflowState.DETECTING,
            WorkflowModel.WorkflowState.DETECTED,
            WorkflowModel.WorkflowState.CONFIRMING -> {
                bottom_prompt_chip.apply {
                    visibility = View.VISIBLE
                    setText(if (workflowState == WorkflowModel.WorkflowState.CONFIRMING)
                            R.string.prompt_hold_camera_steady
                        else
                            R.string.prompt_point_at_an_object)
                }
                startCameraPreview()
            }
            WorkflowModel.WorkflowState.CONFIRMED -> {
                bottom_prompt_chip.apply {
                    visibility = View.VISIBLE
                    setText(R.string.prompt_searching)
                }
                stopCameraPreview()
            }
            WorkflowModel.WorkflowState.SEARCHING -> {
                search_progress_bar.visibility = View.VISIBLE
                bottom_prompt_chip.apply {
                    visibility = View.VISIBLE
                    setText(R.string.prompt_searching)
                }
                stopCameraPreview()
            }
            WorkflowModel.WorkflowState.SEARCHED -> {
                bottom_prompt_chip.visibility = View.GONE
                stopCameraPreview()
            }
            else -> bottom_prompt_chip.visibility = View.GONE
        }

        val shouldPlayPromptChipEnteringAnimation = wasPromptChipGone &&
                bottom_prompt_chip.visibility == View.VISIBLE
        if (shouldPlayPromptChipEnteringAnimation && !promptChipAnimator.isRunning) {
            promptChipAnimator.start()
        }
    }

    private fun stateChangeInManualSearchMode(workflowState: WorkflowModel.WorkflowState) {
        val wasPromptChipGone = bottom_prompt_chip.visibility == View.GONE
        val wasSearchButtonGone = product_search_button.visibility == View.GONE

        search_progress_bar.visibility = View.GONE

        when (workflowState) {
            WorkflowModel.WorkflowState.DETECTING,
            WorkflowModel.WorkflowState.DETECTED,
            WorkflowModel.WorkflowState.CONFIRMING -> {
                bottom_prompt_chip.apply {
                    visibility = View.VISIBLE
                    setText(R.string.prompt_point_at_an_object)
                }
                product_search_button.visibility = View.GONE
                startCameraPreview()
            }
            WorkflowModel.WorkflowState.CONFIRMED -> {
                bottom_prompt_chip.visibility = View.GONE
                product_search_button.apply {
                    visibility = View.VISIBLE
                    isEnabled = true
                    setBackgroundColor(Color.WHITE)
                }
                startCameraPreview()
            }
            WorkflowModel.WorkflowState.SEARCHING -> {
                bottom_prompt_chip.visibility = View.GONE
                product_search_button.apply {
                    visibility = View.VISIBLE
                    isEnabled = false
                    setBackgroundColor(Color.GRAY)
                }
                search_progress_bar.visibility = View.VISIBLE
                stopCameraPreview()
            }
            WorkflowModel.WorkflowState.SEARCHED -> {
                bottom_prompt_chip.visibility = View.GONE
                product_search_button.visibility = View.GONE
                stopCameraPreview()
            }
            else -> {
                bottom_prompt_chip.visibility = View.GONE
                product_search_button.visibility = View.GONE
            }
        }

        val shouldPlayPromptChipEnteringAnimation = wasPromptChipGone &&
                bottom_prompt_chip.visibility == View.VISIBLE
        if (shouldPlayPromptChipEnteringAnimation && !promptChipAnimator.isRunning) {
            promptChipAnimator.start()
        }

        val shouldPlaySearchButtonEnteringAnimation = wasSearchButtonGone &&
                product_search_button.visibility == View.VISIBLE
        if (shouldPlaySearchButtonEnteringAnimation && !searchButtonAnimator.isRunning()) {
            searchButtonAnimator.start()
        }
    }
}