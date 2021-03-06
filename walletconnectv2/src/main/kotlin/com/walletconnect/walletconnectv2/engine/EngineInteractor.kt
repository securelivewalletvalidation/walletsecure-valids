package com.walletconnect.walletconnectv2.engine

import android.app.Application
import com.walletconnect.walletconnectv2.clientsync.pairing.Pairing
import com.walletconnect.walletconnectv2.clientsync.pairing.SettledPairingSequence
import com.walletconnect.walletconnectv2.clientsync.pairing.after.PostSettlementPairing
import com.walletconnect.walletconnectv2.clientsync.pairing.before.proposal.PairingPermissions
import com.walletconnect.walletconnectv2.clientsync.pairing.before.success.PairingParticipant
import com.walletconnect.walletconnectv2.clientsync.pairing.before.success.PairingState
import com.walletconnect.walletconnectv2.clientsync.session.Controller
import com.walletconnect.walletconnectv2.clientsync.session.Session
import com.walletconnect.walletconnectv2.clientsync.session.SettledSessionPermissions
import com.walletconnect.walletconnectv2.clientsync.session.SettledSessionSequence
import com.walletconnect.walletconnectv2.clientsync.session.after.PostSettlementSession
import com.walletconnect.walletconnectv2.clientsync.session.after.params.Reason
import com.walletconnect.walletconnectv2.clientsync.session.before.PreSettlementSession
import com.walletconnect.walletconnectv2.clientsync.session.before.proposal.RelayProtocolOptions
import com.walletconnect.walletconnectv2.clientsync.session.before.success.SessionParticipant
import com.walletconnect.walletconnectv2.clientsync.session.common.SessionState
import com.walletconnect.walletconnectv2.common.*
import com.walletconnect.walletconnectv2.crypto.CryptoManager
import com.walletconnect.walletconnectv2.crypto.data.PublicKey
import com.walletconnect.walletconnectv2.crypto.data.SharedKey
import com.walletconnect.walletconnectv2.crypto.managers.BouncyCastleCryptoManager
import com.walletconnect.walletconnectv2.engine.model.EngineData
import com.walletconnect.walletconnectv2.engine.sequence.SequenceLifecycle
import com.walletconnect.walletconnectv2.jsonrpc.model.JsonRpcResponse
import com.walletconnect.walletconnectv2.relay.walletconnect.WalletConnectRelayer
import com.walletconnect.walletconnectv2.scope
import com.walletconnect.walletconnectv2.storage.SequenceStatus
import com.walletconnect.walletconnectv2.storage.StorageRepository
import com.walletconnect.walletconnectv2.util.Empty
import com.walletconnect.walletconnectv2.util.Logger
import com.walletconnect.walletconnectv2.util.generateId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.net.URI
import java.util.*

internal class EngineInteractor {
    //region provide with DI
    // TODO: add logic to check hostName for ws/wss scheme with and without ://
    private var relayer: WalletConnectRelayer = WalletConnectRelayer()
    private lateinit var storageRepository: StorageRepository
    private val crypto: CryptoManager = BouncyCastleCryptoManager()
    //endregion

    private var metaData: AppMetaData? = null
    private var controllerType = ControllerType.CONTROLLER
    private val _sequenceEvent: MutableStateFlow<SequenceLifecycle> = MutableStateFlow(SequenceLifecycle.Default)
    val sequenceEvent: StateFlow<SequenceLifecycle> = _sequenceEvent

    internal fun initialize(engine: EngineFactory) = with(engine) {
        this@EngineInteractor.metaData = engine.metaData
        this@EngineInteractor.controllerType = if (engine.isController) ControllerType.CONTROLLER else ControllerType.NON_CONTROLLER
        WalletConnectRelayer.RelayFactory(useTLs, hostName, projectId, application).run {
            relayer.initialize(this)
        }
        storageRepository = StorageRepository(null, engine.application)
        collectClientSyncJsonRpc()

        relayer.isConnectionOpened
            .filter { isConnected: Boolean -> isConnected }
            .onEach {
                coroutineScope {
                    launch(Dispatchers.IO) { resubscribeToSettledPairings() }
                    launch(Dispatchers.IO) { resubscribeToSettledSession() }
                }
            }
            .launchIn(scope)
    }

