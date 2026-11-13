package es.topophone.app.ui.wms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore

/**
 * Guarda los ViewModel de un test y los limpia al acabar: su corrutina deja de escuchar a Room antes de cerrar la base, y no queda una
 * excepción suelta que rompa otro test (`UncaughtExceptionsBeforeTest`).
 */
class TestViewModels {
    private val store = ViewModelStore()
    private var count = 0

    fun <T : ViewModel> keep(viewModel: T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = viewModel as V
        }
        ViewModelProvider(store, factory).get("vm${count++}", viewModel.javaClass)
        return viewModel
    }

    /**
     * Cancela las corrutinas de los ViewModel y espera a que acabe la consulta de Room que ya estuviera en marcha, que no se puede
     * interrumpir: si la base se cierra con ella en vuelo, falla fuera del test y el error le sale al siguiente.
     */
    fun clear() {
        store.clear()
        Thread.sleep(SETTLE_MS)
    }

    private companion object {
        const val SETTLE_MS = 150L
    }
}
