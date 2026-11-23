package com.poscafe.pos.ui.screens.crm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poscafe.pos.data.crm.CrmActivity
import com.poscafe.pos.data.crm.CrmCustomer360
import com.poscafe.pos.data.crm.CrmParsers
import com.poscafe.pos.ui.components.EmptyState
import com.poscafe.pos.ui.components.KVRow
import com.poscafe.pos.ui.components.PosCard
import com.poscafe.pos.ui.components.PrimaryButton
import com.poscafe.pos.ui.components.StatCard
import com.poscafe.pos.ui.screens.manage.ManageScaffold

/**
 * CRM on the till (CRM enhancements T3c): "My activities", quick lead capture and customer 360. Online only — the
 * salesperson signs in with their own ERP account; the session is kept in memory and ends when the till locks.
 */
@Composable
fun CrmScreen(onBack: () -> Unit, vm: CrmViewModel = hiltViewModel()) {
    val session by vm.session.collectAsStateWithLifecycle()
    LaunchedEffect(session?.userId) { vm.refreshSession() }
    BackHandler(enabled = vm.customer != null) { vm.closeCustomer() }

    ManageScaffold(
        title = if (vm.customer != null) vm.customer!!.name else "CRM",
        onBack = { if (vm.customer != null) vm.closeCustomer() else onBack() },
        header = {
            if (session != null && vm.customer == null) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    vm.tabs().forEach { t ->
                        FilterChip(selected = vm.tab == t, onClick = { vm.openTab(t) }, label = { Text(t.label) })
                    }
                    Spacer(Modifier.width(8.dp))
                    // Borrowed from the app's account sign-in: signing out happens on the dashboard.
                    if (session?.viaAccount != true) TextButton(onClick = vm::signOut) { Text("Sign out of CRM") }
                }
            }
            if (vm.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            vm.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            vm.notice?.let {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    onClick = vm::dismissNotice,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) { Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        },
    ) { _ ->
        val customer = vm.customer
        when {
            session == null -> SignInForm(vm)
            customer != null -> CustomerDetail(customer)
            vm.tabs().isEmpty() -> EmptyState(
                Icons.Outlined.Groups, "No CRM access",
                "Your account has no CRM permissions. Ask an administrator.", Modifier.fillMaxSize(),
            )
            vm.tab == CrmTab.Activities -> ActivitiesTab(vm)
            vm.tab == CrmTab.Lead -> LeadTab(vm)
            else -> CustomersTab(vm)
        }
    }
}

// ------------------------------------------------------------------ sign-in

