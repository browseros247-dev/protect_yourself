package protect.yourself.features.blockerPage.utils

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import protect.yourself.database.selectedKeywords.SelectedKeywordIdentifier
import protect.yourself.database.selectedKeywords.SelectedKeywordItemModel
import timber.log.Timber
import java.util.Locale

/**
 * Default keyword data loader.
 *
 * Loads preset block + whitelist keywords from `assets/preset_block_keywords.json`
 * and `assets/preset_whitelist_keywords.json` (extracted from original NopoX APK
 * via Base64-decoding the embedded data in BlockerPageUtils.blockingKeywords()).
 *
 * Original behavior:
 *  - For English locale: return only English keywords.
 *  - For other locales: return English + current-locale keywords (concatenated).
 */
class DefaultKeywordData(
    private val context: Context,
    private val gson: Gson = Gson()
) {

    /** Returns preset BLOCK keywords for current locale (English + current language). */
    fun getDefaultBlockKeywords(): List<String> {
        val data = loadBlockKeywords()
        val currentLang = Locale.getDefault().language
        val enKeywords = data["en"].orEmpty()
        if (currentLang == "en" || currentLang !in data.keys) {
            return enKeywords.split(',').filter { it.isNotBlank() }
        }
        val localized = data[currentLang].orEmpty()
        val combined = "$enKeywords,$localized"
        return combined.split(',').filter { it.isNotBlank() }.distinct()
    }

    /** Returns preset WHITELIST keywords (English only — original behavior). */
    fun getDefaultWhitelistKeywords(): List<String> {
        val data = loadWhitelistKeywords()
        val enKeywords = data["en"].orEmpty()
        return enKeywords.split(',').filter { it.isNotBlank() }
    }

    /** Returns all preset block keywords as SelectedKeywordItemModel for DB insertion. */
    fun getDefaultBlockKeywordModels(): List<SelectedKeywordItemModel> {
        return getDefaultBlockKeywords().mapIndexed { index, keyword ->
            SelectedKeywordItemModel(
                key = "preset_block_$index",
                keyword = keyword,
                identifier = SelectedKeywordIdentifier.PORN_BLOCK_WORDS.value,
                isSelected = true
            )
        }
    }

    /** Returns all preset whitelist keywords as SelectedKeywordItemModel for DB insertion. */
    fun getDefaultWhitelistKeywordModels(): List<SelectedKeywordItemModel> {
        return getDefaultWhitelistKeywords().mapIndexed { index, keyword ->
            SelectedKeywordItemModel(
                key = "preset_whitelist_$index",
                keyword = keyword,
                identifier = SelectedKeywordIdentifier.PORN_WHITE_LIST_WORDS.value,
                isSelected = true
            )
        }
    }

    private fun loadBlockKeywords(): Map<String, String> {
        return loadJsonMap("preset_block_keywords.json")
    }

    private fun loadWhitelistKeywords(): Map<String, String> {
        return loadJsonMap("preset_whitelist_keywords.json")
    }

    private fun loadJsonMap(assetName: String): Map<String, String> {
        return try {
            context.assets.open(assetName).use { stream ->
                val text = stream.bufferedReader().readText()
                val type = object : TypeToken<Map<String, String>>() {}.type
                gson.fromJson(text, type) ?: emptyMap()
            }
        } catch (t: Throwable) {
            Timber.e(t, "Failed to load $assetName")
            emptyMap()
        }
    }

    companion object {
        @Volatile
        private var instance: DefaultKeywordData? = null

        fun getInstance(context: Context): DefaultKeywordData {
            return instance ?: synchronized(this) {
                instance ?: DefaultKeywordData(context.applicationContext).also { instance = it }
            }
        }
    }
}
