package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.IndependentCheck
import ir.aut.supplementtracker.core.model.UnitRef
import ir.aut.supplementtracker.core.model.UnitVerification
import ir.aut.supplementtracker.core.model.VerifiedUnit
import kotlinx.coroutines.CancellationException

/**
 * Verifies a unit through the API, then re-checks the API's evidence on the
 * device: the Merkle proof must lead to the root, and the root and consumed bit
 * must match the registry contract. A dishonest or stale backend therefore
 * cannot turn a copied or used label into "Authentic" unnoticed.
 */
class VerifyUnitUseCase(
    private val repository: ProtocolRepository,
    private val scanContext: ScanContextProvider,
    private val proofVerifier: MerkleProofVerifier? = null,
    private val anchorReader: ChainAnchorReader? = null,
) {
    suspend operator fun invoke(unit: UnitRef): VerifiedUnit {
        val verification = repository.verify(unit, scanContext.current())
        return VerifiedUnit(verification, check(verification))
    }

    private suspend fun check(result: UnitVerification): IndependentCheck {
        val evidence = result.evidence ?: return IndependentCheck()
        val proofValid =
            proofVerifier?.let {
                runCatching { it.verify(result.unit.index, evidence.unitKey, evidence.proof, evidence.merkleRoot) }
                    .getOrDefault(false)
            }
        val anchor =
            anchorReader?.let { reader ->
                try {
                    val info = repository.info()
                    if (info.chainId != result.unit.chainId) return@let null
                    reader.read(info.registryAddress, result.unit.batchId, result.unit.index)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    null
                }
            }
        return IndependentCheck(
            proofValid = proofValid,
            rootMatchesChain = anchor?.let { it.merkleRoot.equals(evidence.merkleRoot, ignoreCase = true) },
            consumedMatchesChain = anchor?.let { it.consumed == result.consumed },
        )
    }
}
