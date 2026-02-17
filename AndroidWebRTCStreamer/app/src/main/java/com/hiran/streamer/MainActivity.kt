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
        private const val TAG = "WHIP-ANDROID"
        private const val SCREEN_CAPTURE_REQUEST = 1001
    }

    private lateinit var btnStart: Button
    private lateinit var projectionManager: MediaProjectionManager

    private lateinit var factory: PeerConnectionFactory
    private lateinit var pc: PeerConnection
    private lateinit var eglBase: EglBase

    private val whipUrl = "http://10.20.30.41:8889/gameplay/whip"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        initWebRTC()

        btnStart.setOnClickListener {
            Log.d(TAG, "Requesting screen permission")
            startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                SCREEN_CAPTURE_REQUEST
            )
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SCREEN_CAPTURE_REQUEST &&
            resultCode == Activity.RESULT_OK &&
            data != null
        ) {
            Log.d(TAG, "Screen permission granted")
            startStreaming(data)
        }
    }

    // -------------------- WEBRTC INIT --------------------

    private fun initWebRTC() {

        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)

        eglBase = EglBase.create()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    eglBase.eglBaseContext,
                    true,
                    true
                )
            )
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            )
            .createPeerConnectionFactory()

        val iceServers = listOf(
            PeerConnection.IceServer
                .builder("stun:stun.l.google.com:19302")
                .createIceServer()
        )

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        pc = factory.createPeerConnection(
            config,
            object : PeerConnection.Observer {

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                    Log.d(TAG, "ICE gathering: $state")
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "ICE connection: $state")
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceCandidate(candidate: IceCandidate) {}
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(dc: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(
                    receiver: RtpReceiver,
                    streams: Array<MediaStream>
                ) {}
            }
        )!!
    }

    // -------------------- STREAM --------------------

    private fun startStreaming(permissionData: Intent) {

        Log.d(TAG, "Starting screen capture")

        val videoSource = factory.createVideoSource(false)
        val videoTrack = factory.createVideoTrack("screen", videoSource)

        val capturer = ScreenCapturerAndroid(
            permissionData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped")
                }
            }
        )

        val helper = SurfaceTextureHelper.create(
            "ScreenCaptureThread",
            eglBase.eglBaseContext
        )

        capturer.initialize(helper, this, videoSource.capturerObserver)
        capturer.startCapture(720, 1280, 30)

        pc.addTrack(videoTrack)

        // 🔥 OBLIGATORIO PARA WHIP
        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            )
        )

        createOffer()
    }

    // -------------------- WHIP --------------------

    private fun createOffer() {

        val constraints = MediaConstraints().apply {
            mandatory.add(
                MediaConstraints.KeyValuePair("IceRestart", "false")
            )
            optional.add(
                MediaConstraints.KeyValuePair("TrickleIce", "false")
            )
        }

        pc.createOffer(object : SdpObserver {

            override fun onCreateSuccess(desc: SessionDescription) {
                Log.d(TAG, "SDP offer created")
                pc.setLocalDescription(this, desc)
            }

            override fun onSetSuccess() {
                Log.d(TAG, "Local SDP set")
                sendWhipOffer(pc.localDescription!!)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Offer error: $error")
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Set local SDP error: $error")
            }

        }, constraints)
    }

    private fun sendWhipOffer(offer: SessionDescription) {

        Log.d(TAG, "Sending WHIP offer")

        val body = offer.description
            .toRequestBody("application/sdp".toMediaType())

        val request = Request.Builder()
            .url(whipUrl)
            .post(body)
            .build()

        Thread {
            try {
                OkHttpClient().newCall(request).execute().use { res ->

                    Log.d(TAG, "WHIP HTTP ${res.code}")

                    val answerSdp = res.body?.string()
                    if (answerSdp.isNullOrBlank()) {
                        Log.e(TAG, "Empty SDP answer")
                        return@use
                    }

                    pc.setRemoteDescription(
                        object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d(TAG, "STREAMING ACTIVE 🚀")
                            }

                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "Set remote SDP error: $error")
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        },
                        SessionDescription(
                            SessionDescription.Type.ANSWER,
                            answerSdp
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "WHIP error", e)
            }
        }.start()
    }
}