@Composable
private fun SignInForm(vm: CrmViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 480.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "CRM needs a connection and your own ERP account — the one you use for your PIN on this till. " +
                "You stay signed in until the till locks.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (vm.mfaToken == null) {
            OutlinedTextField(
                value = vm.organizationCode, onValueChange = { vm.organizationCode = it },
                label = { Text("Organization code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.email, onValueChange = { vm.email = it },
                label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.password, onValueChange = { vm.password = it },
                label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(
                "Sign in", { vm.signIn() }, Modifier.fillMaxWidth(),
                enabled = !vm.busy && vm.organizationCode.isNotBlank() && vm.email.isNotBlank() && vm.password.isNotEmpty(),
            )
        } else {
            Text("Enter the 6-digit code from your authenticator app.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = vm.mfaCode, onValueChange = { vm.mfaCode = it.filter(Char::isDigit).take(8) },
                label = { Text("Code") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton("Verify", { vm.verifyMfa() }, Modifier.fillMaxWidth(), enabled = !vm.busy && vm.mfaCode.length >= 6)
            TextButton(onClick = vm::cancelMfa) { Text("Back") }
        }
    }
}

// ------------------------------------------------------------------ activities

private fun typeIcon(type: String): ImageVector = when (type) {
    "call" -> Icons.Outlined.Call
    "email" -> Icons.Outlined.Email
    "meeting" -> Icons.Outlined.Groups
    else -> Icons.Outlined.TaskAlt
}

@Composable
private fun ActivitiesTab(vm: CrmViewModel) {
    var completing by remember { mutableStateOf<CrmActivity?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val (overdue, today, upcoming) = vm.counts
            listOf("overdue" to "Overdue ($overdue)", "today" to "Today ($today)", "upcoming" to "Upcoming ($upcoming)")
                .forEach { (state, label) ->
                    FilterChip(selected = vm.activityState == state, onClick = { vm.loadActivities(state) }, label = { Text(label) })
                }
        }
        if (vm.activities.isEmpty() && !vm.busy) {
            EmptyState(Icons.Outlined.Event, "Nothing here", "Activities assigned to you appear here.", Modifier.fillMaxSize())
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            ) {
                items(vm.activities, key = { it.id }) { a ->
                    PosCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(typeIcon(a.type), null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val about = listOfNotNull(a.dealName, a.partnerName).distinct().joinToString(" · ")
                                if (about.isNotBlank()) {
                                    Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    CrmParsers.shortDate(a.dueAt).ifBlank { "No due date" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (a.state == "overdue") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (a.partnerId != null && vm.tabs().contains(CrmTab.Customers)) {
                                TextButton(onClick = { vm.openCustomer(a.partnerId) }) { Text("Customer") }
                            }
                            FilledTonalButton(onClick = { completing = a }, enabled = !vm.busy) { Text("Done") }
                        }
                    }
                }
            }
        }
    }
    completing?.let { a ->
        var outcome by remember(a.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { completing = null },
            title = { Text("Mark as done") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(a.title, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(value = outcome, onValueChange = { outcome = it }, label = { Text("Outcome (optional)") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { Button(onClick = { vm.markDone(a, outcome); completing = null }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { completing = null }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ new lead

@Composable
private fun LeadTab(vm: CrmViewModel) {
    val d = vm.draft
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(value = d.name, onValueChange = { vm.editDraft(d.copy(name = it)) }, label = { Text("Lead name *") },
            placeholder = { Text("e.g. Office coffee for Acme") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = d.contactName, onValueChange = { vm.editDraft(d.copy(contactName = it)) }, label = { Text("Contact person") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = d.companyName, onValueChange = { vm.editDraft(d.copy(companyName = it)) }, label = { Text("Company") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = d.phone, onValueChange = { vm.editDraft(d.copy(phone = it)) }, label = { Text("Phone") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = d.email, onValueChange = { vm.editDraft(d.copy(email = it)) }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = d.notes, onValueChange = { vm.editDraft(d.copy(notes = it)) }, label = { Text("Notes") },
            minLines = 3, modifier = Modifier.fillMaxWidth())
        if (vm.duplicates.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text("Possible duplicates", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    vm.duplicates.take(6).forEach {
                        Text(
                            it.label + if (it.matchedOn.isEmpty()) "" else "  (same ${it.matchedOn.joinToString(", ")})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    Text("You can still save; check the back office first if it is the same person.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
        PrimaryButton("Save lead", { vm.saveLead() }, Modifier.fillMaxWidth(), enabled = !vm.busy && d.name.isNotBlank())
    }
}

// ------------------------------------------------------------------ customers

@Composable
private fun CustomersTab(vm: CrmViewModel) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.search, onValueChange = vm::onSearch,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            placeholder = { Text("Name, code, phone or email") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (vm.customers.isEmpty() && !vm.busy) {
            EmptyState(Icons.Outlined.Groups, "No customers found", null, Modifier.fillMaxSize())
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            ) {
                items(vm.customers, key = { it.id }) { c ->
                    PosCard(Modifier.fillMaxWidth(), onClick = { vm.openCustomer(c.id) }) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(c.code, c.phone, c.email).joinToString(" · ").ifBlank { "—" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            val facts = buildList {
                                if (c.openOpportunities > 0) add("${c.openOpportunities} open · ${CrmParsers.money(c.openValue)}")
                                if (c.overdueReceivables.signum() > 0) add("${CrmParsers.money(c.overdueReceivables)} overdue")
                            }
                            if (facts.isNotEmpty()) {
                                Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall,
                                    color = if (c.overdueReceivables.signum() > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomerDetail(c: CrmCustomer360) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(listOfNotNull(c.code, c.phone, c.email).joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val open = c.deals.filter { it.status == "open" }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("${open.size}", "Open deals", Modifier.weight(1f))
            StatCard(CrmParsers.money(c.openReceivables), "Receivables", Modifier.weight(1f))
            StatCard(
                CrmParsers.money(c.overdueReceivables), "Overdue", Modifier.weight(1f),
                accent = if (c.overdueReceivables.signum() > 0) MaterialTheme.colorScheme.error else null,
            )
        }
        if (c.creditHold || c.creditLimit.signum() > 0) {
            PosCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    KVRow("Credit limit", if (c.creditLimit.signum() > 0) CrmParsers.money(c.creditLimit) else "None")
                    if (c.creditHold) KVRow("Status", "On credit hold", valueColor = MaterialTheme.colorScheme.error)
                }
            }
        }
        Section("Contacts", c.contacts.isEmpty()) {
            c.contacts.forEach {
                Text(it.name + (if (it.isPrimary) "  (primary)" else ""), style = MaterialTheme.typography.bodyMedium)
                Text(listOfNotNull(it.position, it.phone, it.email).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Section("Leads and opportunities", c.deals.isEmpty()) {
            c.deals.take(15).forEach {
                KVRow(
                    "${it.name}  ·  ${if (it.status == "open") it.stageName ?: (if (it.kind == "lead") "Lead" else "Open") else it.status}",
                    CrmParsers.money(it.amount, it.currencyCode),
                )
            }
        }
        Section("Invoices", c.invoices.isEmpty()) {
            c.invoices.take(10).forEach {
                KVRow(
                    "${it.number}  ·  ${it.issueDate?.take(10).orEmpty()}",
                    if (it.residual.signum() > 0) "${CrmParsers.money(it.residual)} due" else CrmParsers.money(it.total),
                    valueColor = if (it.residual.signum() > 0) MaterialTheme.colorScheme.error else null,
                )
            }
        }
        Section("Recent activity", c.history.isEmpty()) {
            c.history.take(15).forEach {
                Text(it.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${it.type} · ${it.status} · ${CrmParsers.shortDate(it.at)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Section(title: String, empty: Boolean, content: @Composable ColumnScope.() -> Unit) {
    PosCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (empty) {
                Text("None", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                content()
            }
        }
    }
}
