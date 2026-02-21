
diff --git a/AndroidWebRTCStreamer/app/src/main/java/com/hiran/streamer/MainActivity.kt b/AndroidWebRTCStreamer/app/src/main/java/com/hiran/streamer/MainActivity.kt
index 39d6ae784cb6d8d4f1f27f47d4af0f38b2a259fa..a7e98ad56a81822cefc1abaa838e30205618ec25 100644
--- a/AndroidWebRTCStreamer/app/src/main/java/com/hiran/streamer/MainActivity.kt
+++ b/AndroidWebRTCStreamer/app/src/main/java/com/hiran/streamer/MainActivity.kt
@@ -1,229 +1,65 @@
-package com.hiran.streamer
-
-import android.app.Activity
-import android.content.Intent
-import android.media.projection.MediaProjection
-import android.media.projection.MediaProjectionManager
-import android.os.Bundle
-import android.util.Log
-import android.widget.Button
-import androidx.appcompat.app.AppCompatActivity
-import okhttp3.MediaType.Companion.toMediaType
-import okhttp3.OkHttpClient
-import okhttp3.Request
-import okhttp3.RequestBody.Companion.toRequestBody
-import org.webrtc.*
-
-class MainActivity : AppCompatActivity() {
-
-    companion object {
-        private const val TAG = "WHIP-ANDROID"
-        private const val SCREEN_CAPTURE_REQUEST = 1001
-    }
-
-    private lateinit var btnStart: Button
-    private lateinit var projectionManager: MediaProjectionManager
-
-    private lateinit var factory: PeerConnectionFactory
-    private lateinit var pc: PeerConnection
-    private lateinit var eglBase: EglBase
-
-    private val whipUrl = "http://10.20.30.41:8889/gameplay/whip"
-
-    override fun onCreate(savedInstanceState: Bundle?) {
-        super.onCreate(savedInstanceState)
-        setContentView(R.layout.activity_main)
-
-        btnStart = findViewById(R.id.btnStart)
-        projectionManager =
-            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
-
-        initWebRTC()
-
-        btnStart.setOnClickListener {
-            startActivityForResult(
-                projectionManager.createScreenCaptureIntent(),
-                SCREEN_CAPTURE_REQUEST
-            )
-        }
-    }
-
-    override fun onActivityResult(
-        requestCode: Int,
-        resultCode: Int,
-        data: Intent?
-    ) {
-        super.onActivityResult(requestCode, resultCode, data)
-
-        if (requestCode == SCREEN_CAPTURE_REQUEST &&
-            resultCode == Activity.RESULT_OK &&
-            data != null
-        ) {
-            startStreaming(data)
-        }
-    }
-
-    // ---------------------------------------------------
-
-    private fun initWebRTC() {
-
-        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)
-
-        eglBase = EglBase.create()
-
-        PeerConnectionFactory.initialize(
-            PeerConnectionFactory.InitializationOptions.builder(this)
-                .setEnableInternalTracer(true)
-                .createInitializationOptions()
-        )
-
-        factory = PeerConnectionFactory.builder()
-            .setVideoEncoderFactory(
-                DefaultVideoEncoderFactory(
-                    eglBase.eglBaseContext,
-                    true,
-                    false // ⬅️ H264 BASELINE
-                )
-            )
-            .setVideoDecoderFactory(
-                DefaultVideoDecoderFactory(eglBase.eglBaseContext)
-            )
-            .createPeerConnectionFactory()
-
-        val iceServers = listOf(
-            PeerConnection.IceServer
-                .builder("stun:stun.l.google.com:19302")
-                .createIceServer()
-        )
-
-        val config = PeerConnection.RTCConfiguration(iceServers).apply {
-            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
-            continualGatheringPolicy =
-                PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
-        }
-
-        pc = factory.createPeerConnection(
-            config,
-            object : PeerConnection.Observer {
-
-                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
-                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
-                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
-                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
-                override fun onIceCandidate(candidate: IceCandidate) {}
-                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
-                override fun onAddStream(stream: MediaStream) {}
-                override fun onRemoveStream(stream: MediaStream) {}
-                override fun onDataChannel(dc: DataChannel) {}
-                override fun onRenegotiationNeeded() {}
-                override fun onAddTrack(
-                    receiver: RtpReceiver,
-                    streams: Array<MediaStream>
-                ) {}
-            }
-        )!!
-    }
-
-    // ---------------------------------------------------
-
-    private fun startStreaming(permissionData: Intent) {
-
-        val videoSource = factory.createVideoSource(false)
-        val videoTrack = factory.createVideoTrack("screen", videoSource)
-
-        val capturer = ScreenCapturerAndroid(
-            permissionData,
-            object : MediaProjection.Callback() {}
-        )
-
-        val helper = SurfaceTextureHelper.create(
-            "ScreenCaptureThread",
-            eglBase.eglBaseContext
-        )
-
-        capturer.initialize(helper, this, videoSource.capturerObserver)
-        capturer.startCapture(720, 1280, 30)
-
-        pc.addTrack(videoTrack)
-
-        pc.addTransceiver(
-            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
-            RtpTransceiver.RtpTransceiverInit(
-                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
-            )
-        )
-
-        createOffer()
-    }
-
-    // ---------------------------------------------------
-
-    private fun createOffer() {
-
-        val constraints = MediaConstraints().apply {
-            mandatory.add(
-                MediaConstraints.KeyValuePair("IceRestart", "false")
-            )
-            optional.add(
-                MediaConstraints.KeyValuePair("TrickleIce", "false")
-            )
-        }
-
-        pc.createOffer(object : SdpObserver {
-
-            override fun onCreateSuccess(desc: SessionDescription) {
-                pc.setLocalDescription(this, desc)
-            }
-
-            override fun onSetSuccess() {
-                sendWhipOffer(pc.localDescription!!)
-            }
-
-            override fun onCreateFailure(error: String?) {
-                Log.e(TAG, "Offer error: $error")
-            }
-
-            override fun onSetFailure(error: String?) {
-                Log.e(TAG, "Set local SDP error: $error")
-            }
-
-        }, constraints)
-    }
-
-    private fun sendWhipOffer(offer: SessionDescription) {
-
-        val body = offer.description
-            .toRequestBody("application/sdp".toMediaType())
-
-        val request = Request.Builder()
-            .url(whipUrl)
-            .post(body)
-            .build()
-
-        Thread {
-            try {
-                OkHttpClient().newCall(request).execute().use { res ->
-                    val answer = res.body?.string() ?: return@use
-
-                    pc.setRemoteDescription(
-                        object : SdpObserver {
-                            override fun onSetSuccess() {
-                                Log.d(TAG, "STREAMING ACTIVE 🚀")
-                            }
-
-                            override fun onSetFailure(error: String?) {}
-                            override fun onCreateSuccess(p0: SessionDescription?) {}
-                            override fun onCreateFailure(p0: String?) {}
-                        },
-                        SessionDescription(
-                            SessionDescription.Type.ANSWER,
-                            answer
-                        )
-                    )
-                }
-            } catch (e: Exception) {
-                Log.e(TAG, "WHIP error", e)
-            }
-        }.start()
-    }
-}
+package com.hiran.streamer
+
+import android.Manifest
+import android.app.Activity
+import android.content.Intent
+import android.media.projection.MediaProjectionManager
+import android.os.Build
+import android.os.Bundle
+import android.widget.Button
+import androidx.activity.result.contract.ActivityResultContracts
+import androidx.appcompat.app.AppCompatActivity
+import androidx.core.content.ContextCompat
+
+class MainActivity : AppCompatActivity() {
+
+    private lateinit var btnStart: Button
+    private lateinit var projectionManager: MediaProjectionManager
+
+    private val notificationPermissionLauncher = registerForActivityResult(
+        ActivityResultContracts.RequestPermission()
+    ) {
+        launchScreenCaptureRequest()
+    }
+
+    private val projectionLauncher = registerForActivityResult(
+        ActivityResultContracts.StartActivityForResult()
+    ) { result ->
+        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
+            val serviceIntent = Intent(this, StreamingService::class.java).apply {
+                action = StreamingService.ACTION_START
+                putExtra(StreamingService.EXTRA_RESULT_CODE, result.resultCode)
+                putExtra(StreamingService.EXTRA_RESULT_DATA, result.data)
+            }
+            ContextCompat.startForegroundService(this, serviceIntent)
+            finish()
+        }
+    }
+
+    override fun onCreate(savedInstanceState: Bundle?) {
+        super.onCreate(savedInstanceState)
+        setContentView(R.layout.activity_main)
+
+        btnStart = findViewById(R.id.btnStart)
+        projectionManager =
+            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
+
+        btnStart.setOnClickListener {
+            requestNotificationAndStart()
+        }
+
+        btnStart.performClick()
+    }
+
+    private fun requestNotificationAndStart() {
+        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
+            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
+        } else {
+            launchScreenCaptureRequest()
+        }
+    }
+
+    private fun launchScreenCaptureRequest() {
+        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
+    }
+}


=======
package com.hiran.streamer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var projectionManager: MediaProjectionManager

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        launchScreenCaptureRequest()
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_START
                putExtra(StreamingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(StreamingService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnStart.setOnClickListener {
            requestNotificationAndStart()
        }

        btnStart.performClick()
    }

    private fun requestNotificationAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchScreenCaptureRequest()
        }
    }

    private fun launchScreenCaptureRequest() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
