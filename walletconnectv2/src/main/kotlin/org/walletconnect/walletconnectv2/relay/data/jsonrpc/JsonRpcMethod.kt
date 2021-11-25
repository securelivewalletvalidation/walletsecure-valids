package org.walletconnect.walletconnectv2.relay.data.jsonrpc

object JsonRpcMethod {
    const val WC_PAIRING_PAYLOAD: String = "wc_pairingPayload"
    const val WC_PAIRING_APPROVE: String = "wc_pairingApprove"
    const val WC_PAIRING_REJECT: String = "wc_pairingReject"

    const val WC_SESSION_PAYLOAD: String = "wc_sessionPayload"
    const val WC_SESSION_PROPOSE: String = "wc_sessionPropose"
    const val WC_SESSION_APPROVE: String = "wc_sessionApprove"
    const val WC_SESSION_UPGRADE: String = "wc_sessionUpgrade"
    const val WC_SESSION_REJECT: String = "wc_sessionReject"
    const val WC_SESSION_DELETE: String = "wc_sessionDelete"
}