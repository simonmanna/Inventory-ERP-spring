package com.poscafe.pos.ui.screens.crm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poscafe.pos.data.crm.CrmActivity
import com.poscafe.pos.data.crm.CrmCustomer360
import com.poscafe.pos.data.crm.CrmCustomerRow
import com.poscafe.pos.data.crm.CrmDuplicate
import com.poscafe.pos.data.crm.CrmException
import com.poscafe.pos.data.crm.CrmLoginResult
import com.poscafe.pos.data.crm.CrmRepository
import com.poscafe.pos.data.crm.LeadDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CrmTab(val label: String, val permission: String) {
    Activities("My activities", "crm:activity:read"),
    Lead("New lead", "crm:lead:write"),
    Customers("Customers", "crm:deal:read"),
}

/** State of the till's CRM screens (CRM enhancements T3c). Everything is fetched live; nothing is stored. */
@HiltViewModel
class CrmViewModel @Inject constructor(private val repo: CrmRepository) : ViewModel() {

    val session: StateFlow<CrmRepository.Session?> = repo.session

    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    // ---- sign-in
    var organizationCode by mutableStateOf(repo.organizationCode.orEmpty())
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var mfaCode by mutableStateOf("")
    var mfaToken by mutableStateOf<String?>(null)
        private set

    // ---- tabs
    var tab by mutableStateOf(CrmTab.Activities)

    // ---- activities
    var activityState by mutableStateOf("today")
    var activities by mutableStateOf<List<CrmActivity>>(emptyList())
        private set
    var counts by mutableStateOf(Triple(0, 0, 0)) // overdue, today, upcoming
        private set

    // ---- lead
    var draft by mutableStateOf(LeadDraft(name = ""))
        private set
    var duplicates by mutableStateOf<List<CrmDuplicate>>(emptyList())
        private set
    private var duplicateJob: Job? = null

    // ---- customers
    var search by mutableStateOf("")
    var customers by mutableStateOf<List<CrmCustomerRow>>(emptyList())
        private set
    var customer by mutableStateOf<CrmCustomer360?>(null)
        private set
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            if (email.isBlank()) email = repo.tillUserEmail().orEmpty()
        }
    }

    /** Re-checks that the CRM session still belongs to the person on the till; loads the first tab when it does. */
    fun refreshSession() {
        val s = repo.current() ?: return
        if (!s.permissions.contains(tab.permission)) {
            CrmTab.entries.firstOrNull { s.permissions.contains(it.permission) }?.let { tab = it }
        }
        openTab(tab)
    }

    fun tabs(): List<CrmTab> = CrmTab.entries.filter { repo.can(it.permission) }

    fun openTab(t: CrmTab) {
        tab = t
        error = null
        when (t) {
            CrmTab.Activities -> loadActivities()
            CrmTab.Customers -> if (customers.isEmpty()) loadCustomers()
            CrmTab.Lead -> Unit
        }
    }

    // ------------------------------------------------------------------ sign-in

    fun signIn(): Job = launchBusy {
        repo.signIn(organizationCode, email, password).onResult { r ->
            password = ""
            if (r is CrmLoginResult.NeedsMfa) mfaToken = r.mfaToken else refreshSession()
        }
    }

    fun verifyMfa(): Job = launchBusy {
        val token = mfaToken ?: return@launchBusy
        repo.verifyMfa(token, mfaCode).onResult {
            mfaToken = null
            mfaCode = ""
            refreshSession()
        }
    }

    fun cancelMfa() {
        mfaToken = null
        mfaCode = ""
        error = null
    }

    fun signOut() {
        repo.signOut()
        activities = emptyList()
        customers = emptyList()
        customer = null
        duplicates = emptyList()
        notice = null
        error = null
    }

    // ------------------------------------------------------------------ activities

    fun loadActivities(state: String = activityState): Job = launchBusy {
        activityState = state
        repo.planner(state).onResult {
            activities = it.activities
            counts = Triple(it.overdue, it.today, it.upcoming)
        }
    }

    fun markDone(a: CrmActivity, outcome: String): Job = launchBusy {
        repo.markDone(a.id, outcome).onResult {
            notice = "Done: ${a.title}"
            loadActivities()
        }
    }

    // ------------------------------------------------------------------ lead

    fun editDraft(d: LeadDraft) {
        val identifying = d.phone != draft.phone || d.email != draft.email || d.companyName != draft.companyName
        draft = d
        notice = null
        if (!identifying) return
        duplicateJob?.cancel()
        duplicateJob = viewModelScope.launch {
            delay(600)
            repo.duplicates(d).onSuccess { duplicates = it }
        }
    }

    fun saveLead(): Job = launchBusy {
        repo.createLead(draft).onResult { name ->
            notice = "Lead \"$name\" created and assigned."
            draft = LeadDraft(name = "")
            duplicates = emptyList()
        }
    }

    // ------------------------------------------------------------------ customers

    fun onSearch(text: String) {
        search = text
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(400)
            loadCustomers()
        }
    }

    fun loadCustomers(): Job = launchBusy {
        repo.customers(search).onResult { customers = it }
    }

    fun openCustomer(id: String): Job = launchBusy {
        repo.customer(id).onResult { customer = it }
    }

    fun closeCustomer() {
        customer = null
    }

    fun dismissNotice() {
        notice = null
    }

    // ------------------------------------------------------------------ helpers

    private fun launchBusy(block: suspend () -> Unit): Job = viewModelScope.launch {
        busy = true
        error = null
        try {
            block()
        } finally {
            busy = false
        }
    }

    /** On success runs [ok]; on failure shows the message (and, when the CRM session is gone, the sign-in form). */
    private inline fun <T> Result<T>.onResult(ok: (T) -> Unit) {
        onSuccess(ok)
        onFailure { e ->
            if ((e as? CrmException)?.signedOut == true || repo.current() == null) signOut()
            error = e.message ?: "Something went wrong."
        }
    }
}
