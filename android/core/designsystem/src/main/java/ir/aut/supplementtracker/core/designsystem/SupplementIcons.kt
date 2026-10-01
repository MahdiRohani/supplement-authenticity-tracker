package ir.aut.supplementtracker.core.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector

/** The app's icon vocabulary; features pick icons from here, not from the full set. */
object SupplementIcons {
    val Verify: ImageVector = Icons.Filled.VerifiedUser
    val Verified: ImageVector = Icons.Filled.Verified
    val Scan: ImageVector = Icons.Filled.QrCodeScanner
    val QrCode: ImageVector = Icons.Filled.QrCode2
    val Search: ImageVector = Icons.Filled.Search
    val Register: ImageVector = Icons.Filled.AddBox
    val Dashboard: ImageVector = Icons.Filled.Dashboard
    val Transfer: ImageVector = Icons.Filled.SwapHoriz
    val Consume: ImageVector = Icons.Filled.Medication
    val History: ImageVector = Icons.Filled.History
    val Stock: ImageVector = Icons.Filled.Inventory2
    val Refresh: ImageVector = Icons.Filled.Refresh
    val Copy: ImageVector = Icons.Filled.ContentCopy
    val Close: ImageVector = Icons.Filled.Close
    val Camera: ImageVector = Icons.Filled.CameraAlt
    val Pdf: ImageVector = Icons.Filled.PictureAsPdf
    val Secret: ImageVector = Icons.Filled.Key
    val Wallet: ImageVector = Icons.Filled.AccountBalanceWallet
    val Link: ImageVector = Icons.Filled.Link
    val Receipt: ImageVector = Icons.Filled.Receipt
    val Time: ImageVector = Icons.Filled.Schedule
    val Tag: ImageVector = Icons.Filled.Tag
    val Report: ImageVector = Icons.Filled.Report
    val Forward: ImageVector = Icons.AutoMirrored.Filled.ArrowForward
    val TorchOn: ImageVector = Icons.Filled.FlashOn
    val TorchOff: ImageVector = Icons.Filled.FlashOff
    val Login: ImageVector = Icons.AutoMirrored.Filled.Login
    val Logout: ImageVector = Icons.AutoMirrored.Filled.Logout

    val Manufacturer: ImageVector = Icons.Filled.Factory
    val Distributor: ImageVector = Icons.Filled.LocalShipping
    val Pharmacy: ImageVector = Icons.Filled.LocalPharmacy
    val Admin: ImageVector = Icons.Filled.AdminPanelSettings

    val Success: ImageVector = Icons.Filled.CheckCircle
    val Info: ImageVector = Icons.Filled.Info
    val Warning: ImageVector = Icons.Filled.Warning
    val Error: ImageVector = Icons.Filled.ErrorOutline
    val Blocked: ImageVector = Icons.Filled.Block
    val Offline: ImageVector = Icons.Filled.CloudOff
    val Unknown: ImageVector = Icons.AutoMirrored.Filled.HelpOutline
}
