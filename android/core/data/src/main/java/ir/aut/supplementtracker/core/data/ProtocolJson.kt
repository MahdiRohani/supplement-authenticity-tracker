package ir.aut.supplementtracker.core.data

import ir.aut.supplementtracker.core.model.Batch
import ir.aut.supplementtracker.core.model.BatchDetail
import ir.aut.supplementtracker.core.model.ChainEvidence
import ir.aut.supplementtracker.core.model.Consumption
import ir.aut.supplementtracker.core.model.CustodyStep
import ir.aut.supplementtracker.core.model.Eip712Domain
import ir.aut.supplementtracker.core.model.Page
import ir.aut.supplementtracker.core.model.Party
import ir.aut.supplementtracker.core.model.ProtocolInfo
import ir.aut.supplementtracker.core.model.RegisteredBatch
import ir.aut.supplementtracker.core.model.RiskAssessment
import ir.aut.supplementtracker.core.model.RiskReason
import ir.aut.supplementtracker.core.model.Segment
import ir.aut.supplementtracker.core.model.SegmentTransferResult
import ir.aut.supplementtracker.core.model.UnitConsumeResult
import ir.aut.supplementtracker.core.model.UnitCredential
import ir.aut.supplementtracker.core.model.UnitHistory
import ir.aut.supplementtracker.core.model.UnitRef
import ir.aut.supplementtracker.core.model.UnitVerification
import org.json.JSONArray
import org.json.JSONObject

/** Parsers for the `/v2` response bodies (see backend/internal/protocol). */
internal object ProtocolJson {
    fun info(json: JSONObject): ProtocolInfo {
        val domain = json.getJSONObject("eip712Domain")
        return ProtocolInfo(
            chainId = json.getLong("activeChainId"),
            registryAddress = json.getString("registryAddress"),
            publicVerifyBaseUrl = json.str("publicVerifyBaseUrl").orEmpty(),
            domain = Eip712Domain(
                name = domain.getString("name"),
                version = domain.getString("version"),
                chainId = domain.getLong("chainId"),
                verifyingContract = domain.getString("verifyingContract"),
            ),
        )
    }

    fun batch(json: JSONObject): Batch =
        Batch(
            batchId = json.getString("batchId"),
            manufacturer = json.optString("manufacturer"),
            size = json.optInt("size"),
            consumedCount = json.optInt("consumedCount"),
            recalled = json.optBoolean("recalled"),
            name = json.str("name"),
            lotCode = json.str("lotCode"),
            merkleRoot = json.str("merkleRoot").orEmpty(),
            metadataCid = json.str("metadataCid").orEmpty(),
            metadataGatewayUrl = json.str("metadataGatewayUrl").orEmpty(),
            txHash = json.str("txHash").orEmpty(),
            createdAt = json.str("createdAt").orEmpty(),
        )

    fun registeredBatch(json: JSONObject): RegisteredBatch =
        RegisteredBatch(
            batch = batch(json),
            segmentId = json.getString("segmentId"),
            ipfsPinned = json.optBoolean("ipfsPinned"),
            keysRevealOnce = json.optBoolean("keysRevealOnce", true),
            units = json.optJSONArray("units").objects().map {
                UnitCredential(
                    index = it.getLong("index"),
                    unitKey = it.getString("unitKey"),
                    publicQr = it.getString("publicQr"),
                    secretQr = it.getString("secretQr"),
                )
            },
        )

    fun batchDetail(json: JSONObject): BatchDetail {
        val distribution = json.optJSONObject("distribution")
        return BatchDetail(
            batch = batch(json),
            segments = json.optJSONArray("segments").objects().map(::segment),
            distribution = distribution?.keys()?.asSequence()?.associateWith { distribution.optInt(it) }.orEmpty(),
        )
    }

    fun segment(json: JSONObject): Segment =
        Segment(
            segmentId = json.getString("segmentId"),
            batchId = json.getString("batchId"),
            owner = json.optString("owner"),
            start = json.getLong("start"),
            end = json.getLong("end"),
            units = json.optInt("units", (json.getLong("end") - json.getLong("start")).toInt()),
            status = json.getString("status"),
            updatedAt = json.str("updatedAt").orEmpty(),
        )

    fun <T> page(json: JSONObject, item: (JSONObject) -> T): Page<T> =
        Page(
            page = json.optInt("page", 1),
            limit = json.optInt("limit"),
            total = json.optInt("total"),
            totalPages = json.optInt("totalPages", 1),
            items = json.optJSONArray("items").objects().map(item),
        )

