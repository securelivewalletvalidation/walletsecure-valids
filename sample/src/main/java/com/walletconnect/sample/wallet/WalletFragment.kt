package com.walletconnect.sample.wallet

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.walletconnect.sample.R
import com.walletconnect.sample.databinding.WalletFragmentBinding
import com.walletconnect.sample.wallet.ui.*
import com.walletconnect.sample.wallet.ui.dialog.SessionDetailsDialog
import com.walletconnect.sample.wallet.ui.dialog.SessionProposalDialog
import com.walletconnect.sample.wallet.ui.dialog.SessionRequestDialog
import com.walletconnect.sample.wallet.ui.dialog.UrlDialog
import com.walletconnect.walletconnectv2.client.WalletConnectClientData

class WalletFragment : Fragment(R.layout.wallet_fragment) {
    private val viewModel: WalletViewModel by activityViewModels()
    private lateinit var binding: WalletFragmentBinding
    private var proposalDialog: SessionProposalDialog? = null
    private val sessionAdapter by lazy { SessionsAdapter(viewModel::disconnect, viewModel::sessionUpdate, viewModel::sessionUpgrade, viewModel::sessionPing, this::onSessionsDetails) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = WalletFragmentBinding.bind(view)
        setupToolbar()

        binding.sessions.adapter = sessionAdapter

        viewModel.eventFlow.observe(viewLifecycleOwner) { event ->
            when (event) {
                is InitSessionsList -> sessionAdapter.submitList(event.sessions)
                is ShowSessionProposalDialog -> {
                    proposalDialog = SessionProposalDialog(
                        requireContext(),
                        viewModel::approve,
                        viewModel::reject,
                        event.proposal
                    )
                    proposalDialog?.show()
                }
                is ShowSessionRequestDialog -> {
                    val requestDialog = SessionRequestDialog(
                        requireContext(),
                        viewModel::respondRequest,
                        viewModel::rejectRequest,
                        event.sessionRequest,
                        event.session
                    )
                    requestDialog.show()
                }
                is UpdateActiveSessions -> {
                    proposalDialog?.dismiss()
                    sessionAdapter.submitList(event.sessions)
                    event.message?.let {
                        Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                    }
                }
                is RejectSession -> proposalDialog?.dismiss()
                is PingSuccess -> Toast.makeText(requireContext(), "Successful session ping", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupToolbar() {
        binding.walletToolbar.title = getString(R.string.app_name)
        binding.walletToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.qrCodeScanner -> {
                    findNavController().navigate(R.id.action_walletFragment_to_scannerFragment)
                    true
                }
                R.id.pasteUri -> {
                    UrlDialog(requireContext(), pair = viewModel::pair).show()
                    true
                }
                else -> false
            }
        }
    }

    private fun onSessionsDetails(session: WalletConnectClientData.SettledSession) {
        SessionDetailsDialog(requireContext(), session).show()
    }
}