package com.hiran.streamer

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SCREEN_CAPTURE_REQUEST = 1001
    }

    private lateinit var btnStart: Button
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var peerConnection: PeerConnection

    private val whipUrl = "http://10.20.30.1:8889/gameplay/whip"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        initWebRTC()

        btnStart.setOnClickListener {
            startScreenPermission()
        }
    }

    private fun startScreenPermission() {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, SCREEN_CAPTURE_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SCREEN_CAPTURE_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            startStreaming(data)
        }
    }

    private fun initWebRTC() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        peerConnection = peerConnectionFactory.createPeerConnection(
            iceServers,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {}
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(dc: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {}
            }
        )!!
    }

    private fun startStreaming(permissionData: Intent) {
    try {
        val eglBase = EglBase.create()

        // Crear video source
        val videoSource = peerConnectionFactory.createVideoSource(false)
        val videoTrack = peerConnectionFactory.createVideoTrack("SCREEN", videoSource)

        // Callback real para MediaProjection
        val mediaProjectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d("WebRTC", "Screen capture stopped")
            }
        }

        // Crear capturador de pantalla
        val capturer = ScreenCapturerAndroid(permissionData, mediaProjectionCallback)

        // Surface helper en hilo separado
        val surfaceHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext)

        // Inicializar capturador
        capturer.initialize(surfaceHelper, this, videoSource.capturerObserver)

        // Captura a 720x1600 @30fps
        capturer.startCapture(720, 1600, 30)

        // Agregar track al PeerConnection
        peerConnection.addTrack(videoTrack)

        // Crear oferta WHIP
        createOffer()

    } catch (e: Exception) {
        Log.e("WebRTC", "Error starting stream: ${e.message}")
    }
}


    private fun createOffer() {
        val constraints = MediaConstraints()

        peerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        sendWhipOffer(desc)
                    }

                    override fun onSetFailure(p0: String?) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, desc)
            }

            override fun onCreateFailure(error: String?) {
                Log.e("WHIP", "Offer failed: $error")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    private fun sendWhipOffer(offer: SessionDescription) {
        val client = OkHttpClient()
        val body = offer.description.toRequestBody("application/sdp".toMediaType())
        val request = Request.Builder()
            .url(whipUrl)
            .post(body)
            .build()

        client.newCall(request).execute().use {
            Log.d("WHIP", "Response: ${it.code}")
        }
    }
}






