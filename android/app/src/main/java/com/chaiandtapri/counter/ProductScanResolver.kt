package com.chaiandtapri.counter

/** Resolves scanner keyboard input by exact, case-insensitive shop catalogue code. */
object ProductScanResolver {
    fun resolve(products: List<TapriProduct>, rawCode: String): TapriProduct? {
        val code = rawCode.trim().takeIf(String::isNotEmpty) ?: return null
        val matches = products.filter { it.active && it.scanCode?.equals(code, ignoreCase = true) == true }
        return matches.singleOrNull()
    }
}
