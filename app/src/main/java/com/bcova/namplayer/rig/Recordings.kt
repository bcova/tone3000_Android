package com.bcova.namplayer.rig

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** A finished recording. [uri] can be shared (null on Android 8-9, where it's a plain file). */
data class Recording(val name: String, val seconds: Double, val uri: Uri?, val location: String)

object Recordings {
    /** Moves a finished WAV from app storage to Music/Ampfield where other apps can find it. */
    fun publish(context: Context, file: File, seconds: Double): Recording {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Ampfield")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: error("No storage available")
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                return Recording(file.name, seconds, uri, "Music/Ampfield")
            }
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Ampfield").apply { mkdirs() }
            val dest = File(dir, file.name)
            file.copyTo(dest, overwrite = true)
            return Recording(file.name, seconds, null, dest.parent ?: "app storage")
        } finally {
            file.delete()
        }
    }
}
