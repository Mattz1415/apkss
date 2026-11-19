package com.example

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

sealed class GeminiResult {
    data class Success(val text: String) : GeminiResult()
    data class Error(val message: String) : GeminiResult()
}

sealed class ModelsResult {
    data class Success(val models: List<String>) : ModelsResult()
    data class Error(val message: String) : ModelsResult()
}

class GeminiClient {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * Mengambil daftar model Gemini yang tersedia dan mendukung generateContent
     * langsung dari REST API /v1beta/models menggunakan API Key terkonfigurasi.
     */
    suspend fun fetchAvailableModels(apiKey: String): ModelsResult = withContext(Dispatchers.IO) {
        val cleanApiKey = apiKey.trim()
        if (cleanApiKey.isEmpty()) {
            return@withContext ModelsResult.Error("GEMINI_API_KEY belum diatur di panel Secrets AI Studio.")
        }

        val encodedKey = URLEncoder.encode(cleanApiKey, StandardCharsets.UTF_8.name())
        val url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100&key=$encodedKey"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = parseApiErrorMessage(rawBody)
                    return@withContext ModelsResult.Error(
                        if (detail.isNotBlank()) "HTTP ${response.code}: $detail" else "HTTP ${response.code}"
                    )
                }

                val parsedModels = parseAvailableModels(rawBody)
                if (parsedModels.isEmpty()) {
                    ModelsResult.Error("Tidak ada model generateContent yang tersedia.")
                } else {
                    ModelsResult.Success(parsedModels)
                }
            }
        } catch (_: UnknownHostException) {
            ModelsResult.Error("Tidak ada koneksi internet.")
        } catch (_: SocketTimeoutException) {
            ModelsResult.Error("Koneksi timeout.")
        } catch (e: IOException) {
            ModelsResult.Error(e.localizedMessage ?: "Kesalahan jaringan.")
        } catch (e: Exception) {
            ModelsResult.Error(e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    suspend fun generateContent(
        apiKey: String,
        modelId: String,
        systemInstruction: String,
        userPrompt: String
    ): GeminiResult = withContext(Dispatchers.IO) {
        val cleanApiKey = apiKey.trim()
        val cleanModelId = modelId.trim()
        val cleanPrompt = userPrompt.trim()

        if (cleanApiKey.isEmpty()) {
            return@withContext GeminiResult.Error(
                "GEMINI_API_KEY belum dikonfigurasi.\n\nMasukkan API Key Anda di panel Secrets AI Studio lalu bangun ulang aplikasi."
            )
        }

        if (cleanModelId.isEmpty()) {
            return@withContext GeminiResult.Error(
                "Model belum dipilih.\n\nBuka Floating Gemini AI lalu pilih model dari dropdown."
            )
        }

        if (cleanPrompt.isEmpty()) {
            return@withContext GeminiResult.Error("Pertanyaan tidak boleh kosong.")
        }

        val normalizedModel = cleanModelId.removePrefix("models/")
        val encodedModel = URLEncoder.encode(normalizedModel, StandardCharsets.UTF_8.name())
        val encodedKey = URLEncoder.encode(cleanApiKey, StandardCharsets.UTF_8.name())
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$encodedModel:generateContent?key=$encodedKey"

        val payloadJson = buildRequestPayload(systemInstruction.trim(), cleanPrompt)
        val requestBody = payloadJson.toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    val apiErrorDetail = parseApiErrorMessage(rawBody)
                    return@withContext GeminiResult.Error(
                        formatHttpError(response.code, normalizedModel, apiErrorDetail)
                    )
                }

                parseSuccessResponse(rawBody)
            }
        } catch (_: UnknownHostException) {
            GeminiResult.Error(
                "Tidak ada koneksi internet.\n\nPeriksa Wi-Fi atau data seluler Anda lalu coba lagi."
            )
        } catch (_: SocketTimeoutException) {
            GeminiResult.Error(
                "Koneksi timeout saat menghubungi server Gemini.\n\nCoba kirim ulang beberapa saat lagi."
            )
        } catch (e: IOException) {
            GeminiResult.Error(
                "Gagal terhubung ke jaringan: ${e.localizedMessage ?: "Kesalahan I/O"}."
            )
        } catch (e: Exception) {
            GeminiResult.Error(
                "Terjadi kesalahan tak terduga: ${e.localizedMessage ?: e.javaClass.simpleName}."
            )
        }
    }

    private fun parseAvailableModels(rawBody: String): List<String> {
        if (rawBody.isBlank()) return emptyList()
        val json = JsonParser.parseString(rawBody).asJsonObject
        val modelsArray = json.getAsJsonArray("models") ?: return emptyList()

        val result = mutableListOf<String>()
        for (i in 0 until modelsArray.size()) {
            val item = modelsArray[i].asJsonObject
            val methods = item.getAsJsonArray("supportedGenerationMethods")
            var supportsGenerateContent = false
            if (methods != null) {
                for (m in 0 until methods.size()) {
                    if (methods[m].asString == "generateContent") {
                        supportsGenerateContent = true
                        break
                    }
                }
            }
            if (!supportsGenerateContent) continue

            val name = item.get("name")?.asString?.removePrefix("models/")?.trim().orEmpty()
            if (name.isNotEmpty() && !name.contains("embedding", ignoreCase = true)) {
                result.add(name)
            }
        }

        // Prioritaskan model Gemini terbaru agar mudah dipilih di bagian atas dropdown
        return result.distinct().sortedWith(
            compareBy<String>(
                { modelPriorityRank(it) },
                { it }
            )
        )
    }

    private fun modelPriorityRank(modelId: String): Int = when {
        modelId == "gemini-2.5-flash" -> 0
        modelId == "gemini-2.5-pro" -> 1
        modelId == "gemini-2.5-flash-lite" -> 2
        modelId.startsWith("gemini-2.5") -> 3
        modelId.startsWith("gemini-2.0") -> 4
        modelId.startsWith("gemini-") -> 5
        else -> 6
    }

    private fun buildRequestPayload(systemInstruction: String, userPrompt: String): String {
        val root = JsonObject()

        if (systemInstruction.isNotEmpty()) {
            val sysPart = JsonObject().apply {
                addProperty("text", systemInstruction)
            }
            val sysParts = JsonArray().apply { add(sysPart) }
            val sysObj = JsonObject().apply { add("parts", sysParts) }
            root.add("system_instruction", sysObj)
        }

        val userPart = JsonObject().apply {
            addProperty("text", userPrompt)
        }
        val userParts = JsonArray().apply { add(userPart) }
        val contentObj = JsonObject().apply {
            addProperty("role", "user")
            add("parts", userParts)
        }
        val contentsArray = JsonArray().apply { add(contentObj) }
        root.add("contents", contentsArray)

        return gson.toJson(root)
    }

    private fun parseSuccessResponse(rawBody: String): GeminiResult {
        if (rawBody.isBlank()) {
            return GeminiResult.Error("Server Gemini mengembalikan respons kosong.")
        }

        return try {
            val json = JsonParser.parseString(rawBody).asJsonObject
            val candidates = json.getAsJsonArray("candidates")

            if (candidates == null || candidates.size() == 0) {
                val promptFeedback = json.getAsJsonObject("promptFeedback")
                val blockReason = promptFeedback?.get("blockReason")?.asString
                return if (!blockReason.isNullOrBlank()) {
                    GeminiResult.Error("Permintaan diblokir oleh filter keamanan Gemini ($blockReason).")
                } else {
                    GeminiResult.Error("Gemini tidak mengembalikan kandidat jawaban.")
                }
            }

            val firstCandidate = candidates[0].asJsonObject
            val content = firstCandidate.getAsJsonObject("content")
            val parts = content?.getAsJsonArray("parts")

            val combined = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.size()) {
                    val partObj = parts[i].asJsonObject
                    if (partObj.has("text") && !partObj.get("text").isJsonNull) {
                        val piece = partObj.get("text").asString
                        if (piece.isNotBlank()) {
                            if (combined.isNotEmpty()) combined.append("\n")
                            combined.append(piece)
                        }
                    }
                }
            }

            val finalText = combined.toString().trim()
            if (finalText.isEmpty()) {
                val finishReason = firstCandidate.get("finishReason")?.asString
                GeminiResult.Error(
                    "Jawaban kosong dari model.${if (finishReason != null) " (Alasan: $finishReason)" else ""}"
                )
            } else {
                GeminiResult.Success(finalText)
            }
        } catch (e: Exception) {
            GeminiResult.Error("Gagal membaca format JSON dari Gemini: ${e.localizedMessage ?: "Parse error"}.")
        }
    }

    private fun parseApiErrorMessage(rawBody: String): String {
        if (rawBody.isBlank()) return ""
        return try {
            val json = JsonParser.parseString(rawBody).asJsonObject
            val errorObj = json.getAsJsonObject("error")
            errorObj?.get("message")?.asString?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun formatHttpError(code: Int, modelId: String, detail: String): String {
        val suffix = if (detail.isNotBlank()) "\n\nDetail server: $detail" else ""
        return when (code) {
            400 -> "Error 400 (Permintaan Tidak Valid).\nPeriksa kembali konfigurasi API Key atau model \"$modelId\".$suffix"
            401, 403 -> "Error $code (API Key Ditolak).\nGEMINI_API_KEY tidak valid atau tidak memiliki akses ke model \"$modelId\".$suffix"
            404 -> "Error 404 (Model Tidak Ditemukan).\nModel \"$modelId\" tidak tersedia di endpoint publik Gemini REST API. Pilih model lain pada dropdown.$suffix"
            429 -> "Error 429 (Kuota / Rate Limit Habis).\nBatas permintaan API tercapai. Tunggu sebentar atau ganti model lain.$suffix"
            in 500..599 -> "Error $code (Gangguan Server Gemini).\nServer Google Gemini sedang sibuk atau gangguan sementara. Coba lagi nanti.$suffix"
            else -> "Error HTTP $code saat memanggil model \"$modelId\".$suffix"
        }
    }
}
