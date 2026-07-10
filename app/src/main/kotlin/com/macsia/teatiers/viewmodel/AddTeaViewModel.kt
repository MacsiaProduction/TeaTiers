package com.macsia.teatiers.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.R
import com.macsia.teatiers.data.photos.ImageReader
import com.macsia.teatiers.data.repository.AddPhotoResult
import com.macsia.teatiers.data.repository.CatalogDetailResult
import com.macsia.teatiers.data.repository.CatalogRepository
import com.macsia.teatiers.data.repository.CatalogSearchResult
import com.macsia.teatiers.data.repository.OcrResult
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.data.repository.TeaEnrichmentManager
import com.macsia.teatiers.domain.model.CatalogTea
import com.macsia.teatiers.domain.model.CatalogTeaDetail
import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.PhotoSource
import com.macsia.teatiers.domain.model.TeaPhoto
import com.macsia.teatiers.domain.model.Tier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Owns the add/edit form state. Add mode wants a [boardId] (the new placement targets that
 * board); edit mode wants only a [editingTeaId] because the user-tea is shared across boards
 * (decisions.md #42) — saving in edit mode never reorders any board, the tier picker is
 * hidden, and the change ripples to every placement.
 */
@HiltViewModel
class AddTeaViewModel @Inject constructor(
    private val repository: TeaBoardRepository,
    private val catalogRepository: CatalogRepository,
    private val enrichmentManager: TeaEnrichmentManager,
    private val imageReader: ImageReader,
) : ViewModel() {

    private val eventHost = UiEventHost()
    /** One-shot UI events (snackbars). Screen-side `LaunchedEffect` collects this. */
    val events get() = eventHost.events

    /** Toggled by the screen when the user taps Save with an invalid form. The screen reads
     *  this once via [consumeNameRequiredFocus] and routes focus to the nameRu field. */
    private var pendingNameFocus = false
    fun consumeNameRequiredFocus(): Boolean {
        val taken = pendingNameFocus
        pendingNameFocus = false
        return taken
    }

    private val _form = MutableStateFlow(AddTeaForm())
    val form: StateFlow<AddTeaForm> = _form.asStateFlow()

    /** True while a [submit] is in flight. Guards against double-submit (UX-P0-1) and lets the screen
     *  disable the Save button so the user sees the save is running. */
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    /** True while an edit-mode form loads its tea (UX3-P2-4) so the screen shows a spinner instead of a
     *  blank default form for a frame before the load resolves. Always false in add mode. */
    private val _formLoading = MutableStateFlow(false)
    val formLoading: StateFlow<Boolean> = _formLoading.asStateFlow()

    /**
     * The form as last bound (empty in add mode, the loaded tea in edit mode); [isDirty] compares
     * against it so the screen can warn before discarding edits (audit #3). Edit-mode photo changes
     * persist immediately, so only the draft photos (add mode) count toward dirtiness here.
     */
    private var pristineForm = AddTeaForm()
    fun isDirty(): Boolean = _form.value != pristineForm || _draftPhotos.value.isNotEmpty()

    /** "Scan packaging" flow (slice 3): Idle → Recognizing → Review(text) → applied to sourceText. */
    private val _scan = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val scan: StateFlow<ScanUiState> = _scan.asStateFlow()

    /** The in-flight scan coroutine, tracked so [cancelScan] can abort it (UX3-P1-6) and [bind] can
     *  clear it — otherwise a scan started for one tea leaks into a re-bound session (UX3-P1-5). */
    private var scanJob: Job? = null

    /**
     * Catalog search box (add mode only). Typing here queries the shared catalog; picking a result
     * prefills the form. Debounced + min-length so we never hit the API per keystroke (plan §6).
     */
    private val _catalogQuery = MutableStateFlow("")
    val catalogQuery: StateFlow<String> = _catalogQuery.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val catalogSearch: StateFlow<CatalogSearchUiState> = _catalogQuery
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce { query -> if (query.isEmpty()) 0L else CATALOG_SEARCH_DEBOUNCE_MS }
        .flatMapLatest { query ->
            if (!isSearchableQuery(query)) {
                flowOf<CatalogSearchUiState>(CatalogSearchUiState.Idle)
            } else {
                flow {
                    emit(CatalogSearchUiState.Loading)
                    emit(catalogRepository.search(query).toUiState())
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogSearchUiState.Idle)

    /**
     * Catalog detail sheet (add mode). [_catalogDetailId] holds the open tea's id (null = closed);
     * [_catalogDetailRetry] is a bump counter so "retry" re-runs the fetch for the same id (a plain
     * id re-set would be conflated by the StateFlow). The sheet stays mounted under the add form, so
     * opening it never re-binds the form or drops the in-progress search.
     */
    private val _catalogDetailId = MutableStateFlow<Long?>(null)
    private val _catalogDetailRetry = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val catalogDetail: StateFlow<CatalogDetailUiState> =
        combine(_catalogDetailId, _catalogDetailRetry) { id, _ -> id }
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf<CatalogDetailUiState>(CatalogDetailUiState.Hidden)
                } else {
                    flow {
                        emit(CatalogDetailUiState.Loading)
                        emit(catalogRepository.detail(id).toUiState())
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogDetailUiState.Hidden)

    private val boardId = MutableStateFlow<String?>(null)

    // P1-1 "add another sample" (#132): when set, submit() bypasses reuse so a 2nd sample of the same
    // catalog ref is created. Set per-entry in bind(); plain field (not flow) — only read at submit.
    private var forceNew = false
    private val _editingTeaId = MutableStateFlow<String?>(null)
    val editingTeaId: StateFlow<String?> = _editingTeaId.asStateFlow()

    /**
     * True when the current add-mode form would create a NEW sample whose name already exists in the
     * collection — decision #132's non-blocking dedup suggestion. The form still saves an independent
     * sample (custom adds never auto-merge, UX3-P0-1); this only surfaces a hint so an accidental
     * re-type is visible. False in edit mode, for "add another sample" (forceNew), and while blank.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val duplicateNameHint: StateFlow<Boolean> =
        combine(_form, _editingTeaId) { form, editing -> form to editing }
            // Only identity fields decide the hint — collapse edits to notes/flavor/purchases so
            // typing an unrelated field doesn't re-run the lookup during the debounce window.
            .distinctUntilChanged { old, new ->
                old.second == new.second &&
                    old.first.nameRu == new.first.nameRu &&
                    old.first.nameEn == new.first.nameEn &&
                    old.first.pinyin == new.first.pinyin &&
                    old.first.nameZh == new.first.nameZh &&
                    old.first.catalogTeaId == new.first.catalogTeaId
            }
            .debounce(CATALOG_SEARCH_DEBOUNCE_MS)
            .mapLatest { (form, editing) ->
                editing == null && !forceNew && form.isValid && repository.wouldDuplicateName(form.toTea())
            }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * How many boards the edited user-tea currently sits on; drives the
     * "Изменения видны во всех подборках" caption (shown when > 1, decisions.md #42). Zero in
     * add mode and while the load is in flight.
     */
    private val _placementCount = MutableStateFlow(0)
    val placementCount: StateFlow<Int> = _placementCount.asStateFlow()

    /**
     * Add-mode draft list of pending photos: the picker ran but the tea row does not exist yet,
     * so we cannot copy the bytes through `PhotoStore.addPhoto(teaId, …)` — we stage the URIs
     * here and `submit()` materializes them after the tea is saved (one-by-one; a copy failure
     * is logged + skipped, the tea is still saved). Empty in edit mode.
     */
    private val _draftPhotos = MutableStateFlow<List<DraftPhoto>>(emptyList())
    val draftPhotos: StateFlow<List<DraftPhoto>> = _draftPhotos.asStateFlow()

    /**
     * Photos visible on the strip. In edit mode this projects from a reactive single-tea Room flow
     * (so a successful `addPhoto`/`removePhoto`/`reorderPhotos` immediately shows up — even for a tea
     * with zero board placements, which never appears in `repository.boards`, review 2026-06-19); in
     * add mode it projects from [_draftPhotos] using `DraftPhoto.id` as the strip id.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val photos: StateFlow<List<TeaPhoto>> = _editingTeaId
        .flatMapLatest { id ->
            if (id == null) {
                _draftPhotos.mapLatest { drafts -> drafts.mapIndexed { i, d -> d.asTeaPhoto(i) } }
            } else {
                repository.observeTea(id).mapLatest { it?.photos.orEmpty() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Binds the form. Always resets first so a recomposition with new ids cannot leak stale
     * state from a previous binding (the VM is reused across navigations under the host
     * activity's `viewModelStore`). The tea load is async — the form stays empty until the
     * load resolves and we double-check the binding hasn't changed in between.
     */
    /** The entry whose form is currently bound; guards against a recreation-triggered reset (N5). */
    private var lastEntryToken: String? = null

    fun bind(
        boardId: String? = null,
        teaId: String? = null,
        catalogTeaId: Long? = null,
        forceNew: Boolean = false,
        entryToken: String = UUID.randomUUID().toString(),
    ) {
        // Preserve the in-progress form across a config change / recreation (review N5). The screen
        // passes a STABLE rememberSaveable token per Add/Edit entry, so a re-fired bind() with the same
        // token (a rotation — the ViewModel itself survived) is a NO-OP and the typed name/notes/flavors
        // are kept. A genuinely new entry (or a changed binding) carries a fresh token and resets. After
        // a process death the token is restored but the ViewModel is fresh (lastEntryToken == null), so
        // it re-binds — edit mode reloads from teaId; an in-progress ADD is reset (acceptable, rare).
        if (entryToken == lastEntryToken) return
        lastEntryToken = entryToken
        this.forceNew = forceNew
        this.boardId.value = boardId
        _editingTeaId.value = teaId
        _form.value = AddTeaForm()
        pristineForm = AddTeaForm()
        _placementCount.value = 0
        _draftPhotos.value = emptyList()
        _catalogQuery.value = ""
        _catalogDetailId.value = null
        _formLoading.value = teaId != null // edit mode loads async below; add mode is ready immediately
        // A genuinely new entry (fresh token past the early-return above): abort any scan from the
        // previous binding so its Review dialog can't pop onto — and merge into — this session (UX3-P1-5).
        cancelScan()
        if (teaId != null) {
            // The entry this load belongs to. The VM is reused across Add/Edit entries, so guard on the
            // token (not just teaId): two binds to the SAME tea id must still supersede each other, else
            // a slow stale load could overwrite edits the user already made after a faster rebind.
            val boundToken = lastEntryToken
            viewModelScope.launch {
                val result = runCatching {
                    val tea = repository.tea(teaId)
                    val count = repository.placementCountForTea(teaId)
                    (tea?.toForm() ?: AddTeaForm()) to count
                }
                // A newer bind() superseded this load — it owns _formLoading now, so leave it alone.
                if (lastEntryToken != boundToken) return@launch
                result
                    .onSuccess { (loaded, count) ->
                        _form.value = loaded
                        pristineForm = loaded
                        _placementCount.value = count
                    }
                    // A Room read error must not strand a full-screen spinner (review): clear it below,
                    // leaving the blank form navigable, and explain via a snackbar.
                    .onFailure { eventHost.emit(ShowSnackbar(R.string.error_generic)) }
                _formLoading.value = false
            }
        } else if (catalogTeaId != null) {
            // Entered from catalog browse (#42 follow-up): prefill from the chosen catalog tea, exactly
            // like picking a search result. The route can only carry the id, so re-fetch its detail. A
            // failed fetch leaves the form empty + explains via a snackbar (the user can still type the
            // tea). Guard against a stale re-bind racing this async fetch.
            viewModelScope.launch {
                when (val result = catalogRepository.detail(catalogTeaId)) {
                    is CatalogDetailResult.Loaded ->
                        if (this@AddTeaViewModel.boardId.value == boardId && _editingTeaId.value == null) {
                            pickCatalogTea(result.detail.toCatalogTea())
                        }
                    is CatalogDetailResult.Retracted ->
                        eventHost.emit(ShowSnackbar(R.string.catalog_detail_withdrawn))
                    CatalogDetailResult.Offline ->
                        eventHost.emit(ShowSnackbar(R.string.catalog_search_offline))
                    CatalogDetailResult.RateLimited ->
                        eventHost.emit(ShowSnackbar(R.string.catalog_detail_rate_limited))
                    CatalogDetailResult.Error ->
                        eventHost.emit(ShowSnackbar(R.string.error_generic))
                }
            }
        }
    }

    /** Tiers of the bound board for the optional tier picker; empty until the board loads. */
    val tiers: StateFlow<List<Tier>> = combine(repository.boards, boardId) { boards, id ->
        id?.let { boards.firstOrNull { board -> board.id == it } }
            ?.tiers
            ?.sortedBy { it.position }
            .orEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun update(transform: (AddTeaForm) -> AddTeaForm) {
        _form.update(transform)
    }

    /**
     * Opt-in packaging scan (slice 3, decision #100): reads + downscales the picked/captured image,
     * sends it to the OCR sidecar, and — on success — surfaces the recognized text for the user to
     * review/edit ([ScanUiState.Review]) before it becomes `sourceText`. Failures clear the spinner
     * and explain via a snackbar; the image is never persisted locally.
     */
    fun scanLabel(uri: Uri) {
        if (_scan.value == ScanUiState.Recognizing) return // a scan is already in flight
        scanJob = viewModelScope.launch {
            _scan.value = ScanUiState.Recognizing
            val bytes = imageReader.read(uri)
            if (bytes == null) {
                eventHost.emit(ShowSnackbar(R.string.ocr_error))
                _scan.value = ScanUiState.Idle
                return@launch
            }
            when (val result = catalogRepository.ocr(bytes)) {
                is OcrResult.Recognized ->
                    // Prefill the review with the dictionary-corrected text (decision #125) — a cleaner
                    // start for the user, which then becomes sourceText -> enrichment context.
                    if (result.corrected.isBlank()) {
                        eventHost.emit(ShowSnackbar(R.string.ocr_no_text))
                        _scan.value = ScanUiState.Idle
                    } else {
                        _scan.value = ScanUiState.Review(result.corrected)
                    }
                OcrResult.Offline -> failScan(R.string.ocr_offline)
                OcrResult.TooLarge -> failScan(R.string.ocr_image_too_large)
                OcrResult.UnreadableImage -> failScan(R.string.ocr_unreadable_image)
                OcrResult.Unavailable -> failScan(R.string.ocr_unavailable)
                OcrResult.RateLimited -> failScan(R.string.ocr_rate_limited)
                OcrResult.Error -> failScan(R.string.ocr_error)
            }
        }
    }

    private fun failScan(messageRes: Int) {
        eventHost.emit(ShowSnackbar(messageRes))
        _scan.value = ScanUiState.Idle
    }

    /**
     * Confirms the reviewed scan text into the `sourceText` field (appending to anything already
     * there, capped at [SourceTextMaxLength]), then closes the review.
     */
    fun applyScannedText(text: String) {
        val cleaned = text.trim()
        if (cleaned.isNotEmpty()) {
            // `truncated` is read once after update{} converges, not per CAS attempt — the emit itself
            // stays outside the lambda (update{}'s transform may run more than once on contention, and
            // emit() enqueuing to a channel is an observable side effect that must fire exactly once,
            // unlike pickCatalogTea's own snackbar in this same file; post-merge review).
            var truncated = false
            _form.update { form ->
                val merged = if (form.sourceText.isBlank()) cleaned else "${form.sourceText.trimEnd()}\n$cleaned"
                truncated = merged.length > SourceTextMaxLength
                form.copy(sourceText = merged.take(SourceTextMaxLength))
            }
            // UX2-P2-14: the merge used to truncate silently — tell the user their scanned text was
            // cut off instead of leaving them to notice the field just looks incomplete.
            if (truncated) {
                eventHost.emit(ShowSnackbar(R.string.source_text_truncated))
            }
        }
        _scan.value = ScanUiState.Idle
    }

    /**
     * Returns the scan flow to Idle: cancels an in-flight recognition (UX3-P1-6 — the Recognizing
     * button doubles as a Cancel) and dismisses a pending review without applying its text. Also
     * called from [bind] so a scan never leaks into a differently-bound Add/Edit session (UX3-P1-5).
     */
    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _scan.value = ScanUiState.Idle
    }

    fun setFlavor(dimension: FlavorDimension, intensity: Int) {
        _form.update { it.copy(flavors = it.flavors + (dimension to intensity)) }
    }

    /** Updates the catalog search query; the [catalogSearch] flow debounces + runs the search. */
    fun onCatalogQuery(query: String) {
        _catalogQuery.value = query
    }

    /** Clears the catalog search box (e.g. the user dismisses results without picking). */
    fun clearCatalogSearch() {
        _catalogQuery.value = ""
    }

    /**
     * Search-miss escape hatch (#62): the catalog doesn't know this tea yet, so carry the typed
     * query straight into the name field instead of dead-ending. Only the name is implied by the
     * CTA, so other fields stay untouched. Arms name focus so the user lands inside the form.
     */
    fun addManuallyFromQuery() {
        val query = _catalogQuery.value.trim()
        if (query.isNotEmpty()) {
            _form.update { it.copy(nameRu = query) }
        }
        pendingNameFocus = true
        _catalogQuery.value = ""
    }

    /**
     * Prefills the form from a chosen catalog tea (names + type + origin) and collapses the search.
     * The values are editable suggestions, never authoritative (#21) — the user owns their board.
     * Flavors/blurb need the detail endpoint and stay for a later M3 slice; this is search + prefill.
     */
    fun pickCatalogTea(tea: CatalogTea) {
        val previous = _form.value
        // UX2-P1-8: this VM is reused across navigations, so bind() may re-bind it to a DIFFERENT
        // entry before the Undo snackbar times out. Capture the entry it belongs to and only restore
        // `previous` if that entry is still the one bound — otherwise Undo would stomp a different
        // Add/Edit session with this one's stale snapshot.
        val entryTokenAtPick = lastEntryToken
        _form.update { form ->
            form.copy(
                nameRu = tea.nameRu ?: tea.displayName,
                // Keep a typed name when the catalog lacks that locale rather than blanking it — a
                // ru-only catalog entry must not erase a "Dragon Well" the user already typed.
                nameEn = tea.nameEn?.takeUnless { it.isBlank() } ?: form.nameEn,
                pinyin = tea.pinyin?.takeUnless { it.isBlank() } ?: form.pinyin,
                nameZh = tea.nameZh?.takeUnless { it.isBlank() } ?: form.nameZh,
                type = tea.type,
                origin = tea.originCountry ?: form.origin,
                catalogTeaId = tea.id,
            )
        }
        _catalogQuery.value = ""
        // The pick overwrites the form (names/type/origin); offer an Undo so it is never a silent,
        // unrecoverable replace of what the user had typed.
        eventHost.emit(
            ShowSnackbar(
                R.string.add_tea_catalog_applied,
                R.string.action_undo,
                onAction = { if (lastEntryToken == entryTokenAtPick) _form.value = previous },
            ),
        )
    }

    private fun CatalogSearchResult.toUiState(): CatalogSearchUiState = when (this) {
        is CatalogSearchResult.Loaded ->
            if (teas.isEmpty()) CatalogSearchUiState.Empty
            else CatalogSearchUiState.Results(teas, fromCache)
        CatalogSearchResult.Offline -> CatalogSearchUiState.Offline
        CatalogSearchResult.RateLimited -> CatalogSearchUiState.RateLimited
        CatalogSearchResult.Error -> CatalogSearchUiState.Error
    }

    /** Opens the detail sheet for a catalog tea id; the [catalogDetail] flow fetches and shows it. */
    fun openCatalogDetail(id: Long) {
        _catalogDetailId.value = id
    }

    /** Closes the detail sheet without picking. */
    fun closeCatalogDetail() {
        _catalogDetailId.value = null
    }

    /** Re-runs the detail fetch for the currently open tea (after an offline/error state). */
    fun retryCatalogDetail() {
        _catalogDetailRetry.update { it + 1 }
    }

    /** Prefills the form from the opened detail and closes the sheet (and the search). */
    fun useCatalogDetail(detail: CatalogTeaDetail) {
        pickCatalogTea(detail.toCatalogTea())
        // toCatalogTea narrows to the search shape (no harvest year); carry the detail's over directly
        // so a catalog tea's harvest year prefills the form instead of being silently dropped.
        detail.harvestYear?.let { year -> _form.update { it.copy(harvestYear = year.toString()) } }
        // toCatalogTea also narrows origin to the bare country; carry the detail's region-preferring
        // origin (region ?: country) so the form keeps the more specific "where from" the sheet showed.
        detail.origin?.let { origin -> _form.update { it.copy(origin = origin) } }
        _catalogDetailId.value = null
    }

    private fun CatalogDetailResult.toUiState(): CatalogDetailUiState = when (this) {
        is CatalogDetailResult.Loaded -> CatalogDetailUiState.Loaded(detail)
        is CatalogDetailResult.Retracted -> CatalogDetailUiState.Withdrawn
        CatalogDetailResult.Offline -> CatalogDetailUiState.Offline
        CatalogDetailResult.RateLimited -> CatalogDetailUiState.RateLimited
        CatalogDetailResult.Error -> CatalogDetailUiState.Error
    }

    fun addPurchase() {
        _form.update { it.copy(purchases = it.purchases + PurchaseDraft()) }
    }

    fun updatePurchase(index: Int, transform: (PurchaseDraft) -> PurchaseDraft) {
        _form.update { form ->
            if (index !in form.purchases.indices) return@update form
            form.copy(purchases = form.purchases.toMutableList().also { it[index] = transform(it[index]) })
        }
    }

    fun removePurchase(index: Int) {
        _form.update { form ->
            if (index !in form.purchases.indices) return@update form
            form.copy(purchases = form.purchases.toMutableList().also { it.removeAt(index) })
        }
    }

    /**
     * Persists the tea (insert in add mode, in-place update in edit mode), then [onSaved].
     * Invalid forms surface a snackbar + arm focus-on-error so the user knows why nothing
     * happened (#43 polish lane 2). Repository failures surface a generic snackbar and leave
     * the form intact so the user can retry without re-typing.
     */
    fun submit(onSaved: (photoFailure: PhotoSaveFailure?) -> Unit) {
        val form = _form.value
        if (!form.isValid) {
            pendingNameFocus = true
            eventHost.emit(ShowSnackbar(R.string.add_tea_error_name_required))
            return
        }
        // Re-entrancy guard (UX-P0-1): a double-tap on Save must not launch a second save while the first
        // is in flight — that races the resolve-or-create dedup in addTea and can create a duplicate tea.
        // Set synchronously (before the launch) so a second tap on the same frame sees it.
        if (_isSaving.value) return
        _isSaving.value = true
        val editing = _editingTeaId.value
        val board = boardId.value
        viewModelScope.launch {
            try {
                if (editing != null) {
                    // pristineForm is the as-loaded snapshot (UX2-P0-1): only a field the user actually
                    // changed should win over whatever a concurrent enrichment patch wrote in the meantime.
                    val saved = runCatching { repository.updateTea(editing, form.toTea(), pristineForm.toTea()); true }
                        .getOrElse { eventHost.emit(ShowSnackbar(R.string.error_generic)); false }
                    if (saved) onSaved(null)
                    return@launch
                }
                if (board == null) return@launch
                val tea = form.toTea()
                // Only the insert is guarded for TOTAL failure (UX-P2-13): once the tea row exists the save
                // has succeeded, so a later photo-copy or enrichment error must not report the whole save as
                // failed — that would make the user retry and duplicate the tea.
                val added = runCatching { repository.addTea(board, tea, form.tierId, forceNew) }
                    .getOrElse { eventHost.emit(ShowSnackbar(R.string.error_generic)); null }
                    ?: return@launch
                var failedPhotos = 0
                var firstFailureRes: Int? = null
                // A failed copy is counted (the save still succeeds — the tea row is already in DB); the
                // screen surfaces the total + reason on the destination it pops to, so the message is not
                // lost when this screen (and its snackbar host) is torn down on navigation.
                _draftPhotos.value.forEach { draft ->
                    val result = repository.addPhoto(added.teaId, draft.uri)
                    if (result !is AddPhotoResult.Added) {
                        failedPhotos++
                        if (firstFailureRes == null) firstFailureRes = result.failureMessageRes()
                    }
                }
                _draftPhotos.value = emptyList()
                // Optimistic background enrichment (#21): only for a genuinely new tea that is NOT already
                // catalog-linked (a catalog pick carries its names + id, so a re-resolve is redundant).
                // Never an auto-linked existing one. Fire-and-forget on the app scope. A pasted vendor blurb
                // (#25) grounds the profile; sent once, never stored in Room.
                if (added.created && tea.catalogTeaId == null) {
                    enrichmentManager.enrich(added.teaId, tea.displayName, form.sourceText.trim().ifBlank { null })
                }
                onSaved(firstFailureRes?.let { PhotoSaveFailure(failedPhotos, it) })
            } finally {
                _isSaving.value = false
            }
        }
    }

    /**
     * Stages a picked photo. In edit mode the bytes are copied + a row inserted right away so
     * the strip shows it immediately; in add mode it joins the draft list and waits for save.
     * A copy failure (e.g. revoked URI permission) surfaces as a snackbar.
     */
    fun onAddPhoto(uri: Uri) {
        val editing = _editingTeaId.value
        if (editing != null) {
            viewModelScope.launch {
                val result = runCatching { repository.addPhoto(editing, uri) }.getOrElse { AddPhotoResult.Failed }
                if (result !is AddPhotoResult.Added) {
                    eventHost.emit(ShowSnackbar(result.failureMessageRes()))
                }
            }
        } else {
            _draftPhotos.update { it + DraftPhoto(id = "draft-${UUID.randomUUID()}", uri = uri) }
        }
    }

    /** Removes one strip photo. In add mode this just drops the draft entry. */
    fun onRemovePhoto(photoId: String) {
        val editing = _editingTeaId.value
        if (editing != null) {
            viewModelScope.launch {
                runCatching { repository.removePhoto(editing, photoId) }
                    .onFailure { eventHost.emit(ShowSnackbar(R.string.error_generic)) }
            }
        } else {
            _draftPhotos.update { drafts -> drafts.filterNot { it.id == photoId } }
        }
    }

    /** Reorders the photo strip to match [orderedPhotoIds]. */
    fun onReorderPhotos(orderedPhotoIds: List<String>) {
        val editing = _editingTeaId.value
        if (editing != null) {
            viewModelScope.launch {
                runCatching { repository.reorderPhotos(editing, orderedPhotoIds) }
                    .onFailure { eventHost.emit(ShowSnackbar(R.string.error_generic)) }
            }
        } else {
            _draftPhotos.update { drafts ->
                val byId = drafts.associateBy(DraftPhoto::id)
                val ordered = orderedPhotoIds.mapNotNull(byId::get)
                ordered + drafts.filterNot { it.id in orderedPhotoIds.toSet() }
            }
        }
    }

    /**
     * Deletes the user-tea everywhere from the edit screen — every board the tea sits on loses its
     * placement (FK cascade). Destructive; the screen gates it behind a confirm dialog.
     *
     * No Undo snackbar here (UX3-P2-29, parity with [TeaDetailViewModel.deleteTea]): this path pops
     * back on success, so an in-place snackbar would vanish with the screen. The confirm dialog is the
     * deliberate safety net for this entry point; the in-place board delete offers Undo instead.
     */
    fun deleteTea(onDeleted: () -> Unit) {
        val editing = _editingTeaId.value ?: return
        viewModelScope.launch {
            val ok = runCatching { repository.deleteTea(editing); true }
                .getOrElse { eventHost.emit(ShowSnackbar(R.string.error_generic)); false }
            if (ok) onDeleted()
        }
    }
}

/**
 * One pending add-mode photo. The id is a stable UI handle for drag-reorder; the URI is what the
 * picker handed us and what `PhotoStore` will copy in once the tea row exists.
 */
data class DraftPhoto(val id: String, val uri: Uri) {
    fun asTeaPhoto(index: Int): TeaPhoto = TeaPhoto(
        id = id,
        uri = uri.toString(),
        position = index,
        source = PhotoSource.USER,
    )
}
