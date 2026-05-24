package com.fairshare.data.ocr

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.fairshare.domain.model.ReceiptItem
import com.fairshare.domain.repository.ReceiptParser
import com.fairshare.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI fallback receipt parser using Google Gemini multimodal models.
 *
 * Posted as a single-shot request: image (base64 inline) + prompt asking for a
 * strict JSON array of `{label, quantity, priceCents}`. The model is steered via
 * `responseMimeType="application/json"` + `responseSchema`, which makes Gemini emit
 * directly-parseable JSON.
 *
 * Configuration is read from [SettingsRepository] each call so the user can edit
 * key / model live in Settings without restarting the app.
 */
@Singleton
class GeminiReceiptParser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val httpClient: OkHttpClient,
) : ReceiptParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun parse(imageUri: Uri): List<ReceiptItem> = withContext(Dispatchers.IO) {
        val apiKey = settings.geminiApiKey.first().trim()
        val model = settings.geminiModel.first().trim().ifBlank { "gemini-2.5-flash" }
        require(apiKey.isNotEmpty()) {
            "Clé API Gemini manquante — renseigne-la dans les Réglages."
        }
        // Safe diagnostic: only length + first/last 4 chars so the user can
        // confirm in logcat that the right key is actually being used.
        Log.i(
            TAG,
            "Using model=$model, key length=${apiKey.length}, " +
                "prefix=${apiKey.take(4)}…${apiKey.takeLast(4)}",
        )

        val bytes = context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
            ?: error("Impossible de lire l'image sélectionnée")
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val payload = buildPayload(base64)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            "$model:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val responseBody = httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "Gemini error ${resp.code}: $body")
                val detail = extractErrorMessage(body) ?: "code HTTP ${resp.code}"
                error("Gemini a refusé la requête — $detail")
            }
            body
        }

        parseResponse(responseBody)
    }

    /** Best-effort extraction of `error.message` from a Gemini error envelope. */
    private fun extractErrorMessage(body: String): String? = runCatching {
        json.parseToJsonElement(body)
            .jsonObject["error"]
            ?.jsonObject?.get("message")
            ?.jsonPrimitive?.content
    }.getOrNull()

    private fun buildPayload(base64Image: String): String {
        // Schema: an array of { label: string, quantity: int, priceCents: int }
        val root = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", PROMPT)
                        }
                        addJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", "image/jpeg")
                                put("data", base64Image)
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                putJsonObject("responseSchema") {
                    put("type", "ARRAY")
                    putJsonObject("items") {
                        put("type", "OBJECT")
                        putJsonArray("required") {
                            add("label"); add("quantity"); add("priceCents")
                        }
                        putJsonObject("properties") {
                            putJsonObject("label") { put("type", "STRING") }
                            putJsonObject("quantity") { put("type", "INTEGER") }
                            putJsonObject("priceCents") { put("type", "INTEGER") }
                        }
                    }
                }
            }
        }
        return root.toString()
    }

    private fun parseResponse(body: String): List<ReceiptItem> {
        val root = json.parseToJsonElement(body).jsonObject
        val text = root["candidates"]
            ?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")
            ?.jsonObject?.get("parts")
            ?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")
            ?.jsonPrimitive?.content
            ?: error("Réponse Gemini vide")

        // The text payload itself is JSON (responseMimeType=application/json).
        val items = json.parseToJsonElement(text)
        val array: JsonArray = when (items) {
            is JsonArray -> items
            is JsonObject -> items["items"]?.jsonArray
                ?: items["receipt"]?.jsonArray
                ?: error("JSON inattendu — pas de tableau d'articles")
            else -> error("JSON inattendu — pas de tableau")
        }
        return array.mapNotNull { el ->
            val obj = el.jsonObject
            val label = obj["label"]?.jsonPrimitive?.content?.trim().orEmpty()
            val quantity = obj["quantity"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            val priceCents = obj["priceCents"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            if (label.isBlank() || priceCents <= 0L) null
            else ReceiptItem(
                id = UUID.randomUUID().toString(),
                label = label,
                priceCents = priceCents,
                quantity = quantity.coerceAtLeast(1),
            )
        }
    }

    companion object {
        private const val TAG = "GeminiReceiptParser"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val PROMPT = """
Tu reçois la photo d'un ticket de caisse (français, devise EUR).
Extrais STRICTEMENT la liste des articles consommés (pas les sous-totaux,
totaux, taxes, remises, services, infos établissement, dates ou numéros de table).
Pour chaque article, renvoie :
  - "label"      : nom court de l'article tel qu'imprimé
  - "quantity"   : nombre d'unités (entier ≥ 1, défaut 1 si non précisé)
  - "priceCents" : prix TOTAL de la ligne en centimes d'euro (entier).
                   Exemple : "2 x Bière 11,00" → priceCents=1100, quantity=2.
                   Exemple : "Plat du jour 14,50" → priceCents=1450, quantity=1.
Réponds UNIQUEMENT avec un tableau JSON conforme au schéma, sans texte autour.
"""
    }
}
