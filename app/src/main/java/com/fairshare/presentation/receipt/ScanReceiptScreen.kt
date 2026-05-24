package com.fairshare.presentation.receipt

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.fairshare.presentation.common.centsToString
import com.fairshare.presentation.common.parseAmountToCents
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScanReceiptScreen(
    onDone: () -> Unit,
    vm: ScanReceiptViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val participants by vm.participants.collectAsState()
    val hasGeminiKey by vm.hasGeminiKey.collectAsState()

    LaunchedEffect(participants) {
        if (state.payerId == null && participants.isNotEmpty()) vm.setPayer(participants.first().id)
    }

    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val pickFromGallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? -> uri?.let(vm::scan) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) pendingCameraUri?.let(vm::scan)
    }

    val cameraPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val uri = createTempImageUri(context)
            pendingCameraUri = uri
            takePicture.launch(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan de ticket") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        cameraPerm.launch(Manifest.permission.CAMERA)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("Photo")
                }
                OutlinedButton(
                    onClick = {
                        pickFromGallery.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Galerie")
                }
            }

            if (state.isScanning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "Analyse du ticket en cours…",
                    modifier = Modifier.padding(16.dp),
                )
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = state.title, onValueChange = vm::setTitle,
                        label = { Text("Titre") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text("Payé par", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        participants.forEach { p ->
                            FilterChip(
                                selected = state.payerId == p.id,
                                onClick = { vm.setPayer(p.id) },
                                label = { Text(p.name) },
                            )
                        }
                    }
                }
                if (state.items.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text("Articles détectés — assigne chacun à qui l'a consommé :", style = MaterialTheme.typography.titleSmall)
                    }
                }
                items(state.items, key = { it.id }) { item ->
                    ReceiptItemRow(
                        item = item,
                        participants = participants,
                        onToggle = { pid -> vm.toggleAssignment(item.id, pid) },
                        onChange = { label, cents -> vm.updateItem(item.id, label, cents) },
                        onDelete = { vm.deleteItem(item.id) },
                    )
                }
                item {
                    OutlinedButton(onClick = { vm.addItem() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Ajouter un article")
                    }
                }
                if (state.items.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Total : ${vm.totalCents().centsToString()}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    item {
                        PerPersonSummary(items = state.items, participants = participants)
                    }
                    item {
                        OutlinedButton(
                            onClick = { vm.reparseWithGemini() },
                            enabled = hasGeminiKey && !state.isScanning && state.lastImageUri != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.AutoAwesome, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (hasGeminiKey) "Réessayer avec IA"
                                else "Réessayer avec IA (clé API requise)"
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { vm.save(onDone) },
                enabled = !state.isSaving && state.items.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(if (state.isSaving) "…" else "Enregistrer la dépense")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReceiptItemRow(
    item: com.fairshare.domain.model.ReceiptItem,
    participants: List<com.fairshare.domain.model.Participant>,
    onToggle: (String) -> Unit,
    onChange: (String, Long) -> Unit,
    onDelete: () -> Unit,
) {
    var label by rememberSaveable(item.id) { mutableStateOf(item.label) }
    var price by rememberSaveable(item.id) { mutableStateOf(String.format("%.2f", item.priceCents / 100.0)) }

    OutlinedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.quantity > 1) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("${item.quantity}×") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                OutlinedTextField(
                    value = label, onValueChange = {
                        label = it
                        onChange(it, parseAmountToCents(price) ?: 0L)
                    },
                    label = { Text("Article") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = price, onValueChange = {
                        price = it
                        onChange(label, parseAmountToCents(it) ?: 0L)
                    },
                    label = { Text("€") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(110.dp),
                )
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                participants.forEach { p ->
                    FilterChip(
                        selected = p.id in item.assignedTo,
                        onClick = { onToggle(p.id) },
                        label = { Text(p.name) },
                    )
                }
            }
            if (item.assignedTo.isNotEmpty() && item.priceCents > 0) {
                val per = item.priceCents / item.assignedTo.size
                Text(
                    "→ ${per.centsToString()} / personne",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (item.assignedTo.isEmpty()) {
                Text(
                    "Non assigné → réparti équitablement entre tous",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun createTempImageUri(context: Context): Uri {
    val dir = File(context.cacheDir, "receipts").apply { mkdirs() }
    val file = File.createTempFile("receipt_", ".jpg", dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/**
 * Per-person breakdown shown at the bottom of a (scanned or edited) receipt,
 * right below the global total. Mirrors the split logic of
 * [com.fairshare.domain.usecase.AssignReceiptItemsUseCase]:
 *  - each item's price is split equally between its assignees
 *  - unassigned items are split equally between every participant
 *  - the cent remainder is distributed deterministically to the first assignees
 */
@Composable
internal fun PerPersonSummary(
    items: List<com.fairshare.domain.model.ReceiptItem>,
    participants: List<com.fairshare.domain.model.Participant>,
) {
    if (items.isEmpty() || participants.isEmpty()) return
    val totals = remember(items, participants) {
        val allIds = participants.map { it.id }
        val acc = LinkedHashMap<String, Long>().apply { allIds.forEach { put(it, 0L) } }
        items.forEach { item ->
            val assignees = item.assignedTo.toList().ifEmpty { allIds }
            if (assignees.isEmpty()) return@forEach
            val base = item.priceCents / assignees.size
            val remainder = (item.priceCents - base * assignees.size).toInt()
            assignees.forEachIndexed { i, pid ->
                acc[pid] = (acc[pid] ?: 0L) + base + if (i < remainder) 1 else 0
            }
        }
        acc
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Détail par personne",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            participants.forEach { p ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(p.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        (totals[p.id] ?: 0L).centsToString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
