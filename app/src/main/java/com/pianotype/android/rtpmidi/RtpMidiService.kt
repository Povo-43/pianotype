package com.pianotype.android.rtpmidi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pianotype.android.MainActivity
import com.pianotype.android.R
import com.pianotype.android.config.Config
import com.pianotype.android.config.ConfigRepository
import com.pianotype.android.midi.MidiEvent
import com.pianotype.android.midi.MidiListener

/**
 * 受信した MIDI を RTP MIDI (AppleMIDI) でネットワークへ転送する常駐サービス。
 * IME とは独立に MIDI デバイスを開くため、テキスト入力中でなくても動作する。
 */
class RtpMidiService : Service() {

    companion object {
        private const val CHANNEL_ID = "rtp_midi"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RtpMidiService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RtpMidiService::class.java))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sender: RtpMidiSender? = null
    private var midiListener: MidiListener? = null
    private var boundBindPort = -1
    private var boundDevice: String? = null
    private var boundChannel: Int? = null

    private val configListener: (Config) -> Unit = { cfg ->
        mainHandler.post { applyConfig(cfg) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        ConfigRepository.addListener(configListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applyConfig(ConfigRepository.current)
        return START_STICKY
    }

    override fun onDestroy() {
        ConfigRepository.removeListener(configListener)
        midiListener?.close()
        midiListener = null
        sender?.close()
        sender = null
        super.onDestroy()
    }

    private fun applyConfig(cfg: Config) {
        if (!cfg.rtpMidiEnabled) {
            stopSelf()
            return
        }

        // 待ち受けポートが変わったらソケットを作り直す
        if (sender != null && cfg.rtpMidiPort != boundBindPort) {
            midiListener?.close()
            midiListener = null
            sender?.close()
            sender = null
        }

        if (sender == null) {
            boundBindPort = cfg.rtpMidiPort
            val s = RtpMidiSender(cfg.rtpMidiName) { RtpMidiBus.publish(it) }
            s.start(boundBindPort, cfg.rtpMidiHost, cfg.rtpMidiPort)
            sender = s
        } else {
            sender?.reconfigure(cfg.rtpMidiHost, cfg.rtpMidiPort, cfg.rtpMidiName)
        }

        // MIDI 入力を開く (IME とは独立した接続。同一デバイスの多重オープンは可能)
        if (midiListener != null &&
            (cfg.midiDevice != boundDevice || cfg.midiChannel != boundChannel)
        ) {
            midiListener?.close()
            midiListener = null
        }
        if (midiListener == null) {
            boundDevice = cfg.midiDevice
            boundChannel = cfg.midiChannel
            midiListener = MidiListener(this, cfg.midiDevice, cfg.midiChannel) { event ->
                forward(event)
            }.also { it.start() }
        }
    }

    private fun forward(event: MidiEvent) {
        val s = sender ?: return
        val cfg = ConfigRepository.current
        val ch = cfg.rtpMidiChannel ?: when (event) {
            is MidiEvent.NoteOn -> event.channel
            is MidiEvent.NoteOff -> event.channel
        }
        when (event) {
            is MidiEvent.NoteOn ->
                if (event.velocity > 0) s.sendNoteOn(ch, event.note, event.velocity)
                else s.sendNoteOff(ch, event.note, 0)
            is MidiEvent.NoteOff -> s.sendNoteOff(ch, event.note, event.velocity)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "RTP MIDI",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "受信した MIDI のネットワーク転送" }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rtp)
            .setContentTitle("Pianotype RTP MIDI")
            .setContentText("受信した MIDI を転送中")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
}
