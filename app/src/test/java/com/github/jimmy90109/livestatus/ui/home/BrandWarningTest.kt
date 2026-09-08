package com.github.jimmy90109.livestatus.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrandWarningTest {
    @Test
    fun detectBrandWarning_recognizesAsusAndRogFromEitherBuildField() {
        assertEquals(
            BrandWarning.ASUS_LIVE_UPDATES,
            detectBrandWarning(manufacturer = "ASUS", brand = null),
        )
        assertEquals(
            BrandWarning.ASUS_LIVE_UPDATES,
            detectBrandWarning(manufacturer = null, brand = "asus"),
        )
        assertEquals(
            BrandWarning.ASUS_LIVE_UPDATES,
            detectBrandWarning(manufacturer = "  Asus  ", brand = "unknown"),
        )
        assertEquals(
            BrandWarning.ASUS_LIVE_UPDATES,
            detectBrandWarning(manufacturer = "unknown", brand = "ROG"),
        )
    }

    @Test
    fun detectBrandWarning_preservesExistingSamsungAndXiaomiFamilies() {
        assertEquals(
            BrandWarning.SAMSUNG_NOW_BAR,
            detectBrandWarning(manufacturer = " Samsung ", brand = null),
        )
        listOf("Xiaomi", "REDMI", " poco ").forEach { value ->
            assertEquals(
                BrandWarning.XIAOMI_HYPER_ISLAND,
                detectBrandWarning(manufacturer = null, brand = value),
            )
        }
    }

    @Test
    fun detectBrandWarning_preservesBrandPriority() {
        assertEquals(
            BrandWarning.SAMSUNG_NOW_BAR,
            detectBrandWarning(manufacturer = "samsung", brand = "asus"),
        )
        assertEquals(
            BrandWarning.XIAOMI_HYPER_ISLAND,
            detectBrandWarning(manufacturer = "xiaomi", brand = "rog"),
        )
    }

    @Test
    fun detectBrandWarning_returnsNullForUnknownOrMissingBrands() {
        assertNull(detectBrandWarning(manufacturer = "google", brand = "pixel"))
        assertNull(detectBrandWarning(manufacturer = " ", brand = null))
        assertNull(detectBrandWarning(manufacturer = null, brand = null))
    }
}
