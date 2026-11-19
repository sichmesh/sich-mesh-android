// The SICH phone link (sich-s3 docs/phone-link.md): the GATT service and the message types.
package org.sichmesh.app.link

import java.util.UUID

object Proto {
    val SERVICE: UUID = UUID.fromString("925dff37-0a2f-4bd5-aea5-3212459a14ee")
    val TO_DEVICE: UUID = UUID.fromString("925d0001-0a2f-4bd5-aea5-3212459a14ee")
    val FROM_DEVICE: UUID = UUID.fromString("925d0002-0a2f-4bd5-aea5-3212459a14ee")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")   // notifications on/off

    const val HELLO = 0x01
    const val WELCOME = 0x02
    const val API = 0x10
    const val API_ANSWER = 0x11
    const val EVENT = 0x20
    const val NET = 0x30
    const val NET_ANSWER = 0x31

    const val ASK_MTU = 517          // the device takes up to 256
}