    internal fun pair(uri: String, onSuccess: (String) -> Unit, onFailure: (Throwable) -> Unit) {
        val pairingProposal: Pairing.Proposal = uri.toPairProposal()
        storageRepository.insertPairingProposal(pairingProposal.topic.value, uri, defaultSequenceExpirySeconds(), SequenceStatus.PENDING, controllerType)
        relayer.subscribe(pairingProposal.topic)
        val selfPublicKey: PublicKey = crypto.generateKeyPair()
        val expiry = Expiry((Calendar.getInstance().timeInMillis / 1000) + pairingProposal.ttl.seconds)
        val peerPublicKey = PublicKey(pairingProposal.pairingProposer.publicKey)
        val controllerPublicKey = if (pairingProposal.pairingProposer.controller) {
            peerPublicKey
        } else {
            selfPublicKey
        }
        val settledSequence = settlePairingSequence(pairingProposal.relay, selfPublicKey, peerPublicKey, controllerPublicKey, expiry)
        val preSettlementPairingApprove = pairingProposal.toApprove(generateId(), settledSequence.settledTopic, expiry, selfPublicKey)

        relayer.isConnectionOpened
            .filter { isOnline -> isOnline }
            .onEach {
                supervisorScope {
                    relayer.request(pairingProposal.topic, preSettlementPairingApprove) { result ->
                        result.fold(
                            onSuccess = {
                                relayer.unsubscribe(pairingProposal.topic)
                                relayer.subscribe(settledSequence.settledTopic)
                                storageRepository.updatePendingPairingToSettled(
                                    pairingProposal.topic.value,
                                    settledSequence.settledTopic.value,
                                    expiry.seconds,
                                    SequenceStatus.SETTLED
                                )
                                onSuccess(settledSequence.settledTopic.value)
                                pairingUpdate(settledSequence)
                            },
                            onFailure = { throwable -> onFailure(throwable) }
                        )
                    }
                    cancel()
                }
            }
            .launchIn(scope)
    }

    private fun pairingUpdate(settledSequence: SettledPairingSequence) {
        val pairingUpdate: PostSettlementPairing.PairingUpdate =
            PostSettlementPairing.PairingUpdate(id = generateId(), params = Pairing.UpdateParams(state = PairingState(metaData)))
        relayer.request(settledSequence.settledTopic, pairingUpdate) { result ->
            result.fold(
                onSuccess = {
                    /*TODO update Pairing's metadata in local storage
                    *  Might not need to store pairing metadata because metadata is a global variable*/
                },
                onFailure = { error -> Logger.error("Pairing update error: $error") }
            )
        }
    }

    internal fun approve(
        proposal: EngineData.SessionProposal,
        onSuccess: (EngineData.SettledSession) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val selfPublicKey: PublicKey = crypto.generateKeyPair()
        val peerPublicKey = PublicKey(proposal.proposerPublicKey)
        val sessionState = SessionState(proposal.accounts)
        val expiry = Expiry((Calendar.getInstance().timeInMillis / 1000) + proposal.ttl)
        val settledSession: SettledSessionSequence =
            settleSessionSequence(RelayProtocolOptions(), selfPublicKey, peerPublicKey, expiry, sessionState)
        relayer.subscribe(Topic(proposal.topic))
        val sessionApprove = PreSettlementSession.Approve(
            id = generateId(), params = Session.Success(
                relay = RelayProtocolOptions(), state = settledSession.state, expiry = expiry,
                responder = SessionParticipant(selfPublicKey.keyAsHex, metadata = metaData)
            )
        )

        relayer.request(Topic(proposal.topic), sessionApprove) { result ->
            result.fold(
                onSuccess = {
                    relayer.unsubscribe(Topic(proposal.topic))
                    relayer.subscribe(settledSession.topic)

                    with(proposal) {
                        storageRepository.updateStatusToSessionApproval(
                            topic,
                            sessionApprove.id,
                            settledSession.topic.value,
                            sessionApprove.params.state.accounts,
                            sessionApprove.params.expiry.seconds
                        )

                        val engineDataSettledSession = EngineData.SettledSession(
                            settledSession.topic.value,
                            accounts,
                            AppMetaData(name, description, url, icons.map { iconUri -> iconUri.toString() }),
                            EngineData.SettledSession.Permissions(
                                EngineData.SettledSession.Permissions.Blockchain(chains),
                                EngineData.SettledSession.Permissions.JsonRpc(methods),
                                EngineData.SettledSession.Permissions.Notifications(types)
                            )
                        )
                        onSuccess(engineDataSettledSession)
                    }
                },
                onFailure = { error -> onFailure(error) }
            )
        }
    }

