/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.core.network.service

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.koin.core.annotation.Single
import org.meshtastic.core.model.BootloaderOtaQuirksResponse
import org.meshtastic.core.model.EventFirmwareResponse
import org.meshtastic.core.model.FirmwareReleaseManifest
import org.meshtastic.core.model.MaintenanceUf2Manifest
import org.meshtastic.core.model.MqttRootsResponse
import org.meshtastic.core.model.MeshViewMessage
import org.meshtastic.core.model.NetworkDeviceHardware
import org.meshtastic.core.model.NetworkDeviceLinksResponse
import org.meshtastic.core.model.NetworkFirmwareNightly
import org.meshtastic.core.model.NetworkFirmwareReleases
import org.meshtastic.core.network.HttpClientDefaults

/**
 * Pointer to the nightly preview build published by CI, served at the root of [HttpClientDefaults.NIGHTLY_BASE_URL]
 * rather than by api.meshtastic.org.
 *
 * Decoded manually rather than via content negotiation so that a 404 — nothing currently published — can be told apart
 * from a transport failure. The not-found body is the host's HTML error page, which content negotiation would reject
 * before the status could be inspected.
 */
private const val NIGHTLY_INDEX_URL = "${HttpClientDefaults.NIGHTLY_BASE_URL}/index.json"

private val firmwareJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * Decodes a GitHub release manifest independently of Ktor content negotiation. GitHub release assets commonly use
 * `application/octet-stream` even when their payload is JSON.
 */
internal fun decodeFirmwareReleaseManifest(body: String): FirmwareReleaseManifest = firmwareJson.decodeFromString(body)

/** Client for the Meshtastic public API (device hardware catalog and firmware releases). */
interface ApiService {
    /** Fetches the device hardware catalog from the Meshtastic API. */
    suspend fun getDeviceHardware(): List<NetworkDeviceHardware>

    /** Fetches the resolved device-links catalog (msh.to purchase links) from the Meshtastic API. */
    suspend fun getDeviceLinks(): NetworkDeviceLinksResponse

    /** Fetches the list of available firmware releases from the Meshtastic API. */
    suspend fun getFirmwareReleases(): NetworkFirmwareReleases

    /** Fetches the target manifest referenced by a firmware release's `zip_url`. */
    suspend fun getFirmwareReleaseManifest(manifestUrl: String): FirmwareReleaseManifest

    /**
     * Fetches the nightly preview build pointer from the nightly host. Returns null when no nightly is currently
     * published (HTTP 404); throws on transport or server errors so callers can distinguish "gone" from "unreachable".
     */
    suspend fun getNightlyFirmware(): NetworkFirmwareNightly?

    /** Fetches event-firmware display metadata (editions, welcome messages, links) from the Meshtastic API. */
    suspend fun getEventFirmware(): EventFirmwareResponse

    /**
     * Fetches the nRF52 bootloader/OTA quirk catalog (advisory bootloader-upgrade flags and the SoftDevice-variant
     * gating table) from the Meshtastic API. Decodes directly to the same model the bundled asset seed uses — the
     * server serves this file verbatim, so there is nothing to transform.
     */
    suspend fun getBootloaderOtaQuirks(): BootloaderOtaQuirksResponse

    /**
     * Fetches the pinned maintenance-UF2 manifest (factory-erase and OTAFIX bootloader self-update images) from the
     * Meshtastic API. Decodes directly to the same model the bundled asset seed uses — the server serves this file
     * verbatim, so there is nothing to transform. Each image's own `sha256` is still checked against the downloaded
     * bytes before any write; that is unaffected by how this manifest itself is fetched.
     */
    suspend fun getMaintenanceUf2Manifest(): MaintenanceUf2Manifest

    /**
     * Fetches the MQTT root directory (community roots, subdivision neighbors and geocoder segment names) the topic
     * finder places a user with. Decodes directly to the same model the bundled asset seed uses.
     */
    /** Fetches a missing text packet from MeshView using HTTP only. */
    suspend fun getMeshViewPacket(packetId: Int): MeshViewMessage?

    suspend fun getMqttRoots(): MqttRootsResponse
}

