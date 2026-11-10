package dev.pleos.starter.teum.records

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter

internal object RecordJson {
    fun encode(record: ActivityRecord): String = write { writeRecord(it, record) }

    fun encodeAll(records: List<ActivityRecord>): String = write { writer ->
        writer.beginArray()
        records.forEach { writeRecord(writer, it) }
        writer.endArray()
    }

    private fun write(block: (JsonWriter) -> Unit): String {
        val output = StringWriter()
        JsonWriter(output).use { block(it) }
        return output.toString()
    }

    private fun writeRecord(writer: JsonWriter, record: ActivityRecord) {
        writer.beginObject()
        writer.name("id").value(record.id)
        writer.name("situationId").value(record.situationId)
        writer.name("activityTitle").value(record.activityTitle)
        writer.name("totalSteps").value(record.totalSteps)
        writer.name("completedStepIndexes").beginArray()
        record.completedStepIndexes.forEach { writer.value(it) }
        writer.endArray()
        writer.name("elapsedSeconds").value(record.elapsedSeconds)
        writer.name("outcome").value(record.outcome)
        writer.name("note").value(record.note)
        writer.name("nextAction").value(record.nextAction)
        writer.name("recallAccepted").value(record.recallAccepted)
        writer.name("historyConsentAtSave").value(record.historyConsentAtSave)
        writer.name("createdAtEpochMs").value(record.createdAtEpochMs)
        writer.name("updatedAtEpochMs").value(record.updatedAtEpochMs)
        writer.endObject()
    }

    fun decode(raw: String): ActivityRecord? = parse(raw) { readRecord(it) }

    fun decodeAll(raw: String): List<ActivityRecord> =
        parse(raw) { readArray(it) } ?: emptyList()

    fun isMalformedArray(raw: String): Boolean =
        raw.isNotBlank() && parse(raw) { readArray(it) } == null

    private fun <T> parse(raw: String, block: (JsonReader) -> T): T? {
        return try {
            JsonReader(StringReader(raw)).use { reader ->
                reader.strictness = Strictness.STRICT
                val result = block(reader)
                require(reader.peek() == JsonToken.END_DOCUMENT)
                result
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun readArray(reader: JsonReader): List<ActivityRecord> {
        val records = mutableListOf<ActivityRecord>()
        reader.beginArray()
        while (reader.hasNext()) records.add(readRecord(reader))
        reader.endArray()
        return records
    }

    private fun readRecord(reader: JsonReader): ActivityRecord {
        val strings = mutableMapOf<String, String>()
        val numbers = mutableMapOf<String, Long>()
        val booleans = mutableMapOf<String, Boolean>()
        var steps = emptyList<Int>()
        val seen = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(seen.add(name)) { "duplicate field" }
            when (name) {
                "id", "situationId", "activityTitle", "outcome", "note", "nextAction" -> {
                    require(reader.peek() == JsonToken.STRING)
                    strings[name] = reader.nextString()
                }
                "totalSteps", "elapsedSeconds", "createdAtEpochMs", "updatedAtEpochMs" ->
                    numbers[name] = readInteger(reader)
                "recallAccepted", "historyConsentAtSave" -> {
                    require(reader.peek() == JsonToken.BOOLEAN)
                    booleans[name] = reader.nextBoolean()
                }
                "completedStepIndexes" -> {
                    val indexes = mutableListOf<Int>()
                    reader.beginArray()
                    while (reader.hasNext()) indexes.add(exactInt(readInteger(reader)))
                    reader.endArray()
                    steps = indexes
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val record = ActivityRecord(
            id = strings["id"] ?: "",
            situationId = strings["situationId"] ?: "",
            activityTitle = strings["activityTitle"] ?: "",
            totalSteps = exactInt(numbers["totalSteps"] ?: 0),
            completedStepIndexes = steps,
            elapsedSeconds = numbers["elapsedSeconds"] ?: 0,
            outcome = strings["outcome"] ?: "",
            note = strings["note"] ?: "",
            nextAction = strings["nextAction"] ?: "",
            recallAccepted = booleans["recallAccepted"] ?: false,
            historyConsentAtSave = booleans["historyConsentAtSave"] ?: false,
            createdAtEpochMs = numbers["createdAtEpochMs"] ?: 0,
            updatedAtEpochMs = numbers["updatedAtEpochMs"] ?: 0
        )
        record.validate()
        return record
    }

    private fun readInteger(reader: JsonReader): Long {
        require(reader.peek() == JsonToken.NUMBER)
        val value = reader.nextString()
        require(value.matches(Regex("-?(0|[1-9][0-9]*)")))
        return value.toLong()
    }

    private fun exactInt(value: Long): Int {
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        return value.toInt()
    }
}
