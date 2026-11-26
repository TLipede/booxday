package com.boox.day.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.boox.day.R
import androidx.compose.ui.unit.dp
import com.boox.day.api.ai.AiProvider
import com.boox.day.api.ai.ChatGptFailure
import com.boox.day.api.ai.ChatGptModel
import com.boox.day.api.ai.ChatGptProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ChatGptSettingsCard(aiEnabled: Boolean) {
    val session = ChatGptProvider.session ?: return
    val state = session.state
    val active = state.active
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var action by remember { mutableStateOf<Job?>(null) }
    var signInPending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var catalogMessage by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<ChatGptModel>>(emptyList()) }
    var catalogOwner by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var showPlanWelcome by remember { mutableStateOf(false) }
    val notices = remember { context.getSharedPreferences("chatgpt_notices", Context.MODE_PRIVATE) }
    val busy = action != null

    fun runAction(signingIn: Boolean = false, block: suspend () -> Unit) {
        if (action != null) return
        message = null
        signInPending = signingIn
        val job = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ChatGptFailure) {
                message = failure.message
            } catch (_: Exception) {
                message = "ChatGPT could not complete the connection. Check your browser and internet connection."
            } finally {
                action = null
                signInPending = false
            }
        }
        if (!job.isCompleted) action = job
    }

    fun signIn(clientId: String? = null, consent: Boolean = false) = runAction(signingIn = true) {
        session.signIn(clientId, consent) { url ->
            withContext(Dispatchers.Main) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        refresh++
        if (session.state.active?.planEnabled == true && !notices.getBoolean("plan_explained", false)) {
            notices.edit().putBoolean("plan_explained", true).apply()
            showPlanWelcome = true
        }
    }

    LaunchedEffect(active?.clientId, active?.planEnabled, aiEnabled, refresh) {
        models = emptyList()
        catalogMessage = null
        catalogOwner = null
        if (!aiEnabled || active?.planEnabled != true) return@LaunchedEffect
        loading = true
        try {
            val found = session.discover(state) { AiProvider.allows(AiProvider.CHATGPT) }
            if (session.state.activeClientId == state.activeClientId) {
                models = found
                catalogOwner = state.activeClientId
                if (found.isEmpty()) catalogMessage = "This ChatGPT account returned no available models."
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ChatGptFailure) {
            catalogMessage = failure.message
        } catch (_: Exception) {
            catalogMessage = "Could not load ChatGPT models. Try refreshing the list."
        } finally {
            loading = false
        }
    }

    if (showPlanWelcome) {
        AlertDialog(
            onDismissRequest = { showPlanWelcome = false },
            title = { Text("You're using your ChatGPT plan") },
            text = { Text("Eligible BooxDay AI requests use your ChatGPT plan. Review limits and connected apps under Manage usage.") },
            confirmButton = { OutlinedButton(onClick = { showPlanWelcome = false }) { Text("Got it") } }
        )
    }

    SettingCard(title = "ChatGPT account", description = "Connect a ChatGPT plan for text and handwriting tools.") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.ready) Text("Loading ChatGPT connections…")
            if (state.storageError) {
                Text("Secure ChatGPT storage is unavailable.")
                OutlinedButton(onClick = { runAction { session.load() } }, enabled = !busy) { Text("Try again") }
            }
            val savedLabel = active?.label ?: "Choose a saved account"
            if (state.accounts.isNotEmpty()) {
                ChatGptPicker(savedLabel, state.accounts, { it.label }, !busy) { account ->
                    if (account.connected) runAction { session.selectAccount(account.clientId) }
                    else signIn(account.clientId)
                }
            }

            when {
                active?.planEnabled == true -> {
                    Text("Using ChatGPT plan")
                    OutlinedButton(onClick = { signIn(active.clientId) }, enabled = !busy) {
                        Text("Reconnect ChatGPT")
                    }
                }
                active?.connected == true -> {
                    Text("ChatGPT is connected. Approve plan usage to run AI tools.")
                    OutlinedButton(onClick = { signIn(active.clientId, consent = true) }, enabled = !busy) {
                        Text("Enable ChatGPT plan usage")
                    }
                }
                active != null -> {
                    Text("Sign in to reconnect this account.")
                    OutlinedButton(onClick = { signIn(active.clientId) }, enabled = !busy) { Text("Reconnect ChatGPT") }
                }
            }
            if (state.ready && !state.storageError) {
                Button(
                    onClick = { signIn() },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.Black, contentColor = Color.White)
                ) {
                    Icon(painterResource(R.drawable.chatgpt_mark), contentDescription = null, modifier = Modifier.size(21.dp))
                    Text("Continue with ChatGPT", modifier = Modifier.padding(start = 8.dp))
                }
                if (state.accounts.isNotEmpty()) Text("Connect a different ChatGPT account or workspace.")
            }
            if (active?.connected == true) {
                OutlinedButton(onClick = { runAction {
                    message = if (session.signOut(active.clientId)) "Signed out of ChatGPT on this device."
                    else "Signed out on this device. Disconnect BooxDay in ChatGPT settings to finish remote sign-out."
                } }, enabled = !busy) { Text("Sign out") }
            }
            if (signInPending) {
                Text("Finish signing in in your browser, then return to BooxDay.")
                OutlinedButton(onClick = { action?.cancel() }) { Text("Cancel sign-in") }
            } else if (busy) {
                Text("Updating ChatGPT connection…")
            }
            message?.let { Text(it) }
            if (active?.connected == true) {
                OutlinedButton(onClick = {
                    runAction {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage")))
                    }
                }, enabled = !busy) { Text("Manage usage") }
            }
        }
    }
    if (active?.planEnabled == true) {
        SettingCard(title = "ChatGPT model", description = "Choose a model that supports the input and output your AI tools need.") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val available = if (catalogOwner == active.clientId) models else emptyList()
                val selected = available.firstOrNull { it.slug == active.modelSlug }
                if (available.isNotEmpty()) {
                    ChatGptPicker(selected?.name ?: "Choose a model", available, { it.name }, !busy && aiEnabled) {
                        runAction { session.selectModel(state, it) }
                    }
                    if (active.modelSlug != null && selected == null) {
                        Text("The saved model is unavailable. Choose another model.")
                    }
                } else if (active.modelSlug != null) {
                    Text("Saved model: ${active.modelSlug}")
                }
                if (!aiEnabled) Text("Turn on AI Features to discover models.")
                if (loading) Text("Loading ChatGPT models…")
                catalogMessage?.let { Text(it) }
                OutlinedButton(onClick = { refresh++ }, enabled = aiEnabled && !loading && !busy) {
                    Text("Refresh models")
                }
                if (state.usageLimited) {
                    Text("ChatGPT paused requests after a usage limit. Check Manage usage before trying again.")
                    OutlinedButton(onClick = { runAction { session.resumeUsage(state) } }, enabled = aiEnabled && !busy) {
                        Text("Resume ChatGPT requests")
                    }
                }
            }
        }

        SettingCard(
            title = "Allow ChatGPT to index handwriting",
            description = "When AI Features and Read my handwriting are on, BooxDay sends images of memo and BOOX Notes pages to OpenAI in the background so you can search them. This uses your ChatGPT plan. BooxDay disables response storage. OpenAI’s data policies apply. Zero data retention is unavailable on this route."
        ) {
            EInkToggle(
                checked = active.allowHandwritingIndexing,
                onCheckedChange = { enabled -> runAction { session.setHandwritingIndexing(state, enabled) } },
                enabled = !busy
            )
        }
    }
}

@Composable
private fun <T> ChatGptPicker(
    label: String,
    items: List<T>,
    itemLabel: (T) -> String,
    enabled: Boolean,
    select: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled, onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
            items.forEach { item ->
                DropdownMenuItem(onClick = { expanded = false; select(item) }) { Text(itemLabel(item)) }
            }
        }
    }
}
