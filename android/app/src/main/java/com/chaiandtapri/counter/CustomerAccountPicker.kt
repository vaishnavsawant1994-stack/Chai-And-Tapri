package com.chaiandtapri.counter

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import java.util.Locale

data class CustomerAccountOption(
    val customerId: String,
    val displayName: String,
    val outstandingPaise: Int
) {
    fun label(): String = buildString {
        append(displayName)
        if (!displayName.equals(customerId, ignoreCase = true)) append(" · ").append(customerId)
        append(" · due ₹").append(String.format(Locale.ROOT, "%.2f", outstandingPaise / 100.0))
    }
}

/** Shared picker for existing local profiles and legacy credit accounts; never creates accounts implicitly. */
object CustomerAccountPicker {
    fun options(profiles: List<CustomerProfile>, credits: List<CustomerCredit>): List<CustomerAccountOption> {
        val profileById = profiles.filter { it.customerId.isNotBlank() }.groupBy { it.customerId }.mapValues { it.value.first() }
        val ids = (profileById.keys + credits.map { it.customerId }).filter(String::isNotBlank).distinct()
        return ids.map { id ->
            CustomerAccountOption(
                customerId = id,
                displayName = profileById[id]?.displayName?.trim()?.takeIf(String::isNotBlank) ?: id,
                outstandingPaise = credits.asSequence().filter { it.customerId == id }.sumOf { it.outstandingPaise }.coerceAtLeast(0)
            )
        }.sortedWith(Comparator { left, right ->
            val nameOrder = left.displayName.compareTo(right.displayName, ignoreCase = true)
            if (nameOrder != 0) nameOrder else left.customerId.compareTo(right.customerId, ignoreCase = true)
        })
    }

    fun show(
        activity: Activity,
        model: CounterModel,
        title: String,
        onSelect: (String) -> Unit,
        onAddCustomer: () -> Unit
    ) {
        val accounts = options(model.customerProfiles(), model.customerCredits())
        if (accounts.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage("No saved customer profile or credit account is available. Add a customer profile before recording Udhar.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open customer directory") { _, _ -> onAddCustomer() }
                .show()
            return
        }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        val search = EditText(activity).apply {
            hint = "Search saved customer accounts"
            contentDescription = "Search saved customer accounts"
            setSingleLine(true)
        }
        val list = ListView(activity)
        layout.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layout.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 360))

        val shown = accounts.toMutableList()
        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, shown.map(CustomerAccountOption::label).toMutableList())
        list.adapter = adapter
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Add customer profile") { _, _ -> onAddCustomer() }
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let { selected ->
                dialog.dismiss()
                onSelect(selected.customerId)
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                val filtered = accounts.filter { option ->
                    query.isEmpty() || option.customerId.contains(query, true) || option.displayName.contains(query, true)
                }
                shown.clear()
                shown.addAll(filtered)
                adapter.clear()
                adapter.addAll(filtered.map(CustomerAccountOption::label))
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        dialog.show()
    }
}
