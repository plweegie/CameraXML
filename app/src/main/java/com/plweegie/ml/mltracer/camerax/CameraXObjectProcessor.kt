package com.plweegie.ml.mltracer.camerax

import android.graphics.RectF
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.objects.FirebaseVisionObject
import com.google.firebase.ml.vision.objects.FirebaseVisionObjectDetector
import com.google.firebase.ml.vision.objects.FirebaseVisionObjectDetectorOptions
import com.plweegie.ml.mltracer.R
import com.plweegie.ml.mltracer.camera.CameraReticleAnimator
import com.plweegie.ml.mltracer.camera.FrameProcessorBase
import com.plweegie.ml.mltracer.camera.GraphicOverlay
import com.plweegie.ml.mltracer.camera.WorkflowModel
import com.plweegie.ml.mltracer.objectdetection.*
import com.plweegie.ml.mltracer.settings.PreferenceUtils
import java.io.IOException


class CameraXObjectProcessor(private val model: WorkflowModel,
                             private val overlay: GraphicOverlay) : FrameProcessorBase<List<FirebaseVisionObject>>() {

    private var detector: FirebaseVisionObjectDetector
    private val confirmationController by lazy {
        ObjectConfirmationController(overlay)
    }
    private val cameraReticleAnimator by lazy {
        CameraReticleAnimator(overlay)
    }
    private val reticleOuterRingRadius: Int = overlay.resources
            .getDimensionPixelOffset(R.dimen.object_reticle_outer_ring_stroke_radius)

    init {
        val detectorOptions = FirebaseVisionObjectDetectorOptions.Builder()
                .setDetectorMode(FirebaseVisionObjectDetectorOptions.STREAM_MODE)
                .build()
        detector = FirebaseVision.getInstance().getOnDeviceObjectDetector(detectorOptions)
    }

    override fun stop() {
        try {
            detector.close()
        } catch(e: IOException) {
            Log.e("DETECTOR", "Failed to close detector")
        }
    }

    override fun detectInImage(image: FirebaseVisionImage): Task<List<FirebaseVisionObject>> =
            detector.processImage(image)

    override fun onSuccess(image: FirebaseVisionImage,
                           results: List<FirebaseVisionObject>,
                           graphicOverlay: GraphicOverlay) {
        if (!model.isCameraLive) {
            return
        }

        if (results.isEmpty()) {
            confirmationController.reset()
            model.setWorkflowState(WorkflowModel.WorkflowState.DETECTING)
        } else {
            val objectIndex = 0
            val detection = results[objectIndex]
            if (objectBoxOverlapsConfirmationReticle(graphicOverlay, detection)) {
                // User is confirming the object selection.
                confirmationController.confirming(detection.trackingId)
                model.confirmingObject(
                        DetectedObject(detection, objectIndex, image), confirmationController.progress)
            } else {
                // Object detected but user doesn't want to pick this one.
                confirmationController.reset()
                model.setWorkflowState(WorkflowModel.WorkflowState.DETECTED)
            }
        }

        graphicOverlay.clear()
        if (results.isEmpty()) {
            graphicOverlay.add(ObjectReticleGraphic(graphicOverlay, cameraReticleAnimator))
            cameraReticleAnimator.start()
        } else {
            if (objectBoxOverlapsConfirmationReticle(graphicOverlay, results[0])) {
                // User is confirming the object selection.
                cameraReticleAnimator.cancel()
                graphicOverlay.add(
                        ObjectGraphicInProminentMode(
                                graphicOverlay, results[0], confirmationController))
                if (!confirmationController.isConfirmed && PreferenceUtils.isAutoSearchEnabled(graphicOverlay.context)) {
                    // Shows a loading indicator to visualize the confirming progress if in auto search mode.
                    graphicOverlay.add(ObjectConfirmationGraphic(graphicOverlay, confirmationController))
                }
            } else {
                // Object is detected but the confirmation reticle is moved off the object box, which
                // indicates user is not trying to pick this object.
                graphicOverlay.add(
                        ObjectGraphicInProminentMode(
                                graphicOverlay, results[0], confirmationController))
                graphicOverlay.add(ObjectReticleGraphic(graphicOverlay, cameraReticleAnimator))
                cameraReticleAnimator.start()
            }
        }
        graphicOverlay.invalidate()
    }

    override fun onFailure(e: Exception?) {
        Log.e("DETECTOR", "Object detection failed!", e)
    }

    private fun objectBoxOverlapsConfirmationReticle(
            graphicOverlay: GraphicOverlay, detection: FirebaseVisionObject): Boolean {
        val boxRect = graphicOverlay.translateRect(detection.boundingBox)
        val reticleCenterX = graphicOverlay.width / 2f
        val reticleCenterY = graphicOverlay.height / 2f
        val reticleRect = RectF(
                reticleCenterX - reticleOuterRingRadius,
                reticleCenterY - reticleOuterRingRadius,
                reticleCenterX + reticleOuterRingRadius,
                reticleCenterY + reticleOuterRingRadius)
        return reticleRect.intersect(boxRect)
    }
}