import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.piratecash.monero.ui.Destination
import com.piratecash.monero.ui.balance.BalanceScreen
import com.piratecash.monero.ui.balance.BalanceViewModel
import com.piratecash.monero.ui.send.SendScreen
import com.piratecash.monero.ui.send.SendViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    var selectedDestination by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        Destination.entries[selectedDestination].title,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEachIndexed { index, destination ->
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
                        label = { Text(destination.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Balance::class,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Destination.Balance::class) {
                val viewModel: BalanceViewModel = viewModel()
                BalanceScreen(
                    uiState = viewModel.uiState.value,
                    onStartClick = viewModel::onStartClick,
                    onDebugClick = viewModel::onDebugClick,
                    onClearClick = viewModel::onClearClick,
                    onStatusClick = viewModel::onStatusClick
                )
            }
//            composable(Destination.Transactions::class) { TransactionsScreen() }
            composable(Destination.Send::class) {
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
