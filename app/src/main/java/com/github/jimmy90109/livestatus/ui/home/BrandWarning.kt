package com.github.jimmy90109.livestatus.ui.home

internal enum class BrandWarning {
    SAMSUNG_NOW_BAR,
    XIAOMI_HYPER_ISLAND,
    ASUS_LIVE_UPDATES,
}

internal fun detectBrandWarning(manufacturer: String?, brand: String?): BrandWarning? = when {
    manufacturer.isBrand("samsung") || brand.isBrand("samsung") ->
        BrandWarning.SAMSUNG_NOW_BAR
    manufacturer.isXiaomiFamily() || brand.isXiaomiFamily() ->
        BrandWarning.XIAOMI_HYPER_ISLAND
    manufacturer.isAsusFamily() || brand.isAsusFamily() ->
        BrandWarning.ASUS_LIVE_UPDATES
    else -> null
}

private fun String?.isBrand(expected: String): Boolean =
    this?.trim()?.equals(expected, ignoreCase = true) == true

private fun String?.isXiaomiFamily(): Boolean =
    isBrand("xiaomi") || isBrand("redmi") || isBrand("poco")

private fun String?.isAsusFamily(): Boolean =
    isBrand("asus") || isBrand("rog")
