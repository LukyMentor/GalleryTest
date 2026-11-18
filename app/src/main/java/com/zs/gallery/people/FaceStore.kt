package com.zs.gallery.people

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.RectF
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One detected face: where it is in which photo, and who it belongs to. */
class FaceRow(
    val id: Long,
    val mediaId: Long,
    /** Face box in 0..1 of the (upright) photo. */
    val box: RectF,
    val score: Float,
    val personId: Long,
)

class Person(
    val id: Long,
    val name: String?,
    val hidden: Boolean,
    /** Photos this person is in. */
    val photos: Int,
    /** The face used as the person's picture. */
    val cover: FaceRow?,
)

/**
 * Local database of faces and people (app-private; nothing leaves the phone).
 *
 * person_id: > 0 a person, 0 = not grouped yet, -1 = "not this person" (kept out of groups).
 * locked: set by the user (moved / removed), never changed by automatic grouping.
 */
internal class FaceStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "faces.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE scanned (media_id INTEGER PRIMARY KEY, modified INTEGER, faces INTEGER)")
        db.execSQL(
            "CREATE TABLE faces (id INTEGER PRIMARY KEY AUTOINCREMENT, media_id INTEGER, " +
                    "x1 REAL, y1 REAL, x2 REAL, y2 REAL, score REAL, embedding BLOB, " +
                    "person_id INTEGER DEFAULT 0, locked INTEGER DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX faces_media ON faces(media_id)")
        db.execSQL("CREATE INDEX faces_person ON faces(person_id)")
        db.execSQL("CREATE TABLE persons (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, hidden INTEGER DEFAULT 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // --- scanning ---------------------------------------------------------------------------

    /** media id -> modified time of every photo already scanned. */
    fun scanned(): HashMap<Long, Long> {
        val map = HashMap<Long, Long>()
        readableDatabase.rawQuery("SELECT media_id, modified FROM scanned", null).use { c ->
            while (c.moveToNext()) map[c.getLong(0)] = c.getLong(1)
        }
        return map
    }

    fun scannedCount(): Int = count("SELECT COUNT(*) FROM scanned")
    fun faceCount(): Int = count("SELECT COUNT(*) FROM faces")

    /** Saves the faces of one photo (replacing older results for it). Returns the new face ids. */
    fun saveScan(mediaId: Long, modified: Long, faces: List<Pair<RectF, Pair<Float, FloatArray>>>): List<Long> {
        val db = writableDatabase
        val ids = ArrayList<Long>()
        db.beginTransaction()
        try {
            db.delete("faces", "media_id = ? AND locked = 0", arrayOf("$mediaId"))
            for ((box, rest) in faces) {
                val (score, embedding) = rest
                ids += db.insert("faces", null, ContentValues().apply {
                    put("media_id", mediaId)
                    put("x1", box.left); put("y1", box.top); put("x2", box.right); put("y2", box.bottom)
                    put("score", score)
                    put("embedding", toBlob(embedding))
                    put("person_id", 0)
                })
            }
            db.insertWithOnConflict("scanned", null, ContentValues().apply {
                put("media_id", mediaId); put("modified", modified); put("faces", faces.size)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ids
    }

    /** Forgets photos that no longer exist. */
    fun removeMedia(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                db.delete("faces", "media_id = ?", arrayOf("$id"))
                db.delete("scanned", "media_id = ?", arrayOf("$id"))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // --- grouping -----------------------------------------------------------------------------

    /** Embeddings of faces, by person (person_id > 0) - for computing group centres. */
    fun embeddingsByPerson(): Map<Long, List<FloatArray>> {
        val map = HashMap<Long, MutableList<FloatArray>>()
        // (Photos added to a person by hand without a detected face have no embedding.)
        readableDatabase.rawQuery("SELECT person_id, embedding FROM faces WHERE person_id > 0 AND LENGTH(embedding) > 0", null).use { c ->
            while (c.moveToNext()) map.getOrPut(c.getLong(0)) { ArrayList() } += fromBlob(c.getBlob(1))
        }
        return map
    }

    /** Faces not grouped yet: id -> embedding. */
    fun ungrouped(): List<Pair<Long, FloatArray>> {
        val list = ArrayList<Pair<Long, FloatArray>>()
        readableDatabase.rawQuery("SELECT id, embedding FROM faces WHERE person_id = 0", null).use { c ->
            while (c.moveToNext()) list += c.getLong(0) to fromBlob(c.getBlob(1))
        }
        return list
    }

    fun newPerson(): Long = writableDatabase.insert("persons", null, ContentValues().apply { putNull("name") })

    fun assign(faceId: Long, personId: Long) {
        writableDatabase.update("faces", ContentValues().apply { put("person_id", personId) }, "id = ?", arrayOf("$faceId"))
    }

    fun inTransaction(block: () -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(); db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Moves every face of [from] into [into] and deletes [from]. */
    fun merge(from: Long, into: Long) {
        inTransaction {
            writableDatabase.execSQL("UPDATE faces SET person_id = ? WHERE person_id = ?", arrayOf(into, from))
            // Keep a name if only the merged-away person had one.
            writableDatabase.execSQL(
                "UPDATE persons SET name = (SELECT name FROM persons WHERE id = ?) WHERE id = ? AND name IS NULL",
                arrayOf(from, into)
            )
            writableDatabase.delete("persons", "id = ?", arrayOf("$from"))
        }
    }

    fun names(): Map<Long, String?> {
        val map = HashMap<Long, String?>()
        readableDatabase.rawQuery("SELECT id, name FROM persons", null).use { c ->
            while (c.moveToNext()) map[c.getLong(0)] = if (c.isNull(1)) null else c.getString(1)
        }
        return map
    }

    /** Deletes people who have no faces left. */
    fun dropEmptyPersons() {
        writableDatabase.execSQL("DELETE FROM persons WHERE id NOT IN (SELECT DISTINCT person_id FROM faces WHERE person_id > 0)")
    }

    // --- user edits ---------------------------------------------------------------------------

    fun rename(personId: Long, name: String?) {
        writableDatabase.update("persons", ContentValues().apply {
            if (name.isNullOrBlank()) putNull("name") else put("name", name.trim())
        }, "id = ?", arrayOf("$personId"))
    }

    fun setHidden(personId: Long, hidden: Boolean) {
        writableDatabase.update("persons", ContentValues().apply { put("hidden", if (hidden) 1 else 0) },
            "id = ?", arrayOf("$personId"))
    }

    /**
     * The faces of [personId] in [mediaIds] go to [target] (a person id, or -1 = "not this
     * person"), marked as the user's decision.
     */
    fun moveFaces(personId: Long, mediaIds: Collection<Long>, target: Long) {
        inTransaction {
            for (m in mediaIds)
                writableDatabase.execSQL(
                    "UPDATE faces SET person_id = ?, locked = 1 WHERE person_id = ? AND media_id = ?",
                    arrayOf(target, personId, m)
                )
        }
        dropEmptyPersons()
    }

    /** Clears all automatic grouping (keeps the user's own moves and names on locked faces). */
    fun resetGrouping() {
        inTransaction {
            writableDatabase.execSQL("UPDATE faces SET person_id = 0 WHERE locked = 0")
        }
        dropEmptyPersons()
    }

    fun clearAll() {
        inTransaction {
            writableDatabase.execSQL("DELETE FROM faces")
            writableDatabase.execSQL("DELETE FROM scanned")
            writableDatabase.execSQL("DELETE FROM persons")
        }
    }

    // --- reading for the UI ---------------------------------------------------------------------

    fun people(): List<Person> {
        val names = HashMap<Long, Pair<String?, Boolean>>()
        readableDatabase.rawQuery("SELECT id, name, hidden FROM persons", null).use { c ->
            while (c.moveToNext())
                names[c.getLong(0)] = (if (c.isNull(1)) null else c.getString(1)) to (c.getInt(2) == 1)
        }
        val photos = HashMap<Long, HashSet<Long>>()
        val cover = HashMap<Long, FaceRow>()
        readableDatabase.rawQuery(
            "SELECT id, media_id, x1, y1, x2, y2, score, person_id FROM faces WHERE person_id > 0", null
        ).use { c ->
            while (c.moveToNext()) {
                val row = row(c)
                photos.getOrPut(row.personId) { HashSet() } += row.mediaId
                // Picture: the biggest, clearest face.
                val best = cover[row.personId]
                if (best == null || quality(row) > quality(best)) cover[row.personId] = row
            }
        }
        return names.mapNotNull { (id, info) ->
            val n = photos[id]?.size ?: return@mapNotNull null
            Person(id, info.first, info.second, n, cover[id])
        }.sortedWith(compareByDescending<Person> { it.name != null }.thenByDescending { it.photos })
    }

    fun person(id: Long): Person? = people().firstOrNull { it.id == id }

    /** Every detected face in one photo (grouped or not), left to right, with the person's name. */
    fun facesIn(mediaId: Long): List<Pair<FaceRow, String?>> {
        val names = names()
        val list = ArrayList<Pair<FaceRow, String?>>()
        readableDatabase.rawQuery(
            "SELECT id, media_id, x1, y1, x2, y2, score, person_id FROM faces WHERE media_id = ? AND LENGTH(embedding) > 0",
            arrayOf("$mediaId")
        ).use { c -> while (c.moveToNext()) row(c).let { list += it to names[it.personId] } }
        return list.sortedBy { it.first.box.left }
    }

    /** The user says this face is [personId]. */
    fun assignFace(faceId: Long, personId: Long) {
        writableDatabase.update("faces", ContentValues().apply { put("person_id", personId); put("locked", 1) },
            "id = ?", arrayOf("$faceId"))
        dropEmptyPersons()
    }

    /**
     * Adds photos to people in one go. A photo with exactly one face, added to one person, gets
     * that face; otherwise the whole photo is added. Photos already with that person are skipped.
     */
    fun addPhotos(mediaIds: Collection<Long>, personIds: Collection<Long>) {
        inTransaction {
            for (m in mediaIds) {
                val faces = facesIn(m)
                for (p in personIds) {
                    if (faces.any { it.first.personId == p }) continue
                    val only = faces.singleOrNull()
                    if (only != null && personIds.size == 1)
                        writableDatabase.update("faces", ContentValues().apply { put("person_id", p); put("locked", 1) },
                            "id = ?", arrayOf("${only.first.id}"))
                    else addManual(m, p)
                }
            }
        }
        dropEmptyPersons()
    }

    /** The user says [personId] is in this photo, but no face was detected there. */
    fun addManual(mediaId: Long, personId: Long) {
        writableDatabase.insert("faces", null, ContentValues().apply {
            put("media_id", mediaId)
            put("x1", 0f); put("y1", 0f); put("x2", 1f); put("y2", 1f)
            put("score", 0f)
            put("embedding", ByteArray(0))
            put("person_id", personId)
            put("locked", 1)
        })
    }

    /** People in one photo: their face in *this* photo and their name (left to right). */
    fun peopleIn(mediaId: Long): List<Pair<FaceRow, String?>> {
        val names = names()
        val list = ArrayList<Pair<FaceRow, String?>>()
        readableDatabase.rawQuery(
            "SELECT f.id, f.media_id, f.x1, f.y1, f.x2, f.y2, f.score, f.person_id FROM faces f " +
                    "JOIN persons p ON p.id = f.person_id WHERE f.media_id = ? AND f.person_id > 0 AND p.hidden = 0",
            arrayOf("$mediaId")
        ).use { c -> while (c.moveToNext()) row(c).let { list += it to names[it.personId] } }
        // A real face beats a hand-added "whole photo" entry for the same person.
        return list.sortedByDescending { it.first.score }.distinctBy { it.first.personId }.sortedBy { it.first.box.left }
    }

    /** Photos of a person, newest scanned first (the screen sorts them by date). */
    fun photosOf(personId: Long): List<Long> {
        val list = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT DISTINCT media_id FROM faces WHERE person_id = ?", arrayOf("$personId")).use { c ->
            while (c.moveToNext()) list += c.getLong(0)
        }
        return list
    }

    private fun quality(r: FaceRow) = r.box.width() * r.box.height() * r.score

    private fun row(c: android.database.Cursor) = FaceRow(
        id = c.getLong(0), mediaId = c.getLong(1),
        box = RectF(c.getFloat(2), c.getFloat(3), c.getFloat(4), c.getFloat(5)),
        score = c.getFloat(6), personId = c.getLong(7)
    )

    private fun count(sql: String): Int = readableDatabase.rawQuery(sql, null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    private fun toBlob(e: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(e.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in e) b.putFloat(v)
        return b.array()
    }

    private fun fromBlob(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { b.getFloat() }
    }
}