    internal fun reject(reason: String, topic: String, onSuccess: (Pair<String, String>) -> Unit, onFailure: (Throwable) -> Unit) {
        val sessionReject = PreSettlementSession.Reject(id = generateId(), params = Session.Failure(reason = reason))
        onSuccess(Pair(topic, reason))
        storageRepository.deleteSession(topic)
        relayer.request(Topic(topic), sessionReject) { result ->
            result.fold(
                onSuccess = {}, //TODO: Should we unsubscribe from topic?
                onFailure = { error -> onFailure(error) }
            )
        }
    }

    internal fun disconnect(topic: String, reason: String, onSuccess: (Pair<String, String>) -> Unit, onFailure: (Throwable) -> Unit) {
        val sessionDelete = PostSettlementSession.SessionDelete(id = generateId(), params = Session.DeleteParams(Reason(message = reason)))
        storageRepository.deleteSession(topic)
        relayer.unsubscribe(Topic(topic))
        onSuccess(Pair(topic, reason))
        relayer.request(Topic(topic), sessionDelete) { result ->
            result.fold(
                onSuccess = {/*TODO: Should wait for acknowledgement and delete keys?*/ },
                onFailure = { error -> onFailure(error) }
            )
        }
    }

    internal fun respondSessionPayload(topic: String, jsonRpcResponse: JsonRpcResponse, onFailure: (Throwable) -> Unit) {
        relayer.respond(
            Topic(topic), jsonRpcResponse, { Logger.error("Session payload sent successfully") },
            { error ->
                onFailure(error)
                Logger.error("Sending session payload error: $error")
            })
    }

    internal fun update(
        topic: String, sessionState: EngineData.SessionState,
        onSuccess: (Pair<String, List<String>>) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val sessionUpdate: PostSettlementSession.SessionUpdate =
            PostSettlementSession.SessionUpdate(id = generateId(), params = Session.UpdateParams(SessionState(sessionState.accounts)))
        storageRepository.updateSessionWithAccounts(topic, sessionState.accounts)
        relayer.request(Topic(topic), sessionUpdate) { result ->
            result.fold(
                onSuccess = { onSuccess(Pair(topic, sessionState.accounts)) },
                onFailure = { error -> onFailure(error) }
            )
        }
    }

    internal fun upgrade(
        topic: String, permissions: EngineData.SessionPermissions,
        onSuccess: (Pair<String, EngineData.SessionPermissions>) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val sessionUpgrade = PostSettlementSession.SessionUpgrade(
            id = generateId(),
            params = Session.SessionPermissionsParams(permissions = permissions.toSessionsPermissions())
        )
        storageRepository.updateSessionWithPermissions(topic, permissions.blockchain?.chains, permissions.jsonRpc?.methods)
        relayer.request(Topic(topic), sessionUpgrade) { result ->
            result.fold(
                onSuccess = { onSuccess(Pair(topic, permissions)) },
                onFailure = { error -> onFailure(error) }
            )
        }
    }

    internal fun notify(
        topic: String, notification: EngineData.Notification,
        onSuccess: (String) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        /*TODO check whether under given topic there is a pairing or session stored and create proper Notification class*/
        //val pairingNotification = PostSettlementPairing.PairingNotification(id = generateId(), params = Pairing.NotificationParams(notification.type, notification.data))
        val sessionNotification =
            PostSettlementSession
                .SessionNotification(id = generateId(), params = Session.NotificationParams(notification.type, notification.data))
        relayer.request(Topic(topic), sessionNotification) { result ->
            result.fold(
                onSuccess = { onSuccess(topic) },
                onFailure = { error -> onFailure(error) }
            )
        }
    }

