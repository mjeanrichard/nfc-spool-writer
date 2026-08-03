package ch.jeanrichard.nfcspoolwriter.data.spoolman

import ch.jeanrichard.nfcspoolwriter.data.settings.SettingsRepository
import ch.jeanrichard.nfcspoolwriter.domain.model.Spool
import kotlinx.coroutines.flow.first

/**
 * Spoolman access using the server address from settings.
 *
 * Thin by design: it resolves the configured URL and otherwise delegates. Keeping URL resolution out of
 * [SpoolmanApiClient] means the client stays a pure function of its inputs and testable without a
 * settings store, while this class is where "not configured yet" becomes a typed error rather than a
 * crash on first run.
 */
class SpoolmanRepository(
    private val apiClient: SpoolmanApiClient,
    private val settingsRepository: SettingsRepository,
) {

    /** Tests an address the user is still typing, which is not yet — and may never be — saved. */
    suspend fun testConnection(baseUrl: String): SpoolmanResult<Unit> =
        apiClient.testConnection(baseUrl)

    suspend fun testConfiguredConnection(): SpoolmanResult<Unit> =
        withBaseUrl { apiClient.testConnection(it) }

    suspend fun getSpool(spoolId: Int): SpoolmanResult<Spool> =
        withBaseUrl { apiClient.getSpool(it, spoolId) }

    suspend fun listSpools(
        filamentName: String? = null,
        material: String? = null,
        vendorName: String? = null,
        location: String? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): SpoolmanResult<SpoolPage> = withBaseUrl { baseUrl ->
        apiClient.listSpools(
            baseUrl = baseUrl,
            filamentName = filamentName,
            material = material,
            vendorName = vendorName,
            location = location,
            limit = limit,
            offset = offset,
        )
    }

    /**
     * Every non-archived spool, following pagination.
     *
     * The spool-list screen filters locally rather than re-querying per keystroke: Spoolman's filters
     * are per-field (name, material, vendor, location are separate parameters), so a single search box
     * would otherwise need several round trips per keystroke and still couldn't rank across fields. A
     * personal Spoolman holds tens to low hundreds of spools, so fetching once and filtering in memory
     * is both simpler and more responsive.
     *
     * [PAGE_LIMIT] and [MAX_SPOOLS] bound the work so a surprisingly large instance degrades by
     * truncating rather than by hanging — [SpoolPage.totalCount] still reports the real total, so the UI
     * can say the list was cut short.
     */
    suspend fun loadAllSpools(): SpoolmanResult<SpoolPage> {
        val collected = mutableListOf<Spool>()
        var offset = 0
        var total = 0

        while (true) {
            when (val page = listSpools(limit = PAGE_LIMIT, offset = offset)) {
                is SpoolmanResult.Failure -> return page
                is SpoolmanResult.Success -> {
                    collected += page.value.spools
                    total = page.value.totalCount
                    offset += page.value.spools.size

                    val exhausted = page.value.spools.isEmpty() || collected.size >= total
                    if (exhausted || collected.size >= MAX_SPOOLS) break
                }
            }
        }

        return SpoolmanResult.Success(SpoolPage(spools = collected, totalCount = total))
    }

    private suspend fun <T> withBaseUrl(
        block: suspend (String) -> SpoolmanResult<T>,
    ): SpoolmanResult<T> {
        val baseUrl = settingsRepository.spoolmanBaseUrl.first()
            ?: return SpoolmanResult.Failure(SpoolmanError.NotConfigured)
        return block(baseUrl)
    }

    companion object {
        const val PAGE_LIMIT = 200
        const val MAX_SPOOLS = 2_000
    }
}
