package com.subtrans.app.engine

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.io.Closeable

/**
 * The offline translation engine.
 *
 * This is the part that makes a whole series affordable. ML Kit's models run
 * on the device: once the language pair is downloaded — roughly thirty
 * megabytes, one time — translating ninety thousand lines costs nothing, needs
 * no network, and has no daily limit to run out of.
 *
 * Quality is below a large language model's, which is exactly why the rest of
 * this package exists: [TermPrep] keeps names out of its reach and
 * [QualityCheck] finds the few lines worth escalating.
 */
class TranslationEngine private constructor(
    private val client: Translator,
    val sourceTag: String,
    val targetTag: String,
) : Closeable {

    companion object {
        /** Null when ML Kit has no model for one of the two languages. */
        fun create(sourceTag: String, targetTag: String): TranslationEngine? {
            val source = TranslateLanguage.fromLanguageTag(sourceTag) ?: return null
            val target = TranslateLanguage.fromLanguageTag(targetTag) ?: return null
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
            return TranslationEngine(Translation.getClient(options), sourceTag, targetTag)
        }

        fun supports(tag: String): Boolean = TranslateLanguage.fromLanguageTag(tag) != null

        /** Every language pair ML Kit can translate, as BCP-47 tags. */
        fun supportedTags(): List<String> = TranslateLanguage.getAllLanguages()

        suspend fun isModelDownloaded(tag: String): Boolean {
            val code = TranslateLanguage.fromLanguageTag(tag) ?: return false
            val model = TranslateRemoteModel.Builder(code).build()
            return RemoteModelManager.getInstance().isModelDownloaded(model).await()
        }

        suspend fun deleteModel(tag: String) {
            val code = TranslateLanguage.fromLanguageTag(tag) ?: return
            val model = TranslateRemoteModel.Builder(code).build()
            RemoteModelManager.getInstance().deleteDownloadedModel(model).await()
        }

        suspend fun downloadedTags(): List<String> =
            RemoteModelManager.getInstance()
                .getDownloadedModels(TranslateRemoteModel::class.java)
                .await()
                .map { it.language }
    }

    /**
     * Fetches the language models if they are not already on the device.
     * Everything after this point works with the network switched off.
     */
    suspend fun ensureModel(requireWifi: Boolean = false) {
        val conditions = DownloadConditions.Builder()
            .apply { if (requireWifi) requireWifi() }
            .build()
        client.downloadModelIfNeeded(conditions).await()
    }

    suspend fun translate(text: String): String =
        if (text.isBlank()) text else client.translate(text).await()

    override fun close() = client.close()
}
