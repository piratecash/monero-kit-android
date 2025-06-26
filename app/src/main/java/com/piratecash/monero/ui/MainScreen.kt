import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.piratecash.monero.ui.Destination
import com.piratecash.monero.ui.balance.BalanceScreen
import com.piratecash.monero.ui.balance.MainViewModel
import com.piratecash.monero.ui.send.SendScreen
import com.piratecash.monero.ui.send.SendViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    var selectedDestination by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                Destination.entries.forEachIndexed { index, destination ->
                    val title = when (destination) {
                        Destination.Balance -> "Balance"
                        Destination.Transactions -> "Transactions"
                        Destination.Send -> "Send"
                    }
                    NavigationBarItem(
                        selected = selectedDestination == index,
                        onClick = {
                            val route = destination::class.qualifiedName!!
                            navController.navigate(route) {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                            }
                            selectedDestination = index
                        },
                        icon = {},
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Balance,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable<Destination.Balance> {
                BalanceScreen(
                    uiState = viewModel.uiState.value,
                    onStartClick = viewModel::onStartClick,
                    onDebugClick = viewModel::onDebugClick,
                    onClearClick = viewModel::clearWallet,
                    onStatusClick = viewModel::onStatusClick
                )
            }
//            composable(Destination.Transactions::class) { TransactionsScreen() }
            composable<Destination.Send> {
                val viewModel: SendViewModel = viewModel()
                SendScreen(
                    uiState = viewModel.uiState.value,
                    onAddressChange = viewModel::onAddressChange,
                    onAmountChange = viewModel::onAmountChange,
                    onNotesChange = viewModel::onNotesChange,
                    onSendClick = viewModel::onSendClick
                )
            }
        }
    }
}
