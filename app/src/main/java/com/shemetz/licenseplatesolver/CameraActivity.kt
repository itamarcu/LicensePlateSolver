package com.shemetz.licenseplatesolver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.view.GestureDetectorCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.*
import android.widget.TextView
import com.google.android.gms.vision.CameraSource
import com.google.android.gms.vision.Detector
import com.google.android.gms.vision.text.TextBlock
import com.google.android.gms.vision.text.TextRecognizer


class CameraActivity : AppCompatActivity(), GestureDetector.OnDoubleTapListener, GestureDetector.OnGestureListener {


    companion object {
        const val TAG = "CameraActivity"
        /**
         * "^\D?\d{2}[,:;. \-\\|/]?\d{3}[,:;. \-\\|/]?\d{2}\d{3}[,:;. \-\\|/]?\d{2}[,:;. \-\\|/]?\d{3})\D?$"
         *
         * go to https://regex101.com/ to figure this out
         *
         * basically, the valid separator characters are:    ,:;. -\|/
         *
         * including line breaks, which is why motorcycles work ^_^
         *
         * and it's 2-3-2 (7 digits) or 3-2-3 (8 digits)
         *
         * And it allows little mistakes on the sides, and an optional "IL" on the left
         */
        val validIsraeliNumberRegex = Regex(
                """ ^
                    I?L?\D?
                    (
                    (\d{2}[,:;.\s\-\\|/]?\d{3}[,:;.\s\-\\|/]?\d{2})
                    |
                    (\d{3}[,:;.\s\-\\|/]?\d{2}[,:;.\s\-\\|/]?\d{3})
                    )
                    \D?
                    $
                """.replace("\\s".toRegex(), "")
        )
        val GOOD_EMOJIS = listOf("👍", "👌", "💯", "✔️", "😀", "😊", "🤓", "🙃", "☺", "😁", "😂", "😎", "🎉", "👯")
        val BAD_EMOJIS = listOf("👎", "💩", "🚫", "❌", "😢", "😬", "😕", "😔", "😨", "😱", "😧", "😭", "🤷", "🙅", "😞")
    }

    private lateinit var detectedTextView: TextView
    private lateinit var solutionTextView: TextView
    private lateinit var mDetector: GestureDetectorCompat
    private var cameraView: SurfaceView? = null
    private var cameraSource: CameraSource? = null
    private var cameraViewHolderCallback: SurfaceHolder.Callback? = null
    private var currentLicenseText: String = ""
    var cameraIsRunning = false
    var randomEmojiCounter = 0

    /**
     * Toggle digit concatenation mode when tapping
     */
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (mDetector.onTouchEvent(event)) {
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "Camera activity created!")
        setContentView(R.layout.activity_camera)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mDetector = GestureDetectorCompat(this, this)
        mDetector.setOnDoubleTapListener(this)
        cameraView = findViewById(R.id.surfaceView)
        detectedTextView = findViewById(R.id.detected_text_view)
        solutionTextView = findViewById(R.id.solution_text_view)

