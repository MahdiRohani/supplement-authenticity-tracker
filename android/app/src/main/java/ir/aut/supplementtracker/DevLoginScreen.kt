package ir.aut.supplementtracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementTextField
import ir.aut.supplementtracker.core.model.SupplyRole
import ir.aut.supplementtracker.core.model.UserSession

@Composable
fun DevLoginScreen(
    role: SupplyRole,
    address: String,
    onRoleSelected: (SupplyRole) -> Unit,
    onAddressChanged: (String) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(SupplementSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
    ) {
        Text(text = stringResource(R.string.dev_login_title))
        SupplyRole.entries.forEach { option ->
            SupplementButton(
                text = option.name,
                onClick = { onRoleSelected(option) },
                enabled = role != option,
            )
        }
        SupplementTextField(
            value = address,
            onValueChange = onAddressChanged,
            label = stringResource(R.string.dev_login_address),
        )
        Text(text = stringResource(R.string.dev_login_selected, role.name))
        SupplementButton(
            text = stringResource(R.string.dev_login_continue),
            onClick = onContinue,
            enabled = address.startsWith("0x"),
        )
    }
}

fun defaultSessionFor(role: SupplyRole): UserSession {
    val address = when (role) {
        SupplyRole.Manufacturer, SupplyRole.Admin ->
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
        SupplyRole.Distributor ->
            "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
        SupplyRole.Pharmacy ->
            "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC"
    }
    return UserSession(role = role, address = address)
}
