package com.hiran.streamer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.webrtc.*

class MainActivity : AppCompatActivity() {

    private lateinit var peerConnectionFactory: PeerConnectionFactory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val initializationOptions =
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions()

        PeerConnectionFactory.initialize(initializationOptions)

        peerConnectionFactory =
            PeerConnectionFactory.builder()
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(
                        EglBase.create().eglBaseContext,
                        true,
                        true
                    )
                )
                .setVideoDecoderFactory(
                    DefaultVideoDecoderFactory(
                        EglBase.create().eglBaseContext
                    )
                )
                .createPeerConnectionFactory()

        createPeerConnection()
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(mutableListOf())

        peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {

                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onIceCandidate(candidate: IceCandidate) {}
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(dc: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {}
            }
        )
    }
}


