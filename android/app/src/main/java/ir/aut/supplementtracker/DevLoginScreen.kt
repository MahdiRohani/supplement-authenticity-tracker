package ir.aut.supplementtracker

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import ir.aut.supplementtracker.core.designsystem.SupplementIcons
import ir.aut.supplementtracker.core.designsystem.components.NoticeCard
import ir.aut.supplementtracker.core.designsystem.components.NoticeTone
import ir.aut.supplementtracker.core.designsystem.components.ScreenHeader
import ir.aut.supplementtracker.core.designsystem.components.SectionHeader
import ir.aut.supplementtracker.core.designsystem.components.SelectableCard
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementCard
import ir.aut.supplementtracker.core.designsystem.components.SupplementScreen
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
    val addressValid = isEvmAddress(address)
    SupplementScreen(modifier = modifier) {
        ScreenHeader(
            title = stringResource(R.string.dev_login_title),
            subtitle = stringResource(R.string.dev_login_subtitle),
            icon = SupplementIcons.Login,
        )
        SectionHeader(title = stringResource(R.string.dev_login_choose_role))
        SupplyRole.entries.forEach { option ->
            SelectableCard(
                selected = role == option,
                title = stringResource(option.labelRes),
                description = stringResource(option.descriptionRes),
                icon = option.icon,
                onClick = { onRoleSelected(option) },
            )
        }
        SupplementCard {
            SupplementTextField(
                value = address,
                onValueChange = onAddressChanged,
                label = stringResource(R.string.dev_login_address),
                leadingIcon = SupplementIcons.Wallet,
                monospace = true,
                isError = address.isNotEmpty() && !addressValid,
                supportingText = stringResource(
                    if (address.isNotEmpty() && !addressValid) {
                        R.string.dev_login_address_invalid
                    } else {
                        R.string.dev_login_address_hint
                    },
                ),
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
                onImeAction = { if (addressValid) onContinue() },
            )
            SupplementButton(
                text = stringResource(R.string.dev_login_continue, stringResource(role.labelRes)),
                onClick = onContinue,
                enabled = addressValid,
                leadingIcon = SupplementIcons.Forward,
            )
        }
        NoticeCard(
            message = stringResource(R.string.dev_login_notice),
            tone = NoticeTone.Info,
        )
    }
}

@get:StringRes
val SupplyRole.labelRes: Int
    get() = when (this) {
        SupplyRole.Manufacturer -> R.string.role_manufacturer
        SupplyRole.Distributor -> R.string.role_distributor
        SupplyRole.Pharmacy -> R.string.role_pharmacy
        SupplyRole.Admin -> R.string.role_admin
    }

@get:StringRes
private val SupplyRole.descriptionRes: Int
    get() = when (this) {
        SupplyRole.Manufacturer -> R.string.role_manufacturer_desc
        SupplyRole.Distributor -> R.string.role_distributor_desc
        SupplyRole.Pharmacy -> R.string.role_pharmacy_desc
        SupplyRole.Admin -> R.string.role_admin_desc
    }

val SupplyRole.icon: ImageVector
    get() = when (this) {
        SupplyRole.Manufacturer -> SupplementIcons.Manufacturer
        SupplyRole.Distributor -> SupplementIcons.Distributor
        SupplyRole.Pharmacy -> SupplementIcons.Pharmacy
        SupplyRole.Admin -> SupplementIcons.Admin
    }

private val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

private fun isEvmAddress(value: String) = EVM_ADDRESS.matches(value.trim())

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
