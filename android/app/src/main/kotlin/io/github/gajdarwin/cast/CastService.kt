package io.github.gajdarwin.cast

import android.app.*
import android.content.Intent
import android.os.IBinder

class CastService : Service() {
    companion object { private var runningService:CastService?=null; fun refreshLanguage(){runningService?.showNotification()}; private var instance:PlaybackSession?=null; fun logIfRunning(source:String,level:String,message:String){instance?.let{s->s.main.post{s.log(source,level,message)}}}; @Synchronized fun session(context:android.content.Context):PlaybackSession=instance?:PlaybackSession(context.applicationContext).also{instance=it}; var relay: MediaRelay? = null; var localMovie: LocalMovie? = null; var youtube: YoutubeCast? = null }
    private var wake:android.os.PowerManager.WakeLock?=null
    override fun onCreate(){super.onCreate();runningService=this}
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { instance?.stop(); relay?.stop(); relay = null; localMovie?.stop(); localMovie = null; youtube?.stop(); youtube = null; stopSelf(); return START_NOT_STICKY }
        if(wake?.isHeld!=true){wake=getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"Darwin:cast").apply{setReferenceCounted(false);acquire(6*60*60*1000L)}}
        showNotification()
        return START_NOT_STICKY
    }
    private fun showNotification(){
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("casting", L("Casting to TV"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, CastService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "casting").setContentTitle("Darwin Cast")
            .setContentText(L("Sending video to your TV over Wi-Fi")).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open).addAction(Notification.Action.Builder(null, L("Stop casting"), stop).build()).setOngoing(true).build())
    }
    override fun onDestroy() { runningService=null; wake?.let{if(it.isHeld)it.release()};wake=null;relay?.stop(); relay = null; localMovie?.stop(); localMovie = null; youtube?.stop(); youtube = null; super.onDestroy() }
}
