package ir.aut.supplementtracker.feature.verify

import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.model.RiskLevel
import ir.aut.supplementtracker.core.model.VerifiedUnit
import ir.aut.supplementtracker.core.model.VerifyResult

fun VerifyResult.toAuthenticityStatus(): AuthenticityStatus =
    when (authenticity) {
        "Authentic" -> AuthenticityStatus.Authentic
        "Consumed" -> AuthenticityStatus.Consumed
        "Invalid" -> AuthenticityStatus.Invalid
        else -> when (status) {
            "Created", "Transferred", "AtPointOfSale" -> AuthenticityStatus.Authentic
            "Consumed" -> AuthenticityStatus.Consumed
            "Invalid" -> AuthenticityStatus.Invalid
            else -> AuthenticityStatus.NotFound
        }
    }

/**
 * The backend's verdict, unless the device's own checks contradict the API's
 * evidence; a contradicted "Authentic" is shown as suspicious.
 */
fun VerifiedUnit.toAuthenticityStatus(): AuthenticityStatus {
    val status = AuthenticityStatus.fromAuthenticity(verification.authenticity)
    if (!check.contradicted) return status
    return when (status) {
        AuthenticityStatus.Authentic, AuthenticityStatus.InTransit -> AuthenticityStatus.Suspicious
        else -> status
    }
}

fun riskLevelOf(level: String): RiskTone =
    when (level) {
        RiskLevel.HIGH -> RiskTone.High
        RiskLevel.MEDIUM -> RiskTone.Medium
        else -> RiskTone.Low
    }

enum class RiskTone { Low, Medium, High }