        createCameraSource()
    }

    private fun solveAndUpdate() {
        // this will not always work in time ¯\_(ツ)_/¯
        runOnUiThread {
            solutionTextView.setTextColor(resources.getColor(
                    if (ProblemSolver.allowDigitConcatenation) R.color.solutionWithConcatenation else R.color.solutionWithoutConcatenation
            ))
            // Show text spaced out
            solutionTextView.text = currentLicenseText.toList().joinToString(" ")
        }

        if (currentLicenseText.isBlank()) {
            startCameraSource()
        } else runOnUiThread {
            Log.d(TAG, "SOLVING $currentLicenseText...")
            stopCameraSource()
            val solution = ProblemSolver.solveNumberString(currentLicenseText)
            randomEmojiCounter++
            if (solution != null)
                solutionTextView.text = getString(R.string.solved_text,
                        solution,
                        GOOD_EMOJIS[randomEmojiCounter % GOOD_EMOJIS.size])
            else
                solutionTextView.text = getString(R.string.unsolved_text,
                        currentLicenseText.toList().joinToString(" "),
                        BAD_EMOJIS[randomEmojiCounter % BAD_EMOJIS.size])
            Log.d(TAG, "...Solved! solution is $solution")
        }
    }


    private fun createCameraSource() {
        Log.i(TAG, "Created Camera Source")

        //Create the TextRecognizer
        val textRecognizer = TextRecognizer.Builder(applicationContext).build()

        if (!textRecognizer.isOperational) {
            Log.w(CameraActivity.TAG, "Detector dependencies not loaded yet")
            return
        }

        //Initialize camera source to use high resolution and set Auto-focus on.
        cameraSource = CameraSource.Builder(applicationContext, textRecognizer)
                .setFacing(CameraSource.CAMERA_FACING_BACK)
                .setRequestedPreviewSize(1280, 1024)
                .setAutoFocusEnabled(true)
                .setRequestedFps(2.0f)
                .build()

        /**
         * Add call back to SurfaceView and check if camera permission is granted.
         * If permission is granted we can start our cameraSource and pass it to surfaceView
         */
        cameraViewHolderCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (ActivityCompat.checkSelfPermission(applicationContext,
                                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {

                    ActivityCompat.requestPermissions(this@CameraActivity,
                            arrayOf(Manifest.permission.CAMERA),
                            requestPermissionID)
                    return
                }
                cameraSource?.start(cameraView?.holder)

            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            /**
             * Release resources for cameraSource
             */
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                cameraSource?.stop()
            }
        }
        cameraView?.holder?.addCallback(cameraViewHolderCallback)

        //Set the TextRecognizer's Processor.
        textRecognizer.setProcessor(object : Detector.Processor<TextBlock> {
            override fun release() {}

            override fun receiveDetections(detections: Detector.Detections<TextBlock>) {
                val items = detections.detectedItems
                if (items.size() != 0) {
                    /**
                     * Update the text box only if one of the detected lines is an Israel license plate:
                     * 12-345-67 or 123-45-678
                     * The detector frequently outputs things like "12\345:67" so I have to use weird regex.
                     */
                    //Detected text: show every line that contains a digit
                    val stringBuilder = StringBuilder()
                    for (i in 0 until items.size()) {
                        val item = items.valueAt(i)
                        if (item.value.contains(Regex("\\d"))) {
                            Log.d(TAG, "Detecting:    ${item.value}")
                            stringBuilder.append(item.value)
                            stringBuilder.append("\n")
                            val text = item.value.trim()
                            val match = CameraActivity.validIsraeliNumberRegex.matchEntire(text)
                            if (match != null) {
                                val newLicenseText = text.filter { it.isDigit() } // of the format "1234567"
                                Log.d(TAG, "FOUND    ${item.value} → $newLicenseText")
                                if (currentLicenseText.isNotBlank())
                                    Log.i(TAG, "NOT SOLVING because $currentLicenseText exists")
                                else {
                                    currentLicenseText = newLicenseText
                                    solveAndUpdate()
                                }
                                break
                            }
                        }
                    }
                    detectedTextView.post {
                        detectedTextView.text = stringBuilder.toString()
                    }
                    //Equation text: solve every line that contains the exact format
                }
            }
        })

        cameraIsRunning = true
    }

    private fun startCameraSource() {
        Log.i(TAG, "Starting Camera Source")

        if (ActivityCompat.checkSelfPermission(applicationContext,
                        Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this@CameraActivity,
                    arrayOf(Manifest.permission.CAMERA),
                    requestPermissionID)
            return
        }
        cameraSource?.start(cameraView?.holder)
        cameraIsRunning = true
    }

    private fun stopCameraSource() {
        Log.i(TAG, "Stopping Camera Source")
        runOnUiThread {
            cameraSource?.stop()        // release the camera for other applications
            cameraIsRunning = false
        }
    }

    public override fun onPause() {
        super.onPause()

//        stopCameraSource()
    }

    public override fun onResume() {
        super.onResume()

//        startCameraSource()
    }

    override fun onShowPress(p0: MotionEvent?) {
    }

    override fun onSingleTapUp(p0: MotionEvent?): Boolean {
        return false
    }

    override fun onDown(p0: MotionEvent?): Boolean {
        return false
    }

    override fun onFling(p0: MotionEvent?, p1: MotionEvent?, p2: Float, p3: Float): Boolean {
        Log.d(TAG, "Flung screen! clearing current text.")
        currentLicenseText = ""
        solveAndUpdate()
        startCameraSource()
        return true
    }

    override fun onScroll(p0: MotionEvent?, p1: MotionEvent?, p2: Float, p3: Float): Boolean {
        return false
    }

    override fun onLongPress(p0: MotionEvent?) {
        Log.d(TAG, "Long-pressed screen! changing allowed solutions.")
        ProblemSolver.allowDigitConcatenation = !ProblemSolver.allowDigitConcatenation
        ProblemSolver.invalidateCache() // because cache now contains lies and untruths
        solveAndUpdate()
    }

    override fun onDoubleTapEvent(p0: MotionEvent?): Boolean {
        return false
    }

    override fun onSingleTapConfirmed(p0: MotionEvent?): Boolean {
        return false
    }

    override fun onDoubleTap(p0: MotionEvent?): Boolean {
        Log.d(TAG, "Double-tapped screen! Toggling camera!")
        if (cameraIsRunning) stopCameraSource() else startCameraSource()
        return true
    }

    private val requestPermissionID = 101

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode != requestPermissionID) {
            Log.d(TAG, "Got unexpected permission result: $requestCode")
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            return
        }

        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            cameraSource?.start(cameraView?.holder)

        }
    }
}