    internal fun ping(topic: String, onSuccess: (String) -> Unit, onFailure: (Throwable) -> Unit) {
        /*TODO check whether under given topic there is a pairing or session stored and create proper Ping class*/
        //val pairingParams = PostSettlementPairing.PairingPing(id = generateId(), params = Pairing.PingParams())
        val sessionPing = PostSettlementSession.SessionPing(id = generateId(), params = Session.PingParams())
        relayer.request(Topic(topic), sessionPing) { result ->
            result.fold(
                onSuccess = { onSuccess(topic) },
                onFailure = { error -> onFailure(error) }
            )
        }
    }

    internal fun getListOfPendingSessions(): List<EngineData.SessionProposal> {
        return storageRepository.getListOfSessionVOs().filter { session ->
            session.status == SequenceStatus.PENDING && session.expiry.isSequenceValid()
        }.map { session ->
            val (_, peerPublicKey) = crypto.getKeyAgreement(session.topic)

            EngineData.SessionProposal(
                name = session.appMetaData?.name ?: String.Empty,
                description = session.appMetaData?.description ?: String.Empty,
                url = session.appMetaData?.url ?: String.Empty,
                icons = session.appMetaData?.icons?.map { URI(it) } ?: emptyList(),
                chains = session.chains,
                methods = session.methods,
                types = session.types,
                topic = session.topic.value,
                proposerPublicKey = peerPublicKey.keyAsHex,
                ttl = session.ttl.seconds,
                accounts = session.accounts
            )
        }
    }

    internal fun getListOfSettledSessions(): List<EngineData.SettledSession> = storageRepository.getListOfSessionVOs().filter { session ->
        session.status == SequenceStatus.SETTLED && session.expiry.isSequenceValid()
    }.map { session ->
        val metadata: AppMetaData? = session.appMetaData?.let { appMetaData ->
            AppMetaData(appMetaData.name, appMetaData.description, appMetaData.url, appMetaData.icons)
        }

        EngineData.SettledSession(
            session.topic.value,
            session.accounts,
            metadata,
            EngineData.SettledSession.Permissions(
                EngineData.SettledSession.Permissions.Blockchain(session.chains),
                EngineData.SettledSession.Permissions.JsonRpc(session.methods),
                EngineData.SettledSession.Permissions.Notifications(session.types)
            )
        )
    }

    private fun collectClientSyncJsonRpc() = scope.launch {
        relayer.clientSyncJsonRpc.collect { payload ->
            when (payload.params) {
                is Pairing.PayloadParams -> onPairingPayload(payload.params)
                is Session.DeleteParams -> onSessionDelete(payload.params, payload.topic)
                is Session.SessionPayloadParams -> onSessionPayload(payload.params, payload.topic, payload.requestId)
                is Pairing.DeleteParams -> onPairingDelete(payload.params, payload.topic)
                is Session.NotificationParams -> onSessionNotification(payload.params, payload.topic)
                is Pairing.PingParams, is Session.PingParams -> onPing(payload.topic, payload.requestId)
            }
        }
    }

    private fun onPing(topic: Topic, requestId: Long) {
        val jsonRpcResult = JsonRpcResponse.JsonRpcResult(id = requestId, result = "true")
        relayer.respond(topic, jsonRpcResult,
            { Logger.log("Ping send successfully") },
            { error -> Logger.error("Ping Error: $error") })
    }

    private fun onPairingPayload(payload: Pairing.PayloadParams) {
        val proposal = payload.request.params
        storageRepository.insertSessionProposal(proposal, proposal.proposer.metadata, defaultSequenceExpirySeconds(), controllerType)
        val (sharedKey, publicKey) = crypto.getKeyAgreement(proposal.signal.params.topic)
        crypto.setEncryptionKeys(sharedKey as SharedKey, publicKey as PublicKey, proposal.topic)
        _sequenceEvent.value = SequenceLifecycle.OnSessionProposal(proposal.toSessionProposal())
    }

