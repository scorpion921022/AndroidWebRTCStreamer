package com.hiran.streamer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import okhttp3.*
import org.webrtc.*
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var peerConnection: PeerConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initWebRTC()

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            startWhipStreaming()
        }
    }

    // ======================
    // INIT WEBRTC
    // ======================
    private fun initWebRTC() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    // ======================
    // START STREAM
    // ======================
    private fun startWhipStreaming() {
        createPeerConnection()
        createDummyVideoTrack() // (temporal)
        createOfferAndSendWhip()
    }

    // ======================
    // PEER CONNECTION
    // ======================
    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        peerConnection = peerConnectionFactory.createPeerConnection(
            iceServers,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {}
                override fun onIceCandidatesRemoved(p0: Array<IceCandidate>) {}
                override fun onSignalingChange(p0: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) {}
                override fun onAddStream(p0: MediaStream) {}
                override fun onRemoveStream(p0: MediaStream) {}
                override fun onDataChannel(p0: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver, p1: Array<MediaStream>) {}
            }
        )!!
    }

    // ======================
    // VIDEO TRACK (DUMMY)
    // ======================
    private fun createDummyVideoTrack() {
        val videoSource = peerConnectionFactory.createVideoSource(false)
        val videoTrack = peerConnectionFactory.createVideoTrack("video", videoSource)

        val stream = peerConnectionFactory.createLocalMediaStream("stream")
        stream.addTrack(videoTrack)
        peerConnection.addStream(stream)
    }

    // ======================
    // SDP OFFER
    // ======================
    private fun createOfferAndSendWhip() {
        val constraints = MediaConstraints()

        peerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection.setLocalDescription(this, desc)
                sendWhipOffer(desc.description)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    // ======================
    // WHIP HTTP POST
    // ======================
    private fun sendWhipOffer(sdp: String) {
        val client = OkHttpClient()

        val body = RequestBody.create(
            "application/sdp".toMediaTypeOrNull(),
            sdp
        )

        val request = Request.Builder()
            .url("http://10.20.30.1:8889/gameplay/whip")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                val answerSdp = response.body?.string() ?: return

                val answer = SessionDescription(
                    SessionDescription.Type.ANSWER,
                    answerSdp
                )

                peerConnection.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {}
                    override fun onSetFailure(p0: String?) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, answer)
            }
        })
    }
}



