package es.topophone.app.ui.offline

/** El espacio libre del móvil para los tests (S037/RF-12). */
class FakeStorageInfo(var free: Long) : StorageInfo {
    override fun freeBytes(): Long = free
}

/** La red para los tests (S037/RF-13, RF-14). */
class FakeNetworkInfo(var kind: NetworkKind) : NetworkInfo {
    override fun kind(): NetworkKind = kind
}
