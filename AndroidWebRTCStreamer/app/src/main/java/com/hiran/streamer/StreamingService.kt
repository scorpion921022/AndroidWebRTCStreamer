package com.hiran.streamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.Logging
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper

class StreamingService : Service() {

    companion object {
        private const val TAG = "WHIP-ANDROID"
        private const val CHANNEL_ID = "stream_channel"
        private const val NOTIFICATION_ID = 100

        const val ACTION_START = "com.hiran.streamer.action.START"
        const val ACTION_STOP = "com.hiran.streamer.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }

    private val whipUrl = "http://10.20.30.41:8889/gameplay/whip"

    private lateinit var factory: PeerConnectionFactory
    private lateinit var pc: PeerConnection
    private lateinit var eglBase: EglBase
    private lateinit var capturer: ScreenCapturerAndroid
    private lateinit var textureHelper: SurfaceTextureHelper
    private var isStopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Iniciando transmisión..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != RESULT_OK || resultData == null) {
                    Log.e(TAG, "Falta permiso de captura de pantalla")
                    stopStreaming()
                    return START_NOT_STICKY
                }

                if (!::pc.isInitialized) {
                    initWebRTC()
                    startStreaming(resultData)
                }
            }
        }

        return START_STICKY
    }

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
                    false
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
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        pc = factory.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "ICE state: $state")
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
                override fun onIceCandidate(candidate: IceCandidate) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
                override fun onAddStream(stream: MediaStream) = Unit
                override fun onRemoveStream(stream: MediaStream) = Unit
                override fun onDataChannel(dc: DataChannel) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) = Unit
            }
        )!!
    }

    private fun startStreaming(permissionData: Intent) {
        val videoSource = factory.createVideoSource(false)
        val videoTrack = factory.createVideoTrack("screen", videoSource)

        capturer = ScreenCapturerAndroid(
            permissionData,
            object : MediaProjection.Callback() {}
        )

        textureHelper = SurfaceTextureHelper.create(
            "ScreenCaptureThread",
            eglBase.eglBaseContext
        )

        capturer.initialize(textureHelper, this, videoSource.capturerObserver)
        capturer.startCapture(720, 1280, 30)

        pc.addTrack(videoTrack)

        val audioSource = factory.createAudioSource(MediaConstraints())
        val audioTrack = factory.createAudioTrack("audio", audioSource)
        pc.addTrack(audioTrack)

        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
        )

        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
        )

        createOffer()
    }

    private fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "false"))
            optional.add(MediaConstraints.KeyValuePair("TrickleIce", "false"))
        }

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(this, desc)
            }

            override fun onSetSuccess() {
                sendWhipOffer(pc.localDescription ?: return)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Offer error: $error")
                updateNotification("Error al crear oferta")
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Set local SDP error: $error")
                updateNotification("Error SDP local")
            }
        }, constraints)
    }

    private fun sendWhipOffer(offer: SessionDescription) {
        val body = offer.description.toRequestBody("application/sdp".toMediaType())
        val request = Request.Builder()
            .url(whipUrl)
            .post(body)
            .build()

        Thread {
            try {
                OkHttpClient().newCall(request).execute().use { res ->
                    if (!res.isSuccessful) {
                        Log.e(TAG, "WHIP status: ${res.code}")
                        updateNotification("Error WHIP: ${res.code}")
                        return@use
                    }

                    val answer = res.body?.string() ?: return@use

                    pc.setRemoteDescription(
                        object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d(TAG, "STREAMING ACTIVE")
                                updateNotification("Transmitiendo pantalla y audio")
                            }

                            override fun onSetFailure(error: String?) {
                                updateNotification("Error SDP remoto")
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) = Unit
                            override fun onCreateFailure(p0: String?) = Unit
                        },
                        SessionDescription(SessionDescription.Type.ANSWER, answer)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "WHIP error", e)
                updateNotification("Error de red")
            }
        }.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, StreamingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WebRTC Stream activo")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Detener", stopPendingIntent)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun stopStreaming() {
        if (isStopping) return
        isStopping = true

        try {
            if (::capturer.isInitialized) {
                capturer.stopCapture()
                capturer.dispose()
            }
        } catch (_: Exception) {
        }

        if (::textureHelper.isInitialized) {
            textureHelper.dispose()
        }

        if (::pc.isInitialized) {
            pc.close()
            pc.dispose()
        }

        if (::factory.isInitialized) {
            factory.dispose()
        }

        if (::eglBase.isInitialized) {
            eglBase.release()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!isStopping) {
            stopStreaming()
        }
        super.onDestroy()
    }
}
