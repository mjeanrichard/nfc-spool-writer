package ch.jeanrichard.nfcspoolwriter.testsupport

import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalog
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolPage
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanError
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanResult
import ch.jeanrichard.nfcspoolwriter.domain.mapping.FieldMappingService
import ch.jeanrichard.nfcspoolwriter.domain.mapping.MaterialMatcher
import ch.jeanrichard.nfcspoolwriter.domain.model.Filament
import ch.jeanrichard.nfcspoolwriter.domain.model.Spool
import ch.jeanrichard.nfcspoolwriter.domain.model.Vendor
import io.mockk.coEvery
import io.mockk.mockk

/**
 * Test doubles for the ViewModel tests.
 *
 * `SpoolmanRepository` is mocked with MockK rather than hidden behind an interface or made `open`:
 * REQUIREMENTS.md NFR-21 rules out repository-interface-plus-fake scaffolding until a second real
 * implementation
 * justifies it, and MockK is on the dependency list for exactly this. The upshot is that no production
 * code is shaped by test needs.
 */
fun fakeSpoolmanRepository(
    spools: List<Spool> = emptyList(),
    totalCount: Int? = null,
    listError: SpoolmanError? = null,
    getError: SpoolmanError? = null,
    testError: SpoolmanError? = null,
): SpoolmanRepository {
    val listResult: SpoolmanResult<SpoolPage> = if (listError != null) {
        SpoolmanResult.Failure(listError)
    } else {
        // Passed through as-is: null models a server that reported no total, which is a case the
        // consumers have to handle rather than one the fake should paper over.
        SpoolmanResult.Success(SpoolPage(spools, totalCount))
    }
    val testResult: SpoolmanResult<Unit> =
        if (testError != null) SpoolmanResult.Failure(testError) else SpoolmanResult.Success(Unit)

    return mockk(relaxed = true) {
        coEvery { loadAllSpools() } returns listResult

        coEvery { getSpool(any()) } answers {
            val id = firstArg<Int>()
            when {
                getError != null -> SpoolmanResult.Failure(getError)
                else -> spools.firstOrNull { it.id == id }
                    ?.let { SpoolmanResult.Success(it) }
                    ?: SpoolmanResult.Failure(SpoolmanError.SpoolNotFound(id))
            }
        }

        coEvery { testConnection(any()) } returns testResult
    }
}

/**
 * A real [FieldMappingService] over the real bundled catalog. Mapping is pure and already covered by
 * its own tests, so faking it here would only weaken the ViewModel tests.
 */
fun realFieldMappingService(): FieldMappingService =
    FieldMappingService(MaterialMatcher(realMaterialCatalog()))

fun realMaterialCatalog(): MaterialCatalog =
    MaterialCatalog.fromJson(bundledMaterialCatalogJson())

fun testSpool(
    id: Int = 42,
    material: String? = "PLA",
    name: String? = "PolyTerra PLA Blue",
    vendorName: String? = "Polymaker",
    colorHex: String? = "0000FF",
    fullWeight: Double? = 1000.0,
    location: String? = "Shelf A",
    lotNumber: String? = null,
) = Spool(
    id = id,
    location = location,
    lotNumber = lotNumber,
    filament = Filament(
        id = id * 10,
        name = name,
        vendor = vendorName?.let { Vendor(id = 1, name = it) },
        material = material,
        colorHex = colorHex,
        fullSpoolWeightGrams = fullWeight,
    ),
)