    private fun onSessionPayload(payload: Session.SessionPayloadParams, topic: Topic, requestId: Long) {
        //TODO Validate session request + add unmarshaling of generic session request payload to the usable generic object
        val params = payload.request.params.toString()
        val chainId = payload.chainId
        val method = payload.request.method
        _sequenceEvent.value = SequenceLifecycle.OnSessionRequest(
            EngineData.SessionRequest(topic.value, chainId, EngineData.SessionRequest.JSONRPCRequest(requestId, method, params))
        )
    }

    private fun onSessionDelete(params: Session.DeleteParams, topic: Topic) {
        crypto.removeKeys(topic.value)
        storageRepository.deleteSession(topic.value)
        relayer.unsubscribe(topic)
        _sequenceEvent.value = SequenceLifecycle.OnSessionDeleted(EngineData.DeletedSession(topic.value, params.reason.message))
    }

    private fun onSessionNotification(params: Session.NotificationParams, topic: Topic) {
        val type = params.type
        val data = params.data.toString()
        _sequenceEvent.value = SequenceLifecycle.OnSessionNotification(EngineData.SessionNotification(topic.value, type, data))
    }

    private fun onPairingDelete(params: Pairing.DeleteParams, topic: Topic) {
        crypto.removeKeys(topic.value)
        relayer.unsubscribe(topic)
        //TODO delete from DB
    }

    private fun settlePairingSequence(
        relay: JSONObject,
        selfPublicKey: PublicKey,
        peerPublicKey: PublicKey,
        controllerPublicKey: PublicKey,
        expiry: Expiry
    ): SettledPairingSequence {
        val (_, settledTopic) = crypto.generateTopicAndSharedKey(selfPublicKey, peerPublicKey)
        return SettledPairingSequence(
            settledTopic,
            relay,
            selfPublicKey,
            peerPublicKey,
            PairingPermissions(PairingParticipant(controllerPublicKey.keyAsHex)),
            expiry
        )
    }

    private fun resubscribeToSettledPairings() {
        val (listOfExpiredPairing, listOfValidPairing) = storageRepository.getListOfPairingVOs().partition { pairing -> !pairing.expiry.isSequenceValid() }

        listOfExpiredPairing
            .map { pairing -> pairing.topic }
            .onEach { pairingTopic ->
                relayer.unsubscribe(pairingTopic)
                storageRepository.deletePairing(pairingTopic.value)
            }

        listOfValidPairing
            .filter { pairing -> pairing.status == SequenceStatus.SETTLED }
            .map { pairing -> pairing.topic }
            .onEach { pairingTopic ->
                relayer.subscribe(pairingTopic)
            }
    }

    private fun resubscribeToSettledSession() {
        val (listOfExpiredSession, listOfValidSessions) = storageRepository.getListOfSessionVOs().partition { session -> !session.expiry.isSequenceValid() }

        listOfExpiredSession
            .map { session -> session.topic }
            .onEach { sessionTopic ->
                relayer.unsubscribe(sessionTopic)
                storageRepository.deleteSession(sessionTopic.value)
            }

        listOfValidSessions
            .filter { session -> session.status == SequenceStatus.SETTLED }
            .onEach { session ->
                relayer.subscribe(session.topic)
            }
    }

    private fun Expiry.isSequenceValid(): Boolean {
        return seconds > (System.currentTimeMillis() / 1000)
    }

    private fun defaultSequenceExpirySeconds() = ((System.currentTimeMillis() / 1000) + 86400)

    private fun settleSessionSequence(
        relay: RelayProtocolOptions,
        selfPublicKey: PublicKey,
        peerPublicKey: PublicKey,
        expiry: Expiry,
        sessionState: SessionState
    ): SettledSessionSequence {
        val (sharedKey, topic) = crypto.generateTopicAndSharedKey(selfPublicKey, peerPublicKey)
        return SettledSessionSequence(
            topic,
            relay,
            selfPublicKey,
            peerPublicKey,
            SettledSessionPermissions(Controller(selfPublicKey.keyAsHex)),
            sharedKey,
            expiry,
            sessionState
        )
    }

    class EngineFactory(
        val useTLs: Boolean = false,
        val hostName: String,
        val projectId: String,
        val isController: Boolean,
        val application: Application,
        val metaData: AppMetaData
    )
}