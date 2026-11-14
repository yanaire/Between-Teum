package dev.pleos.starter.teum.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.pleos.starter.teum.vehicle.BackrestSnapshot

internal fun BackrestSnapshot.toWebState(): JsonObject = JsonObject().apply {
    addProperty("busy", busy)
    addProperty("areaId", areaId)
    addProperty("requestAck", requestAck.name)
    addProperty("stopAck", stopAck.name)
    addProperty("rawPositionDelta", rawPositionDelta)
    addProperty("blockedReason", blockedReason)
    addProperty("closed", closed)
    add("areas", JsonArray().apply {
        areas.forEach { area ->
            add(JsonObject().apply {
                addProperty("id", area.id)
                addProperty("minRaw", area.minRaw)
                addProperty("maxRaw", area.maxRaw)
                addProperty("writable", area.writable)
                addProperty("raw", positions[area.id]?.raw)
                addProperty("observedAtMillis", positions[area.id]?.observedAtMillis)
            })
        }
    })
}
