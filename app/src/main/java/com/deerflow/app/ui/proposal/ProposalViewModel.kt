package com.deerflow.app.ui.proposal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.deerflow.app.data.proposal.ProposalRepository
import com.deerflow.app.data.proposal.ProposalState
import kotlinx.coroutines.flow.StateFlow

class ProposalViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = ProposalRepository.get(app)

    val state: StateFlow<ProposalState> = repository.state

    fun refresh() = repository.refresh()
    fun select(proposalId: String) = repository.select(proposalId)
    fun clearSelection() = repository.clearSelection()
    fun approve(proposalId: String, note: String? = null) = repository.approve(proposalId, note)
    fun reject(proposalId: String, note: String? = null) = repository.reject(proposalId, note)
    fun clearMessage() = repository.clearMessage()
}

