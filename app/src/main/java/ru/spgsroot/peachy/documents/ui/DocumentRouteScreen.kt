package ru.spgsroot.peachy.documents.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.documents.DocumentKind
import ru.spgsroot.peachy.documents.DocumentSession
import ru.spgsroot.peachy.documents.DocumentSessions
import ru.spgsroot.peachy.documents.image.ImageEditorScreen
import ru.spgsroot.peachy.documents.office.ui.OfficeEditorScreen
import ru.spgsroot.peachy.documents.office.ui.OfficeIntegration
import ru.spgsroot.peachy.documents.pdf.PdfEditorScreen

sealed interface DocumentRouteState {
    data object Loading : DocumentRouteState
    data object Missing : DocumentRouteState
    data class Ready(val session: DocumentSession) : DocumentRouteState
}

@HiltViewModel
class DocumentRouteViewModel @Inject constructor(
    private val sessions: DocumentSessions,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: String = savedStateHandle.get<String>("id").orEmpty()

    private val _state = MutableStateFlow<DocumentRouteState>(DocumentRouteState.Loading)
    val state = _state.asStateFlow()

    private val _problems = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val problems = _problems.asSharedFlow()

    init {
        viewModelScope.launch {
            _state.value = sessions.get(sessionId)
                ?.let(DocumentRouteState::Ready)
                ?: DocumentRouteState.Missing
        }
    }

    fun discard(onDiscarded: () -> Unit) {
        viewModelScope.launch {
            sessions.discard(sessionId)
                .onSuccess { onDiscarded() }
                .onFailure { _problems.emit(Unit) }
        }
    }
}

/**
 * The one route that opens a session: the kind decides the editor. Office kinds have no engine in
 * this build, so they get a real refusal that names the sniffed format instead of a blank editor.
 */
@Composable
fun DocumentRouteScreen(
    onClose: () -> Unit,
    viewModel: DocumentRouteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val current = state) {
        DocumentRouteState.Loading -> LoadingState()
        DocumentRouteState.Missing -> MissingSessionState(onBack = onClose)
        is DocumentRouteState.Ready -> when (current.session.kind) {
            DocumentKind.PDF -> PdfEditorScreen(sessionId = current.session.id, onClose = onClose)
            DocumentKind.IMAGE -> ImageEditorScreen(sessionId = current.session.id, onClose = onClose)
            DocumentKind.WORD, DocumentKind.EXCEL, DocumentKind.POWERPOINT ->
                if (OfficeIntegration.ENABLED) {
                    OfficeEditorScreen(sessionId = current.session.id, onClose = onClose)
                } else {
                    OfficeUnavailableScreen(session = current.session, onDiscard = {
                        viewModel.discard(onClose)
                    }, onBack = onClose, onDiscardFailed = viewModel)
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfficeUnavailableScreen(
    session: DocumentSession,
    onDiscard: () -> Unit,
    onBack: () -> Unit,
    onDiscardFailed: DocumentRouteViewModel,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        onDiscardFailed.problems.collect {
            snackbar.showSnackbar(context.getString(R.string.documents_delete_failed))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.documents_office_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.documents_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = session.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    R.string.documents_office_body,
                    session.format.uppercase(),
                    kindLabelText(session.kind),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onDiscard) {
                Text(stringResource(R.string.documents_office_delete))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.documents_back))
            }
        }
    }
}
