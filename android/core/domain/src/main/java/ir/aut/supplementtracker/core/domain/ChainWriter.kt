package ir.aut.supplementtracker.core.domain

import ir.aut.supplementtracker.core.model.ConsumeRequest
import ir.aut.supplementtracker.core.model.ConsumeResult
import ir.aut.supplementtracker.core.model.TransferRequest
import ir.aut.supplementtracker.core.model.TransferResult

interface ChainWriter {
    suspend fun consume(request: ConsumeRequest): ConsumeResult

    suspend fun transfer(request: TransferRequest): TransferResult
}
