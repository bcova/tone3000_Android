package com.bcova.namplayer.t3k

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchFiltersTest {

    @Test
    fun emptySearchOnlyPages() {
        assertEquals("page=1&page_size=25", SearchFilters().toQuery(1))
    }

    @Test
    fun buildsQueryWithFilters() {
        val q = SearchFilters(
            query = " plexi ",
            sort = "newest",
            gears = setOf("amp-cab", "amp"),
            sizes = setOf("lite", "feather"),
            tags = setOf("high-gain", "clean"),
            makes = setOf("Marshall", "1965 Vox AC30"),
            format = "nam",
            a2Only = true,
            calibrated = true,
            verified = true,
        ).toQuery(3)
        assertEquals(
            "page=3&page_size=25&query=plexi&sort=newest&gears=amp_amp-cab&sizes=feather_lite" +
                "&tags=clean_high-gain&makes=1965+Vox+AC30_Marshall&format=nam&architecture=2" +
                "&calibrated=true&verified=true",
            q,
        )
    }

    @Test
    fun countsActiveFilters() {
        assertEquals(0, SearchFilters(query = "x", sort = "newest").activeFilterCount)
        assertEquals(4, SearchFilters(gears = setOf("amp"), tags = setOf("a", "b"), a2Only = true).activeFilterCount)
    }
}
