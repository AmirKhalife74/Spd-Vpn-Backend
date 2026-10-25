package app.spdvpn.backend.panel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

interface PanelContentStore {
    suspend fun load(): PanelContentSnapshot
    suspend fun update(transform: (PanelContentSnapshot) -> PanelContentSnapshot): PanelContentSnapshot
}

class FilePanelContentStore(
    private val path: Path,
    private val json: Json,
) : PanelContentStore {
    private val mutex = Mutex()
    private var cached: PanelContentSnapshot? = null

    override suspend fun load(): PanelContentSnapshot = mutex.withLock {
        cached ?: readFromDisk().also { cached = it }
    }

    override suspend fun update(
        transform: (PanelContentSnapshot) -> PanelContentSnapshot,
    ): PanelContentSnapshot = mutex.withLock {
        val current = cached ?: readFromDisk()
        val updated = transform(current)
        writeToDisk(updated)
        cached = updated
        updated
    }

    private suspend fun readFromDisk(): PanelContentSnapshot = withContext(Dispatchers.IO) {
        if (!Files.exists(path)) return@withContext PanelContentSnapshot()
        json.decodeFromString<PanelContentSnapshot>(Files.readString(path))
    }

    private suspend fun writeToDisk(snapshot: PanelContentSnapshot) = withContext(Dispatchers.IO) {
        path.parent?.let(Files::createDirectories)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(snapshot))

        try {
            Files.move(
                temporary,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
        Unit
    }
}

class InMemoryPanelContentStore(
    initial: PanelContentSnapshot = PanelContentSnapshot(),
) : PanelContentStore {
    private val mutex = Mutex()
    private var snapshot = initial

    override suspend fun load(): PanelContentSnapshot = mutex.withLock { snapshot }

    override suspend fun update(
        transform: (PanelContentSnapshot) -> PanelContentSnapshot,
    ): PanelContentSnapshot = mutex.withLock {
        transform(snapshot).also { snapshot = it }
    }
}