/**
 * Ktor-based [ApiService] implementation.
 *
 * Uses relative paths — the base URL is set via the `DefaultRequest` plugin in the platform Koin modules.
 *
 * Registered with `binds = []` to prevent Koin from auto-binding to [ApiService]; host modules (`app`, `desktop`)
 * provide their own explicit `ApiService` binding to allow platform-specific `HttpClient` engines.
 */
@Single(binds = [])
class ApiServiceImpl(private val client: HttpClient) : ApiService {
    override suspend fun getDeviceHardware(): List<NetworkDeviceHardware> = client.get("resource/deviceHardware").body()

    override suspend fun getDeviceLinks(): NetworkDeviceLinksResponse = client.get("resource/deviceLinks").body()

    override suspend fun getFirmwareReleases(): NetworkFirmwareReleases = client.get("github/firmware/list").body()

    override suspend fun getFirmwareReleaseManifest(manifestUrl: String): FirmwareReleaseManifest =
        decodeFirmwareReleaseManifest(client.get(manifestUrl).bodyAsText())

    override suspend fun getNightlyFirmware(): NetworkFirmwareNightly? {
        val response = client.get(NIGHTLY_INDEX_URL)
        return when {
            response.status == HttpStatusCode.NotFound -> null
            response.status.isSuccess() -> firmwareJson.decodeFromString<NetworkFirmwareNightly>(response.bodyAsText())
            else -> error("Unexpected HTTP ${response.status} fetching nightly firmware index")
        }
    }

    override suspend fun getEventFirmware(): EventFirmwareResponse = client.get("resource/eventFirmware").body()

    override suspend fun getBootloaderOtaQuirks(): BootloaderOtaQuirksResponse =
        client.get("resource/bootloaderOtaQuirks").body()

    override suspend fun getMaintenanceUf2Manifest(): MaintenanceUf2Manifest =
        client.get("resource/maintenanceUf2").body()

    override suspend fun getMqttRoots(): MqttRootsResponse = client.get("resource/mqttRoots").body()

    override suspend fun getMeshViewPacket(packetId: Int): MeshViewMessage? {
        val response = client.get("https://meshview.meshtastic.es/api/packets") { parameter("packet_id", packetId.toUInt().toString()) }
        if (response.status == HttpStatusCode.NotFound) return null
        check(response.status.isSuccess()) { "Unexpected HTTP ${response.status} fetching MeshView packet" }
        return decodeMeshViewMessage(response.bodyAsText(), packetId)
    }
}


/** Decodes the flexible response shapes used by MeshView's packet endpoint. */
internal fun decodeMeshViewMessage(body: String, requestedPacketId: Int): MeshViewMessage? {
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return null
    val candidates = when (root) {
        is JsonArray -> root.mapNotNull { it as? JsonObject }
        is JsonObject -> (root["packets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?: (root["results"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOf(root)
        else -> emptyList()
    }
    fun JsonObject.value(vararg keys: String): JsonElement? = keys.firstNotNullOfOrNull { this[it] }
    fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.contentOrNull
    fun JsonObject.longValue(vararg keys: String): Long? = (value(*keys) as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() }
    val packet = candidates.firstOrNull { it.longValue("packet_id", "packetId", "id")?.toInt() == requestedPacketId }
        ?: candidates.firstOrNull()?.takeIf { candidates.size == 1 } ?: return null
    val nested = sequenceOf("decoded", "data", "payload", "text_message").mapNotNull { packet[it] as? JsonObject }.toList()
    val textValue = (sequenceOf(packet) + nested.asSequence()).mapNotNull { it.value("text", "message", "message_text", "payload_text", "payload").stringValue() }.firstOrNull { it.isNotBlank() } ?: return null
    val sender = packet.value("from_name", "sender_name", "sender", "from", "from_node_id", "fromId").stringValue()
    val rawTime = packet.longValue("rx_time", "timestamp", "time", "received_at", "created_at", "import_time_us")
    val timestamp = rawTime?.let { when { it > 1_000_000_000_000_000L -> it / 1_000L; it > 100_000_000_000L -> it; else -> it * 1_000L } }
    val channel = packet.value("channel_name", "channel", "channel_index").stringValue()
    return MeshViewMessage(requestedPacketId, textValue, sender, timestamp, channel)
}
