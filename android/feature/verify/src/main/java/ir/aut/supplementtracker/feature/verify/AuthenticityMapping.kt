package ir.aut.supplementtracker.feature.verify

import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
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