    fun transfer(json: JSONObject): SegmentTransferResult =
        SegmentTransferResult(
            batchId = json.getString("batchId"),
            fromSegmentId = json.getString("fromSegmentId"),
            toSegmentId = json.getString("toSegmentId"),
            split = json.optBoolean("split"),
            from = json.optString("from"),
            to = json.optString("to"),
            start = json.getLong("start"),
            end = json.getLong("end"),
            status = json.getString("status"),
            txHash = json.getString("txHash"),
        )

    fun verification(json: JSONObject, requested: UnitRef): UnitVerification {
        val product = json.optJSONObject("product") ?: JSONObject()
        val risk = json.optJSONObject("risk") ?: JSONObject()
        return UnitVerification(
            authenticity = json.getString("authenticity"),
            unit = UnitRef(
                chainId = json.optLong("chainId", requested.chainId),
                batchId = json.str("batchId") ?: requested.batchId,
                index = json.optLong("index", requested.index),
            ),
            productName = product.str("name"),
            lotCode = product.str("lotCode"),
            manufacturer = product.optJSONObject("manufacturer")?.let(::party),
            metadataCid = product.str("metadataCid"),
            metadataGatewayUrl = product.str("metadataGatewayUrl"),
            custodian = json.optJSONObject("custodian")?.let(::party),
            segmentId = json.str("segmentId"),
            segmentStatus = json.str("segmentStatus"),
            consumed = json.optBoolean("consumed"),
            consumption = json.optJSONObject("consumption")?.let(::consumption),
            recalled = json.optBoolean("recalled"),
            risk = RiskAssessment(
                score = risk.optDouble("score", 0.0),
                level = risk.str("level") ?: "low",
                reasons = risk.optJSONArray("reasons").objects().map {
                    RiskReason(it.getString("code"), it.optDouble("weight", 0.0), it.optString("message"))
                },
            ),
            scanCount = json.optInt("scanCount"),
            evidence = json.optJSONObject("evidence")?.let {
                ChainEvidence(
                    unitKey = it.getString("unitKey"),
                    merkleRoot = it.getString("merkleRoot"),
                    leaf = it.optString("leaf"),
                    proof = it.optJSONArray("proof").strings(),
                    registerTxHash = it.optString("registerTxHash"),
                )
            },
            checkedAt = json.optString("checkedAt"),
        )
    }

    fun history(json: JSONObject, requested: UnitRef): UnitHistory =
        UnitHistory(
            unit = UnitRef(requested.chainId, json.str("batchId") ?: requested.batchId, json.optLong("index", requested.index)),
            manufacturer = json.optString("manufacturer"),
            registeredAt = json.optString("registeredAt"),
            registerTxHash = json.optString("registerTxHash"),
            custody = json.optJSONArray("custody").objects().map {
                CustodyStep(
                    from = it.optString("from"),
                    to = it.optString("to"),
                    segmentId = it.optString("segmentId"),
                    status = it.optString("status"),
                    units = it.optInt("units"),
                    txHash = it.optString("txHash"),
                    blockNumber = it.optLong("blockNumber"),
                    at = it.optString("at"),
                )
            },
            consumption = json.optJSONObject("consumption")?.let(::consumption),
            recalled = json.optBoolean("recalled"),
        )

    fun consumeResult(json: JSONObject): UnitConsumeResult =
        UnitConsumeResult(
            status = json.optString("status", "Consumed"),
            batchId = json.getString("batchId"),
            index = json.getLong("index"),
            segmentId = json.optString("segmentId"),
            unitKey = json.optString("unitKey"),
            consumer = json.getString("consumer"),
            submitter = json.optString("submitter"),
            txHash = json.getString("txHash"),
            blockNumber = json.optLong("blockNumber"),
        )

    private fun party(json: JSONObject): Party =
        Party(
            address = json.getString("address"),
            role = json.str("role"),
            displayName = json.str("displayName"),
            region = json.str("region"),
        )

    private fun consumption(json: JSONObject): Consumption =
        Consumption(
            consumer = json.str("consumer"),
            txHash = json.optString("txHash"),
            blockNumber = if (json.isNull("blockNumber")) null else json.optLong("blockNumber"),
            at = json.str("at"),
        )

    /** org.json's optString turns JSON null into the string "null". */
    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else List(length()) { getJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else List(length()) { getString(it) }
}
