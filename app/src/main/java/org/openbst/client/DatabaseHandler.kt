package org.openbst.client

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHandler(private var context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    companion object {
        private const val DATABASE_VERSION  = 1
        private const val DATABASE_NAME     = "OpenVBT"
        private const val TABLE_REPS        = "Reps"
        private const val KEY_TIMESTAMP_MS  = "timestamp_ms"
        private const val KEY_DATE          = "date"
        private const val KEY_MAX_VELOCITY  = "max_velocity"
        private const val KEY_MIN_VELOCITY  = "min_velocity"
        private const val KEY_MAX_ACCEL     = "max_accel"
        private const val KEY_MIN_ACCEL     = "min_accel"
    }

    override fun onCreate(db: SQLiteDatabase?) {
        val createRepsTableSql = ("CREATE TABLE " + TABLE_REPS + "("
                + "$KEY_TIMESTAMP_MS INTEGER PRIMARY KEY NOT NULL, "
                + "$KEY_DATE         TEXT, "
                + "$KEY_MAX_VELOCITY REAL, "
                + "$KEY_MIN_VELOCITY REAL, "
                + "$KEY_MAX_ACCEL    REAL, "
                + "$KEY_MIN_ACCEL    REAL  "
                + ");")

        db?.execSQL(createRepsTableSql)
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        db!!.execSQL("DROP TABLE IF EXISTS $TABLE_REPS")
        onCreate(db)
    }

    fun addRepetition(repData: RepData) : Long {
        val db = this.writableDatabase

        val contentValues = ContentValues()
        contentValues.put(KEY_TIMESTAMP_MS, repData.timestamp_ms)
        contentValues.put(KEY_DATE, repData.date_string)
        contentValues.put(KEY_MAX_VELOCITY, repData.max_velocity)
        contentValues.put(KEY_MIN_VELOCITY, repData.min_velocity)
        contentValues.put(KEY_MAX_ACCEL, repData.max_accel)
        contentValues.put(KEY_MIN_ACCEL, repData.min_accel)

        val success = db.insert(TABLE_REPS, null, contentValues)

        db.close()
        return success
    }

    fun deleteRepetition(repData: RepData) : Int {
        val db = this.writableDatabase

        val contentValues = ContentValues()
        contentValues.put(KEY_TIMESTAMP_MS, repData.timestamp_ms) // Timestamp is our primary key

        val success = db.delete(TABLE_REPS, "$KEY_TIMESTAMP_MS = ?", arrayOf("${repData.timestamp_ms}"))

        db.close()
        return success
    }

    fun getRepetitionsAtDate(dateString: String) : List<RepData> {
        val db = this.readableDatabase

        val repsList : ArrayList<RepData> = ArrayList<RepData>()

        val selectQuery = "SELECT * FROM $TABLE_REPS WHERE $KEY_DATE = ?"
        val cursor = db.rawQuery(selectQuery, arrayOf(dateString))

        if (cursor.moveToFirst()) {
            do {
                val timestampMs = cursor.getLong(cursor.getColumnIndex(KEY_TIMESTAMP_MS))
                val dateString  = cursor.getString(cursor.getColumnIndex(KEY_DATE))
                val maxVelocity = cursor.getFloat(cursor.getColumnIndex(KEY_MAX_VELOCITY))
                val minVelocity = cursor.getFloat(cursor.getColumnIndex(KEY_MIN_VELOCITY))
                val maxAccel    = cursor.getFloat(cursor.getColumnIndex(KEY_MAX_ACCEL))
                val minAccel    = cursor.getFloat(cursor.getColumnIndex(KEY_MIN_ACCEL))
                repsList.add(RepData(timestampMs, dateString, maxVelocity, minVelocity, maxAccel, minAccel))
            } while (cursor.moveToNext())
        }

        cursor.close()
        return repsList
    }

}