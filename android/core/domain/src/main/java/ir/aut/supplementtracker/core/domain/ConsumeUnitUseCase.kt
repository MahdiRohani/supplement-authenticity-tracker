package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.ConsumeAuthorization
import ir.aut.supplementtracker.core.model.SecretLabel
import ir.aut.supplementtracker.core.model.UnitConsumeResult
import ir.aut.supplementtracker.core.model.UnitRef

/**
 * Gasless consumption by the buyer: the hidden label's unit key signs a
 * short-lived authorization naming the buyer, and the relayer submits it. No
 * login, wallet balance, or pharmacy involvement is needed.
 */
class ConsumeUnitUseCase(
    private val repository: ProtocolRepository,
    private val signer: ConsumeSigner,
    private val identity: ConsumerIdentity,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val validitySeconds: Long = DEFAULT_VALIDITY_SECONDS,
) {
    /** [expected] is the unit whose public label was verified first, if any. */
    suspend operator fun invoke(label: SecretLabel, expected: UnitRef? = null): UnitConsumeResult {
        if (expected != null && expected != label.unit) {
            throw DomainError.Validation(HIDDEN_LABEL_MISMATCH)
        }
        val info = repository.info()
        if (info.chainId != label.chainId || info.domain.chainId != label.chainId) {
            throw DomainError.Validation(WRONG_CHAIN)
        }
        val consumer = identity.address()
        val deadline = nowSeconds() + validitySeconds
        val signature = signer.sign(label, consumer, deadline, info.domain)
        return repository.consume(
            ConsumeAuthorization(
                chainId = label.chainId,
                batchId = label.batchId,
                index = label.index,
                consumer = consumer,
                deadline = deadline,
                signature = signature,
            ),
        )
    }

    companion object {
        const val DEFAULT_VALIDITY_SECONDS = 10 * 60L
        const val HIDDEN_LABEL_MISMATCH = "HIDDEN_LABEL_MISMATCH"
        const val WRONG_CHAIN = "WRONG_CHAIN"
    }
}
