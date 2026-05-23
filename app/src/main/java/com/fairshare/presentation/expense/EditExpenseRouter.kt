package com.fairshare.presentation.expense

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fairshare.domain.repository.ExpenseRepository
import com.fairshare.presentation.navigation.Route
import com.fairshare.presentation.receipt.EditReceiptScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Picks the right edit screen based on whether the expense has scanned items. */
@Composable
fun EditExpenseRouter(
    onDone: () -> Unit,
    vm: EditExpenseRouterViewModel = hiltViewModel(),
) {
    val kind by vm.kind.collectAsState()
    when (kind) {
        ExpenseKind.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        ExpenseKind.NotFound -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Dépense introuvable")
        }
        ExpenseKind.Simple -> AddExpenseScreen(onDone = onDone)
        ExpenseKind.Receipt -> EditReceiptScreen(onDone = onDone)
    }
}

enum class ExpenseKind { Loading, NotFound, Simple, Receipt }

@HiltViewModel
class EditExpenseRouterViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    expenseRepository: ExpenseRepository,
) : ViewModel() {

    private val expenseId: Long = checkNotNull(savedStateHandle[Route.ARG_EXPENSE_ID])

    private val _kind = MutableStateFlow(ExpenseKind.Loading)
    val kind: StateFlow<ExpenseKind> = _kind.asStateFlow()

    init {
        viewModelScope.launch {
            val expense = expenseRepository.get(expenseId)
            _kind.value = when {
                expense == null -> ExpenseKind.NotFound
                expense.items.isNotEmpty() -> ExpenseKind.Receipt
                else -> ExpenseKind.Simple
            }
        }
    }
}
