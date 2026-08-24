package app.spdvpn.backend.cache

import app.spdvpn.backend.model.CatalogSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

interface CatalogCacheStore {
    suspend fun load(): CatalogSnapshot?
    suspend fun save(snapshot: CatalogSnapshot)
}

class FileCatalogCacheStore(
    private val path: Path,
    private val json: Json,
) : CatalogCacheStore {
    override suspend fun load(): CatalogSnapshot? = withContext(Dispatchers.IO) {
        if (!Files.exists(path)) return@withContext null
        json.decodeFromString<CatalogSnapshot>(Files.readString(path))
    }

    override suspend fun save(snapshot: CatalogSnapshot) = withContext(Dispatchers.IO) {
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

class InMemoryCatalogCacheStore(
    initial: CatalogSnapshot? = null,
) : CatalogCacheStore {
    @Volatile
    private var snapshot: CatalogSnapshot? = initial

    override suspend fun load(): CatalogSnapshot? = snapshot

    override suspend fun save(snapshot: CatalogSnapshot) {
        this.snapshot = snapshot
    }
}
