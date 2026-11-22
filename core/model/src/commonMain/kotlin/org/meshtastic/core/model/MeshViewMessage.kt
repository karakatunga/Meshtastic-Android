/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.meshtastic.core.model

import kotlinx.datetime.Instant

/** A text packet recovered from MeshView; external history, never a locally received radio packet. */
data class MeshViewMessage(
    val packetId: Int,
    val text: String,
    val sender: String?,
    /** Unix epoch milliseconds, or null when MeshView did not provide a usable timestamp. */
    val timestamp: Long?,
    /** Channel name or numeric channel index as returned by MeshView. */
    val channel: String?,
) {
    val formattedTime: String?
        get() = timestamp?.let(Instant::fromEpochMilliseconds)?.toString()
}
