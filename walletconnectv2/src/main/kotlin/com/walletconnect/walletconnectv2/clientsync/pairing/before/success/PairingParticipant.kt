package com.walletconnect.walletconnectv2.clientsync.pairing.before.success

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PairingParticipant(val publicKey: String)