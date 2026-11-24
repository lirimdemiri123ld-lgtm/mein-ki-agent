package ch.lirim.jarvis2

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Kontaktbild direkt über Android setzen – ohne durch die Kontakte-App zu klicken.
 * Bildquelle: das neueste Bild in der Galerie (optional nur aus einem Ordner, z. B. «WhatsApp»).
 */
object ContactPhoto {
    data class Img(val uri: Uri, val name: String, val folder: String, val addedSec: Long)

    fun canWrite(c: Context) = c.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    fun canReadImages(c: Context) = c.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    /** Neuestes Bild der Galerie; mit [folderHint] nur aus Ordnern, deren Name den Text enthält. */
    fun newestImage(c: Context, folderHint: String?): Img? {
        val hint = folderHint?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
        )
        c.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cur ->
            var n = 0
            while (cur.moveToNext() && n++ < 500) {
                val folder = cur.getString(2).orEmpty()
                if (hint != null && !folder.lowercase().contains(hint)) continue
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cur.getLong(0))
                return Img(uri, cur.getString(1).orEmpty(), folder, cur.getLong(3))
            }
        }
        return null
    }

    /** Kontakt-ID zu einem Anzeigenamen (exakter Name aus Contacts.resolve). */
    private fun contactId(c: Context, displayName: String): Long? {
        c.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            "${ContactsContract.Contacts.DISPLAY_NAME} = ?", arrayOf(displayName), null
        )?.use { if (it.moveToFirst()) return it.getLong(0) }
        return null
    }

    private fun rawIds(c: Context, contactId: Long): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        c.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
            arrayOf(contactId.toString()), null
        )?.use { while (it.moveToNext()) out.add(it.getLong(0) to it.getString(1).orEmpty()) }
        return out
    }

    /** Bild laden, Ausrichtung korrigieren, auf max. [maxPx] verkleinern. */
    fun load(c: Context, uri: Uri, maxPx: Int): Bitmap {
        val src = ImageDecoder.createSource(c.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            val w = info.size.width; val h = info.size.height
            val f = minOf(1f, maxPx.toFloat() / maxOf(w, h))
            if (f < 1f) dec.setTargetSize((w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1))
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    fun jpeg(b: Bitmap, q: Int): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
    fun preview(c: Context, uri: Uri): String = Base64.encodeToString(jpeg(load(c, uri, 480), 60), Base64.NO_WRAP)

    /** Setzt das Bild bei allen beschreibbaren Roh-Kontakten. Gibt (Anzahl ok, Fehlertext) zurück. */
    fun set(c: Context, displayName: String, uri: Uri): Pair<Int, String?> {
        val id = contactId(c, displayName) ?: return 0 to "Kontakt «$displayName» nicht gefunden."
        val raws = rawIds(c, id)
        if (raws.isEmpty()) return 0 to "Zum Kontakt gibt es keinen bearbeitbaren Eintrag."
        val bytes = jpeg(load(c, uri, 1080), 90)
        var ok = 0
        var lastErr: String? = null
        for ((rawId, _) in raws) {
            try {
                val target = Uri.withAppendedPath(
                    ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
                    ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY
                )
                c.contentResolver.openAssetFileDescriptor(target, "rw")?.use { fd ->
                    fd.createOutputStream().use { it.write(bytes) }
                    ok++
                }
            } catch (e: Exception) {
                lastErr = e.message   // z. B. schreibgeschützte Konten (WhatsApp-Eintrag) – überspringen
            }
        }
        return ok to (if (ok == 0) "Bild konnte nicht gespeichert werden: ${lastErr ?: "unbekannter Fehler"}" else null)
    }
}
