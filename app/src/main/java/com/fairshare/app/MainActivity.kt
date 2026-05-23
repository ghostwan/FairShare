package com.fairshare.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.fairshare.app.data.LocalTripRepository
import com.fairshare.app.domain.CalculateBalancesUseCase
import com.fairshare.app.domain.CalculateSettlementsUseCase
import com.fairshare.app.domain.ParseReceiptTextUseCase
import com.fairshare.app.presentation.FairShareApp
import com.fairshare.app.presentation.FairShareViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

class MainActivity : ComponentActivity() {
    private var currentReceiptImageUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val repository = LocalTripRepository(applicationContext)
        val viewModel = FairShareViewModel(
            repository = repository,
            calculateBalances = CalculateBalancesUseCase(),
            calculateSettlements = CalculateSettlementsUseCase(),
            parseReceiptText = ParseReceiptTextUseCase()
        )

        setContent {
            val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val uri = currentReceiptImageUri
                if (result.resultCode == RESULT_OK && uri != null) {
                    recognizeReceipt(uri, viewModel)
                }
            }

            FairShareApp(
                viewModel = viewModel,
                onScanReceipt = {
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    val uri = createReceiptImageUri()
                    currentReceiptImageUri = uri
                    intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    cameraLauncher.launch(intent)
                }
            )
        }
    }

    private fun createReceiptImageUri(): Uri {
        val directory = File(cacheDir, "receipt_images").apply { mkdirs() }
        val image = File.createTempFile("receipt_", ".jpg", directory)
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", image)
    }

    private fun recognizeReceipt(uri: Uri, viewModel: FairShareViewModel) {
        val trip = viewModel.uiState.trip ?: return
        val payerId = trip.participants.firstOrNull()?.id
        if (payerId == null) {
            viewModel.showScanError("Ajoute au moins un participant avant de scanner un ticket.")
            return
        }

        val image = InputImage.fromFilePath(this, uri)
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(image)
            .addOnSuccessListener { text -> viewModel.importScannedReceipt(text.text, payerId) }
            .addOnFailureListener { error -> viewModel.showScanError(error.localizedMessage ?: "Lecture OCR impossible.") }
    }
}
