package com.chaiandtapri.counter

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity

/** CT-31: local speech preferences and shop-specific product aliases. */
class VoiceSettingsActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var language: Spinner
    private lateinit var product: Spinner
    private lateinit var aliases: EditText
    private lateinit var status: TextView
    private var products: List<TapriProduct> = emptyList()
    private var selectedLanguageTag = "hi-IN"
    private var loading = false
    private var saving = false
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionAccessGate.requireOwner(this)) return
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 28, 28, 28) }
        setContentView(ScrollView(this).apply { addView(root) })
        renderLoading()
        loadSettings()
    }

    override fun onResume() { super.onResume(); if (!loading && root.childCount == 0) loadSettings() }
    override fun onDestroy() { destroyed = true; super.onDestroy() }

    private fun renderLoading() {
        root.removeAllViews()
        root.addView(label("Voice Settings · CT-31", 24f))
        root.addView(label("Loading encrypted shop preferences…", 16f))
    }

    private fun loadSettings() {
        if (loading) return
        loading = true
        RecoveryExecutor.executor.execute {
            val result = runCatching {
                SqliteCounterPersistence(this).useStore { store ->
                    Triple(store.loadShopProfile(), store.loadProducts(), store.loadVoiceLanguageTag())
                }
            }
            runOnUiThread {
                loading = false
                if (destroyed || isFinishing || isDestroyed) return@runOnUiThread
                result.fold({ (loadedProfile, loadedProducts, savedLanguage) ->
                    products = loadedProducts
                    selectedLanguageTag = savedLanguage.ifBlank { loadedProfile.languageTag }
                    render()
                }, { error ->
                    root.removeAllViews()
                    root.addView(label("Voice settings could not be opened. No preferences were changed (${error.javaClass.simpleName}).", 16f))
                    root.addView(button("Retry") { loadSettings() })
                    root.addView(button("Back") { finish() })
                })
            }
        }
    }

    private fun render() {
        root.removeAllViews()
        root.addView(label("Voice Settings · CT-31", 24f))
        root.addView(label("Local speech preferences are stored in the encrypted shop database. Voice commands still require review before application.", 14f))
        root.addView(label("Recognition language", 16f))
        language = Spinner(this).apply {
            adapter = ArrayAdapter(this@VoiceSettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Hindi / Hinglish (hi-IN)", "Marathi (mr-IN)", "English India (en-IN)"))
            setSelection(when (selectedLanguageTag) { "mr-IN" -> 1; "en-IN" -> 2; else -> 0 })
        }
        root.addView(language, matchWidth())
        root.addView(button("Save language") { saveLanguage() })
        root.addView(label("Shop product aliases", 16f))
        root.addView(label("Add one alias per line. Aliases only help interpret a reviewed command; they never execute a sale.", 13f))
        product = Spinner(this).apply {
            adapter = ArrayAdapter(this@VoiceSettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                products.map { "${it.name} (${it.productId})" }.ifEmpty { listOf("No products configured") })
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (::aliases.isInitialized) aliases.setText(products.getOrNull(position)?.aliases.orEmpty().joinToString("\n"))
                }
            }
        }
        root.addView(product, matchWidth())
        aliases = EditText(this).apply { hint = "cutting chai\nspecial chai"; minLines = 3; maxLines = 8 }
        root.addView(aliases, matchWidth())
        root.addView(button("Save aliases") { saveAliases() }.apply { isEnabled = products.isNotEmpty() })
        root.addView(label("Safety and capability", 16f))
        root.addView(label("Every recognized command requires operator review. Unclear product or order matches are rejected. This build uses one-utterance speech recognition; continuous Bluetooth listening is not implemented or qualified.", 13f))
        status = label("Ready", 14f)
        root.addView(status)
        root.addView(button("Back to counter") { finish() })
    }

    private fun saveLanguage() {
        if (saving) return
        val selectedTag = listOf("hi-IN", "mr-IN", "en-IN").getOrElse(language.selectedItemPosition) { "hi-IN" }
        saving = true
        setButtonsEnabled(false)
        status.text = "Saving language preference…"
        RecoveryExecutor.executor.execute {
            val result = runCatching { CounterModel(SqliteCounterPersistence(this)).useModel { it.saveVoiceLanguageTag(selectedTag) } }
            runOnUiThread {
                saving = false
                if (destroyed || isFinishing || isDestroyed) return@runOnUiThread
                setButtonsEnabled(true)
                result.fold({ message ->
                    if (message.startsWith("Voice language saved")) selectedLanguageTag = selectedTag
                    status.text = message
                }, { error -> status.text = "Voice language was not saved (${error.javaClass.simpleName})." })
            }
        }
    }

    private fun saveAliases() {
        if (saving) return
        val selected = products.getOrNull(product.selectedItemPosition)
        if (selected == null) { status.text = "Configure a product before adding aliases."; return }
        val cleaned = aliases.text.lines().map(String::trim).filter(String::isNotBlank).distinctBy(String::lowercase)
        if (cleaned.any { it.length > 40 }) { aliases.error = "Each alias must be 40 characters or fewer."; return }
        val updated = selected.copy(aliases = cleaned)
        saving = true
        setButtonsEnabled(false)
        status.text = "Saving product aliases…"
        RecoveryExecutor.executor.execute {
            val result = runCatching { CounterModel(SqliteCounterPersistence(this), requireConfiguredProducts = false).useModel { it.saveProduct(updated) } }
            runOnUiThread {
                saving = false
                if (destroyed || isFinishing || isDestroyed) return@runOnUiThread
                setButtonsEnabled(true)
                result.fold({ message ->
                    status.text = message
                    if (message.startsWith("Product saved")) products = products.map { if (it.productId == updated.productId) updated else it }
                }, { error -> status.text = "Aliases were not saved (${error.javaClass.simpleName})." })
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        fun visit(group: ViewGroup) {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child is Button && child.text.toString() != "Back to counter") child.isEnabled = enabled
                if (child is ViewGroup) visit(child)
            }
        }
        visit(root)
    }

    private fun label(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setPadding(0, 10, 0, 8) }
    private fun button(value: String, action: () -> Unit) = Button(this).apply { text = value; minHeight = 56; setOnClickListener { action() } }
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

private inline fun <T> SqliteCounterPersistence.useStore(block: (SqliteCounterPersistence) -> T): T = try { block(this) } finally { close() }
private inline fun <T> CounterModel.useModel(block: (CounterModel) -> T): T = try { block(this) } finally { close() }
