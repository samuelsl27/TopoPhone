package es.topophone.core.geodesy.catalog

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Favoritos y recientes en el selector de SC (S034/RF-6, RF-7, RF-8). */
class CrsShortcutsTest {
    private val shortcuts = CrsShortcuts(CrsCatalog.bundled)

    @Test
    fun `S034 RF-6 primero los favoritos y despues los recientes`() {
        val list = shortcuts.resolve(favoriteEpsg = listOf(2154, 25830), recentEpsg = listOf(3857, 4326))

        list.favorites.map { it.epsg } shouldBe listOf(2154, 25830)
        list.recents.map { it.epsg } shouldBe listOf(3857, 4326)
        list.all.map { it.epsg } shouldBe listOf(2154, 25830, 3857, 4326)
    }

    @Test
    fun `S034 RF-8 un favorito no se repite entre los recientes ni un reciente repetido sale dos veces`() {
        val list = shortcuts.resolve(favoriteEpsg = listOf(2154), recentEpsg = listOf(2154, 25830, 25830, 3857))

        list.recents.map { it.epsg } shouldBe listOf(25830, 3857)
    }

    @Test
    fun `S034 RF-7 un favorito que la biblioteca ya no trae no se muestra pero tampoco rompe nada`() {
        val list = shortcuts.resolve(favoriteEpsg = listOf(99999999, 25830), recentEpsg = listOf(99999998))

        list.favorites.map { it.epsg } shouldBe listOf(25830)
        list.recents shouldBe emptyList()
    }

    @Test
    fun `S034 RF-6 sin favoritos ni recientes no hay atajos`() {
        shortcuts.resolve(emptyList(), emptyList()).isEmpty shouldBe true
    }
}
