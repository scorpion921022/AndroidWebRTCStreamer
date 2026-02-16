package com.hiran.streamer

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import org.webrtc.*
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var peer: PeerConnection
    private lateinit var factory: PeerConnectionFactory

    private val WHIP_URL = "http://10.20.30.41:8889/whip/hiran"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        findViewById<Button>(R.id.startBtn).setOnClickListener {
            startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                100
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            startWebRTC(data)
        }
    }

    private fun startWebRTC(data: Intent) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()

        val egl = EglBase.create()

        val videoSource = factory.createVideoSource(false)
        val capturer = ScreenCapturerAndroid(data, null)
        capturer.initialize(
            SurfaceTextureHelper.create("Screen", egl.eglBaseContext),
            this,
            videoSource.capturerObserver
        )
        capturer.startCapture(720, 1600, 30)

        val videoTrack = factory.createVideoTrack("video", videoSource)

        val audioSource = factory.createAudioSource(MediaConstraints())
        val audioTrack = factory.createAudioTrack("audio", audioSource)

        val config = PeerConnection.RTCConfiguration(emptyList())
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        config.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED

        peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {}
            override fun onTrack(t: RtpTransceiver?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })!!

        peer.addTrack(videoTrack)
        peer.addTrack(audioTrack)

        peer.createOffer(object : SdpObserver {
            override fun onCreateSuccess(offer: SessionDescription) {
                peer.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        sendWhipOffer(offer.description)
                    }
                    override fun onSetFailure(p0: String?) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, offer)
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    private fun sendWhipOffer(sdp: String) {
        Thread {
            val conn = URL(WHIP_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/sdp")
            conn.doOutput = true
            conn.outputStream.write(sdp.toByteArray())

            val answer = conn.inputStream.bufferedReader().readText()
            peer.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, SessionDescription(SessionDescription.Type.ANSWER, answer))
        }.start()
    }
}
