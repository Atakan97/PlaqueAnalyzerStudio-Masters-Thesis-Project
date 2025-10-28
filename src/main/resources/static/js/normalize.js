document.addEventListener('DOMContentLoaded', () => {
    // UI elements and state
    const elapsedEl   = document.getElementById('elapsed');
    const attemptEl   = document.getElementById('attemptCount');
    const addTableBtn = document.getElementById('addTable');
    const relationsContainer = document.getElementById('decomposedRelationsContainer');
    const decContainer= document.getElementById('decomposedTablesContainer');
    const relationMetaMap = new Map();


    const IS_RESTORE_MODE = Array.isArray(window.currentRelationsColumns) && window.currentRelationsColumns.length > 0;

    // Get session start time (if not available, use current time)
    const SESSION_START_TIME_MS = window.normalizationStartTimeMs || Date.now();

    // Compute plaque and continue to normalization buttons
    const computeAllBtn = document.getElementById('computeAllBtn');
    const autoComputeEnabled = computeAllBtn && computeAllBtn.dataset.autoCompute === 'true';

    window.isNormalizationAutoComputeEnabled = () => autoComputeEnabled;
    const continueNormalizationBtn = document.getElementById('continueNormalizationBtn');
    const showBcnfTablesBtn = document.getElementById('showBcnfTablesBtn');
    const decompositionFinishedBtn = document.getElementById('decompositionFinishedBtn');

    const changeDecompositionBtn = document.getElementById('changeDecompositionBtn');

    let raw = window.originalTable;
    if (typeof raw === 'string' && raw.trim()) {
        try { raw = JSON.parse(raw); } catch (e) { console.error("originalTable JSON parse error", e); raw = []; }
    }
    const originalRows = Array.isArray(raw) ? raw : [];

    let ricRaw = window.ricMatrixTable;
    if (typeof ricRaw === 'string' && ricRaw.trim()) {
        try { ricRaw = JSON.parse(ricRaw); } catch (e) { ricRaw = []; }
    }
    const ricMatrix = Array.isArray(ricRaw) ? ricRaw : [];

    const alreadyBcnf = Boolean(window.alreadyBcnf);
    if (alreadyBcnf) {
        if (addTableBtn) addTableBtn.style.display = 'none';
        if (decompositionFinishedBtn) decompositionFinishedBtn.style.display = 'none';
        if (changeDecompositionBtn) changeDecompositionBtn.style.display = 'none';
        if (computeAllBtn) computeAllBtn.style.display = 'none';
        if (continueNormalizationBtn) continueNormalizationBtn.style.display = 'none';
        if (showBcnfTablesBtn) showBcnfTablesBtn.style.display = 'inline-block';

        const manualRows = originalRows.map(row => Array.isArray(row) ? row.join(',') : '').filter(Boolean);
        const manualDataString = manualRows.join(';');
        const columnIndices = originalRows[0] ? originalRows[0].map((_, idx) => idx) : [];
        const ricMatrixCopy = Array.isArray(ricMatrix) ? ricMatrix : [];

        window._lastBcnfMeta = {
            attempts: 0,
            elapsed: 0
        };

        window._alreadyBcnfPayload = {
            columnsPerTable: [columnIndices],
            manualPerTable: [manualDataString],
            fdsPerTable: [''],
            fdsPerTableOriginal: [''],
            ricPerTable: [ricMatrixCopy],
            globalRic: ricMatrixCopy,
            unionCols: columnIndices,
            originalTable: manualDataString,
            originalRic: ricMatrixCopy
        };

        const infoMessage = 'There is nothing to normalize since this table is already in BCNF.';
        Swal.fire({
            icon: 'info',
            title: 'Already in BCNF',
            text: infoMessage,
            confirmButtonText: 'OK'
        });
    }

    const timeLimitInput     = document.getElementById('timeLimit');
    const mcCheckboxInput    = document.getElementById('mcCheckbox');
    const mcSamplesInput     = document.getElementById('samples');

    // Functional dependencies transitive closure calculation logic
    const showClosureNormBtn = document.getElementById('showClosureNormBtn');
    const fdListUlNorm = document.getElementById('fdListUl');

    // Data from Thymeleaf
    const transitiveFdsArrayNorm = window.transitiveFdsArray || [];
    const fdItemsNorm = window.fdItems || [];
    // Available red FDs
    const fdInferredNorm = new Set(window.fdInferred || []);

    function normalizeWindowArray(winVal) {
        if (winVal == null) return [];
        if (Array.isArray(winVal)) return winVal;
        if (typeof winVal === 'string') {
            const trimmed = winVal.trim();
            if (!trimmed) return [];
            if ((trimmed.startsWith('[') && trimmed.endsWith(']')) || trimmed.startsWith('{')) {
                try {
                    const parsed = JSON.parse(trimmed);
                    if (Array.isArray(parsed)) return parsed;
                } catch (e) {
                    // fallback below
                }
            }
            return [trimmed];
        }
        return [];
    }

    function normalizeManualEntry(value) {
        if (value == null) return '';
        if (Array.isArray(value)) {
            if (value.length > 0 && Array.isArray(value[0])) {
                return value
                    .map(row => row.map(cell => (cell == null ? '' : String(cell).trim())).join(','))
                    .filter(str => str.trim().length > 0)
                    .join(';');
            }
            return value
                .map(item => normalizeManualEntry(item))
                .filter(str => str && str.trim().length > 0)
                .join(';');
        }

        if (typeof value === 'string') {
            const trimmed = value.trim();
            if (!trimmed) return '';
            if ((trimmed.startsWith('[') && trimmed.endsWith(']')) || trimmed.startsWith('{')) {
                try {
                    const parsed = JSON.parse(trimmed);
                    return normalizeManualEntry(parsed);
                } catch (e) {
                    /* fall through */
                }
            }
            return trimmed;
        }

        return String(value).trim();
    }

    function normalizeToStringList(value) {
        if (value == null) return [];
        if (Array.isArray(value)) {
            return value
                .map(item => normalizeToStringList(item))
                .flat()
                .filter(Boolean);
        }
        if (typeof value === 'string') {
            const trimmed = value.trim();
            if (!trimmed) return [];
            if ((trimmed.startsWith('[') && trimmed.endsWith(']')) || trimmed.startsWith('{')) {
                try {
                    const parsed = JSON.parse(trimmed);
                    if (Array.isArray(parsed)) {
                        return normalizeToStringList(parsed);
                    }
                } catch (e) {
                    // ignore JSON parse errors and fall back to splitting
                }
            }
            return trimmed.split(/[;\r\n]+/).map(s => s.trim()).filter(Boolean);
        }
        const asString = String(value).trim();
        return asString ? [asString] : [];
    }

    // Appends FDs to the existing list with coloring
    function appendTransitiveFdsNorm(fdsToAppend) {
        const existingFds = new Set();
        Array.from(fdListUlNorm.children).forEach(li => {
            // Collect existing original FDs from the list
            existingFds.add(li.textContent.trim());
        });

        const fragment = document.createDocumentFragment();
        fdsToAppend.forEach(fd => {
            // Only add what is not in the original list or what is not yet added to the current list
            if (existingFds.has(fd)) {
                return;
            }
            const li = document.createElement('li');
            li.textContent = fd;
            li.classList.add('inferred');
            fragment.appendChild(li);
        });
        fdListUlNorm.appendChild(fragment);
    }

    if (showClosureNormBtn && fdListUlNorm) {
        showClosureNormBtn.addEventListener('click', () => {
            // Disable the button as soon as the process starts
            if (showClosureNormBtn.disabled) return;
            showClosureNormBtn.disabled = true;
            // Add FDs to the list
            appendTransitiveFdsNorm(transitiveFdsArrayNorm);
            // Remove the button from the page completely
            showClosureNormBtn.remove();
        });
    }

    // Render original table with RIC coloring
    const origContainer = document.getElementById('originalTableContainer');
    const origTable     = document.createElement('table');
    origTable.classList.add('data-grid');
    origTable.id = 'normalizationOrigTable';
    origTable.style.tableLayout = 'fixed';
    origTable.style.width = '100%';

    // Building header
    const colCount = originalRows[0]?.length || 0;
    const origThead = origTable.createTHead();
    const origHeadRow = origThead.insertRow();
    for (let i = 0; i < colCount; i++) {
        const th = document.createElement('th');
        th.textContent = (i + 1).toString();
        th.dataset.origIdx = i;
        th.setAttribute('data-orig-idx', String(i)); // robust attribute for parsing
        origHeadRow.appendChild(th);
    }
    const origTbody = origTable.createTBody();

    const getPlaqueColorFn = typeof window.getPlaqueColor === 'function'
        ? window.getPlaqueColor
        : function getPlaqueColor(value) {
            const darkness = Math.max(0, Math.min(1, 1 - value));
            const lightness = 85 - 55 * darkness;
            return `hsl(220, 85%, ${lightness}%)`;
        };

    function renderOrigBody(data) {
        origTbody.innerHTML = '';
        data.forEach((row, r) => {
            const tr = origTbody.insertRow();
            row.forEach((val, c) => {
                const td = tr.insertCell();
                td.textContent = val;
                const ricVal = parseFloat(ricMatrix[r]?.[c]);
                if (!isNaN(ricVal) && ricVal < 1) {
                    td.classList.add('plaque-cell');
                    td.style.backgroundColor = getPlaqueColorFn(ricVal);
                    if (ricVal < 0.5) {
                        td.classList.add('plaque-light-text');
                    } else {
                        td.classList.remove('plaque-light-text');
                    }
                } else {
                    td.style.backgroundColor = '';
                    td.classList.remove('plaque-light-text');
                }
                td.dataset.origIdx = c;
            });
        });
    }

    renderOrigBody(originalRows);
    if (origContainer) origContainer.appendChild(origTable);

    function applyRicColoring(tableEl) {
        if (!tableEl || !ricMatrix || !ricMatrix.length) return;
        const tbody = tableEl.tBodies[0];
        if (!tbody) return;
        Array.from(tbody.rows).forEach((tr, rowIdx) => {
            const ricRow = ricMatrix[rowIdx];
            if (!Array.isArray(ricRow)) return;
            Array.from(tr.cells).forEach((td, colIdx) => {
                const rawVal = ricRow[colIdx];
                const val = rawVal == null ? NaN : parseFloat(rawVal);
                if (!isNaN(val) && val >= 0 && val < 1) {
                    td.style.backgroundColor = getPlaqueColorFn(val);
                    td.classList.add('plaque-cell');
                    if (val < 0.5) {
                        td.classList.add('plaque-light-text');
                    } else {
                        td.classList.remove('plaque-light-text');
                    }
                } else {
                    td.style.backgroundColor = '';
                    td.classList.remove('plaque-cell');
                    td.classList.remove('plaque-light-text');
                }
            });
        });
    }

    const origRicTable = document.createElement('table');
    origRicTable.classList.add('data-grid');
    origRicTable.id = 'normalizationRicTable';
    origRicTable.style.display = 'none';

    if (colCount) {
        const ricThead = origRicTable.createTHead();
        const ricHeadRow = ricThead.insertRow();
        for (let i = 0; i < colCount; i++) {
            const th = document.createElement('th');
            th.textContent = (i + 1).toString();
            ricHeadRow.appendChild(th);
        }
    }
    const ricTbody = origRicTable.createTBody();
    originalRows.forEach((row, r) => {
        const tr = ricTbody.insertRow();
        row.forEach((_, c) => {
            const td = tr.insertCell();
            const ricVal = ricMatrix[r]?.[c];
            td.textContent = ricVal != null ? ricVal : '';
        });
    });

    if (origContainer) origContainer.appendChild(origRicTable);
    applyRicColoring(origRicTable);

    const originalShowRicBtn = document.getElementById('originalShowRicBtn');
    const originalReturnBtn = document.getElementById('originalReturnBtn');

    if (originalShowRicBtn && originalReturnBtn) {
        if (ricMatrix && ricMatrix.length) {
            originalShowRicBtn.style.display = 'inline-block';
        }
        originalShowRicBtn.addEventListener('click', () => {
            origTable.style.display = 'none';
            origRicTable.style.display = 'table';
            originalShowRicBtn.style.display = 'none';
            originalReturnBtn.style.display = 'inline-block';
            applyRicColoring(origRicTable);
        });
        originalReturnBtn.addEventListener('click', () => {
            origTable.style.display = 'table';
            origRicTable.style.display = 'none';
            originalShowRicBtn.style.display = 'inline-block';
            originalReturnBtn.style.display = 'none';
        });
    }

    // Parse a single FD string like "1,4->3" or "1,4 -> 3" -> normalized "1,4->3"
    function normalizeFdString(fd) {
        return String(fd).replace(/\s+/g,'').replace(/→/g,'->');
    }

    // Parse FD like "1,4->3" -> {lhs: [0,3], rhs: [2]} (zero-based numbers)
    function parseFdToZeroBased(fdStr) {
        if (!fdStr || !fdStr.trim()) return null;
        fdStr = normalizeFdString(fdStr);
        const parts = fdStr.split('->');
        if (parts.length !== 2) return null;
        const lhs = parts[0].split(',').map(s => s.trim()).filter(Boolean).map(n => Number(n) - 1);
        const rhs = parts[1].split(',').map(s => s.trim()).filter(Boolean).map(n => Number(n) - 1);
        if (lhs.some(isNaN) || rhs.some(isNaN)) return null;
        return { lhs, rhs };
    }

    // fdZero is object from parseFdToZeroBased, colsZeroBased is array like [0,2,3]
    // Returns remapped FD string in local 1-based form like "1,3->2" or null if FD not applicable
    function remapFdToLocal(fdZeroObj, colsZeroBased) {
        if (!fdZeroObj || !Array.isArray(colsZeroBased)) return null;
        // Build lookup: origZero -> localIndex(1-based)
        const map = new Map();
        for (let i = 0; i < colsZeroBased.length; i++) {
            map.set(Number(colsZeroBased[i]), i + 1); // local numbers are 1-based
        }
        // Check all referenced attributes are inside map
        for (const x of [...fdZeroObj.lhs, ...fdZeroObj.rhs]) {
            if (!map.has(Number(x))) return null; // Not applicable
        }
        const lhsLocal = fdZeroObj.lhs.map(x => map.get(Number(x)));
        const rhsLocal = fdZeroObj.rhs.map(x => map.get(Number(x)));
        return lhsLocal.join(',') + '->' + rhsLocal.join(',');
    }

    // Parse FD text (semicolon/newline separated) -> array of normalized strings ["1,4->3", ...]
    function splitFdsText(fdsText) {
        if (!fdsText) return [];
        if (Array.isArray(fdsText)) return fdsText.map(String).filter(Boolean).map(normalizeFdString);
        return String(fdsText).split(/[;\r\n]+/).map(s => s.trim()).filter(Boolean).map(normalizeFdString);
    }


    // Compute RIC for a single wrapper
    async function computeRicForWrapper(wrapper, baseColumnsOverride = null) {
        if (!wrapper) return;

        // Parse columns array for this wrapper (original zero-based indices)
        let cols = [];
        try { cols = JSON.parse(wrapper.dataset.columns || '[]'); } catch (e) { cols = []; }
        cols = Array.isArray(cols) ? cols.map(Number) : [];

        let baseColumns = Array.isArray(baseColumnsOverride) ? baseColumnsOverride.map(Number) : null;
        if ((!baseColumns || baseColumns.length === 0) && wrapper.dataset.baseColumns) {
            try {
                const parsed = JSON.parse(wrapper.dataset.baseColumns);
                if (Array.isArray(parsed) && parsed.length > 0) {
                    baseColumns = parsed.map(Number);
                }
            } catch (err) {
                baseColumns = null;
            }
        }

        // Check stored manual data (for restored tables)
        const storedManualDataStr = wrapper.dataset.restoredManualData || '';

        let manualData = '';
        let manualRows = [];

        // Determine the set of tuples (manualData)
        if (IS_RESTORE_MODE) {
            // Normalization page, next step
            // originalRows will be empty at this point. Current table's tuples will be used.

            if (storedManualDataStr) {
                // Option A: If this is a *restored* base table, use the stored data
                manualRows = storedManualDataStr.split(';').filter(Boolean).map(s => s.trim());
                manualData = manualRows.join(';');
            }

            // Option B: If it's a new decomposed table or the restored table's columns have changed,
            // read from the tbody, which reflects the most current state.
            if (!manualData) {
                manualRows = Array.from(wrapper.querySelectorAll('tbody tr')).map(tr => {
                    return Array.from(tr.cells).map(td => td.textContent).join(',');
                });
                manualData = manualRows.join(';');
            }

        } else {
            // Step 1
            // Remove tuples from global originalRows
            const seen = new Set();
            originalRows.forEach(row => {
                const tupArr = cols.map(i => (row[i] !== undefined ? String(row[i]) : ''));
                const key = tupArr.join('|');
                if (!seen.has(key)) {
                    seen.add(key);
                    manualRows.push(tupArr.join(','));
                }
            });
            manualData = manualRows.join(';');
        }

        if (manualData.length === 0 && storedManualDataStr) {
            // If the calculation result is empty (and there is restore data), use restore data
            manualData = storedManualDataStr;
            manualRows = storedManualDataStr.split(';').filter(Boolean).map(s => s.trim());
        }

        // Optionally read any displayed/original FDs that might be stored
        let displayedFds = [];
        try { displayedFds = readProjectedFdsFromWrapper(wrapper) || []; } catch (e) { displayedFds = []; }

        // Determine FD strings to send to server (must be local-indexed relative to this wrapper)
        // Original-index style strings if available (e.g. "1,4->3" or "2->3")
        let rawFdsArr = [];
        try {
            // Prefer an explicit original-index store if present
            if (wrapper.dataset.projectedFdsOrig) {
                rawFdsArr = normalizeWindowArray(JSON.parse(wrapper.dataset.projectedFdsOrig));
            } else {
                // readProjectedFdsFromWrapper may return strings in original-index form
                rawFdsArr = readProjectedFdsFromWrapper(wrapper) || [];
            }
        } catch (e) {
            rawFdsArr = readProjectedFdsFromWrapper(wrapper) || [];
        }

        // Normalize into array of strings
        rawFdsArr = Array.isArray(rawFdsArr) ? rawFdsArr.map(String).filter(Boolean) : [];

        // Convert each raw FD (original-indexed) -> zero-based object -> remap to local 1-based
        const localFdsToSend = [];
        rawFdsArr.forEach(fdText => {
            const normalized = normalizeFdString(fdText); // e.g. "1,4->3"
            const zeroObj = parseFdToZeroBased(normalized); // {lhs:[0,..], rhs:[..]} or null
            if (!zeroObj) return;
            const remapped = remapFdToLocal(zeroObj, cols); // "1,3->2" relative to wrapper columns (1-based)
            if (remapped) localFdsToSend.push(remapped);
        });

        // If no FDs available at all, send empty string
        const fdsPayloadString = localFdsToSend.length > 0 ? localFdsToSend.join(';') : '';

        // Build payload (this is the body sent to /normalize/decompose)
        const globalMonteCarlo = mcCheckboxInput ? mcCheckboxInput.checked : false;
        const globalSamples = mcSamplesInput ? parseInt(mcSamplesInput.value || '0', 10) : 0;

        const payload = {
            columns: cols,
            manualData: manualData,
            fds: fdsPayloadString,
            timeLimit: parseInt(timeLimitInput?.value || '30', 10),
            monteCarlo: globalMonteCarlo,
            samples: globalMonteCarlo ? Math.max(globalSamples, 1) : 0
        };
        if (Array.isArray(baseColumns) && baseColumns.length > 0) {
            payload.baseColumns = baseColumns;
        }

        console.log('RIC payload', payload);

        try {
            const resp = await fetch('/normalize/decompose', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            if (!resp.ok) {
                const txt = await resp.text();
                Swal.fire({
                    icon: 'error',
                    title: 'Compute Failed',
                    text: 'Computation for table failed: ' + (txt || resp.statusText),
                    confirmButtonText: 'Kapat'
                });
                return;
            }

            const json = await resp.json();
            console.log('computeRicForWrapper: /normalize/decompose response:', json);

            // Extract RIC matrix
            const ricMatrixSingle = Array.isArray(json.ric) ? json.ric
                : Array.isArray(json.ricMatrix) ? json.ricMatrix
                    : Array.isArray(json.matrix) ? json.matrix
                        : [];

            // Server-projected FDs
            let serverProjected = [];
            if (Array.isArray(json.projectedFDs) && json.projectedFDs.length > 0) serverProjected = json.projectedFDs.slice();
            else if (Array.isArray(json.fds) && json.fds.length > 0) serverProjected = json.fds.slice();
            else if (typeof json.fds === 'string' && json.fds.trim()) serverProjected = splitFdsText(json.fds);

            // Normalize strings
            serverProjected = serverProjected.map(s => String(s).trim()).filter(Boolean).map(s => s.replace(/\s+/g,'').replace(/→/g,'->'));

            // Remap each serverProjected FD into wrapper-local numbering (1-based local)
            const localFds = [];
            serverProjected.forEach(s => {
                const fdZero = parseFdToZeroBased(s); // Returns {lhs:[0..], rhs:[..]} or null
                if (!fdZero) return;
                const rem = remapFdToLocal(fdZero, cols); // Returns "1,3->2" (local 1-based) or null if not applicable
                if (rem) localFds.push(rem);
            });

            // Store both forms on wrapper
            try { wrapper.dataset.projectedFdsOrig = JSON.stringify(serverProjected); } catch (e) {}
            try { wrapper.dataset.projectedFds = JSON.stringify(localFds); } catch (e) {}

            // Persist per-table RIC matrix for later review/summary usage
            try { wrapper.dataset.ricMatrix = JSON.stringify(ricMatrixSingle || []); } catch (e) {}

            // Update FD list in UI, show serverProjected to the user
            try {
                const fdContainer = wrapper.querySelector('.fd-list-container');
                const fdUl = fdContainer ? fdContainer.querySelector('ul') : null;
                if (fdUl) {
                    fdUl.innerHTML = '';
                    const toShow = serverProjected.length > 0 ? serverProjected : localFds;
                    toShow.forEach(s => {
                        const li = document.createElement('li');
                        li.textContent = String(s);
                        fdUl.appendChild(li);
                    });
                }
            } catch (e) {
                console.warn('computeRicForWrapper: failed to render fd list', e);
            }

            // Render wrapper tbody using manualRows and apply plaque coloring with ricMatrixSingle
            const tbody = wrapper.querySelector('table tbody');
            if (tbody) {
                tbody.innerHTML = '';
                for (let r = 0; r < manualRows.length; r++) {
                    const tup = manualRows[r].split(',').map(x => (x == null ? '' : String(x).trim()));
                    const trEl = tbody.insertRow();
                    for (let cIdx = 0; cIdx < cols.length; cIdx++) {
                        const td = trEl.insertCell();
                        td.textContent = tup[cIdx] !== undefined ? tup[cIdx] : '';
                        td.dataset.origIdx = cols[cIdx];

                        // Attempt to read ric val: ricMatrixSingle[r][cIdx]
                        let ricVal = NaN;
                        if (Array.isArray(ricMatrixSingle) && Array.isArray(ricMatrixSingle[r]) && ricMatrixSingle[r][cIdx] !== undefined) {
                            const v = ricMatrixSingle[r][cIdx];
                            const parsed = parseFloat(v);
                            if (!isNaN(parsed)) ricVal = parsed;
                        }

                        if (!isNaN(ricVal) && ricVal < 1) {
                            td.classList.add('plaque-cell');
                            const lightness = 10 + 90 * ricVal;
                            td.style.backgroundColor = `hsl(220,100%,${lightness}%)`;
                        } else {
                            td.style.backgroundColor = 'white';
                            td.classList.remove('plaque-cell');
                        }
                    }
                }
            }

            console.log('computeRicForWrapper: done for wrapper; serverProjected=', serverProjected, 'localFds=', localFds, 'ricMatrix=', ricMatrixSingle);
        } catch (err) {
            console.error('computeRicForWrapper failed', err);
            Swal.fire({
                icon: 'error',
                title: 'Server Error',
                text: 'Server error while computing RIC for table: ' + (err && err.message ? err.message : err),
                confirmButtonText: 'Close'
            });
        }
    }

    function createRelationGroup({ relationId }) {
        const host = (relationsContainer && relationsContainer.classList.contains('restore-layout'))
            ? relationsContainer
            : (decContainer || relationsContainer);
        if (!host) return null;
        const group = document.createElement('div');
        group.classList.add('relation-group');
        group.dataset.relationId = relationId;
        group.style.display = 'flex';
        group.style.flexDirection = 'column';
        group.style.flexWrap = 'nowrap';
        group.style.width = '100%';
        group.style.alignItems = 'stretch';
        group.style.justifyContent = 'stretch';
        group.style.flex = '0 0 auto';
        group.style.gap = '0';

        host.appendChild(group);
        relationMetaMap.set(group, { relationId, baseWrapper: null, localContainer: null });
        return group;
    }

    function ensureLocalContainer(group) {
        const meta = relationMetaMap.get(group);
        if (!meta) return null;
        if (meta.localContainer) return meta.localContainer;
        const container = document.createElement('div');
        container.classList.add('local-decomposition-group');
        container.dataset.relationId = meta.relationId;
        container.style.display = 'flex';
        container.style.flexWrap = 'wrap';
        container.style.paddingTop = '10px';
        container.style.borderTop = '1px dashed #ddd';
        container.style.gap = '20px';
        container.style.width = '100%';
        container.style.alignItems = 'flex-start';
        group.appendChild(container);
        meta.localContainer = container;
        return container;
    }

    function renderRelationBaseWrapper(group, options = {}) {
        const meta = relationMetaMap.get(group);
        if (!meta) return null;

        const {
            columns = [],
            manualData = '',
            fdList = [],
            fdOriginal = [],
            ricMatrix = [],
            displayFds = [],
            relationTitle,
            showFooter = true
        } = options;

        const resolvedDisplayFds = Array.isArray(displayFds) && displayFds.length
            ? displayFds
            : (fdOriginal.length ? fdOriginal : fdList);

        const wrapper = createDecomposedTable({
            origMode: true,
            initialColumns: columns,
            initialManualData: manualData,
            initialFds: resolvedDisplayFds.join(';'),
            initialRicMatrix: ricMatrix,
            parentContainer: group
        });

        if (!wrapper) return null;

        const titleEl = wrapper.querySelector('h3');
        if (titleEl && relationTitle) titleEl.textContent = relationTitle;

        if (fdList.length) {
            try { wrapper.dataset.projectedFds = JSON.stringify(fdList); } catch (e) {}
        }
        if (fdOriginal.length) {
            try { wrapper.dataset.projectedFdsOrig = JSON.stringify(fdOriginal); } catch (e) {}
        } else if (resolvedDisplayFds.length) {
            try { wrapper.dataset.projectedFdsOrig = JSON.stringify(resolvedDisplayFds); } catch (e) {}
        }

        const footer = wrapper.querySelector('.orig-as-decomposed-footer');
        if (footer && !showFooter) footer.style.display = 'none';

        meta.baseWrapper = wrapper;
        return wrapper;
    }

    function renderHistoryRelations() {
        console.groupCollapsed('renderHistoryRelations');
        console.log('raw window.currentRelationsColumns:', window.currentRelationsColumns);
        console.log('raw window.currentRelationsManual:', window.currentRelationsManual);
        console.log('raw window.currentRelationsFds:', window.currentRelationsFds);
        console.log('raw window.currentRelationsFdsOriginal:', window.currentRelationsFdsOriginal);
        console.log('raw window.currentRelationsRic:', window.currentRelationsRic);

        const colsRaw = window.currentRelationsColumns || null;
        const crCols = normalizeWindowArray(colsRaw);
        console.log('normalized columns:', crCols);
        if (!Array.isArray(crCols) || crCols.length === 0) {
            console.warn('renderHistoryRelations: no columns data available, aborting render.');
            console.groupEnd();
            return false;
        }

        const manualRaw = window.currentRelationsManual || null;
        const fdsRaw = window.currentRelationsFds || null;
        const fdsOriginalRaw = window.currentRelationsFdsOriginal || null;
        const ricRaw = window.currentRelationsRic || null;

        const crManual = normalizeWindowArray(manualRaw);
        const crFds = normalizeWindowArray(fdsRaw);
        const crFdsOriginal = normalizeWindowArray(fdsOriginalRaw);
        const crRic = normalizeWindowArray(ricRaw);
        console.log('normalized manual:', crManual);
        console.log('normalized fds:', crFds);
        console.log('normalized fds original:', crFdsOriginal);
        console.log('normalized ric matrices:', crRic);

        const host = relationsContainer || decContainer;
        if (!host) return false;

        relationMetaMap.clear();

        host.innerHTML = '';
        host.classList.add('restore-layout');
        host.style.display = 'flex';
        host.style.flexWrap = 'wrap';
        host.style.gap = '20px';
        host.style.alignItems = 'flex-start';
        host.style.justifyContent = 'flex-start';

        const isRestoreHost = host === relationsContainer;
        console.log('renderHistoryRelations: host prepared', {
            hostId: host?.id,
            isRestoreHost,
            hostClassList: host ? Array.from(host.classList) : [],
            targetChildCount: crCols.length
        });

        if (host !== relationsContainer && relationsContainer) {
            relationsContainer.innerHTML = '';
            relationsContainer.style.display = 'none';
        }
        if (host === relationsContainer && relationsContainer) {
            relationsContainer.style.display = 'flex';
        }
        if (host === relationsContainer && decContainer) {
            decContainer.style.display = 'none';
        }

        const aggregatedFds = [];
        const aggregatedSeen = new Set();
        const appendAggregatedFds = (list) => {
            if (!Array.isArray(list)) return;
            list.forEach(fd => {
                const normalized = typeof fd === 'string' ? fd.trim() : String(fd || '').trim();
                if (!normalized) return;
                if (aggregatedSeen.has(normalized)) return;
                aggregatedSeen.add(normalized);
                aggregatedFds.push(normalized);
            });
        };

        for (let i = 0; i < crCols.length; i++) {
            let colsEntry = crCols[i];
            if (typeof colsEntry === 'string') {
                try {
                    const parsed = JSON.parse(colsEntry);
                    colsEntry = Array.isArray(parsed) ? parsed : [];
                } catch (e) {
                    colsEntry = colsEntry.replace(/[\[\]]/g, '').split(',').map(s => s.trim()).filter(Boolean).map(Number);
                    console.warn('renderHistoryRelations: failed to parse columns', e);
                }
            }

            const columns = Array.isArray(colsEntry) ? colsEntry.map(Number) : [];

            const manualData = normalizeManualEntry(crManual[i]);
            const localFds = normalizeToStringList(crFds[i]);
            const originalFds = normalizeToStringList(crFdsOriginal[i]);
            console.log('renderHistoryRelations: preparing table', i, {
                columns,
                manualData,
                localFds,
                originalFds
            });

            let ricMatrixForTable = [];
            if (Array.isArray(crRic) && i < crRic.length) {
                try {
                    const entry = crRic[i];
                    console.log('renderHistoryRelations: ric matrix entry', i, entry);
                    if (Array.isArray(entry)) {
                        ricMatrixForTable = entry;
                    } else if (typeof entry === 'string' && entry.trim()) {
                        const parsed = JSON.parse(entry);
                        if (Array.isArray(parsed)) ricMatrixForTable = parsed;
                    }
                } catch (e) {
                    ricMatrixForTable = [];
                }
            }

            const group = createRelationGroup({ relationId: `history-${i}` });
            console.log('renderHistoryRelations: relation group created', i, group);
            if (!group) continue;

            const relationTitle = `Relation R${i + 1}`;
            const displayFds = originalFds.length ? originalFds : localFds;
            appendAggregatedFds(displayFds);

            const baseWrapper = renderRelationBaseWrapper(group, {
                columns,
                manualData,
                fdList: localFds,
                fdOriginal: originalFds.length ? originalFds : localFds,
                displayFds,
                ricMatrix: Array.isArray(ricMatrixForTable) ? ricMatrixForTable : [],
                relationTitle
            });

            if (baseWrapper) {
                console.log('renderHistoryRelations: base wrapper ready', i, baseWrapper);
                console.log('renderHistoryRelations: base wrapper dataset', i, { dataset: { ...baseWrapper.dataset }, childCount: baseWrapper.childElementCount });
                const fdHeading = baseWrapper.querySelector('.fd-list-container h4');
                if (fdHeading) fdHeading.style.display = 'block';
            }

            ensureLocalContainer(group);
            console.log('renderHistoryRelations: group children count', i, group.children.length);
        }
        console.log('renderHistoryRelations: final host child count', host.children.length);
        console.groupEnd();

        if (origContainer) origContainer.style.display = 'none';
        const fdListContainer = document.getElementById('fdListContainer');
        if (fdListContainer) {
            fdListContainer.style.display = aggregatedFds.length ? 'block' : 'none';
            const fdListEl = fdListContainer.querySelector('#fdListUl');
            if (fdListEl) {
                fdListEl.innerHTML = '';
                aggregatedFds.forEach(fd => {
                    const li = document.createElement('li');
                    li.textContent = fd;
                    fdListEl.appendChild(li);
                });
            }
        }
        if (addTableBtn) addTableBtn.style.display = isRestoreHost ? 'none' : 'inline-block';
        if (decompositionFinishedBtn) decompositionFinishedBtn.style.display = 'none';
        if (changeDecompositionBtn) changeDecompositionBtn.style.display = 'none';
        if (computeAllBtn) computeAllBtn.style.display = 'none';
        if (continueNormalizationBtn) continueNormalizationBtn.style.display = 'none';

        return true;
    }

    const historyRendered = renderHistoryRelations();
    if (!historyRendered) {
        relationMetaMap.clear();
        if (relationsContainer) {
            relationsContainer.innerHTML = '';
            relationsContainer.style.display = 'none';
            relationsContainer.classList.remove('restore-layout');
        }
        if (decContainer) {
            decContainer.style.display = 'flex';
            decContainer.style.flexWrap = 'wrap';
            decContainer.style.gap = '20px';
            decContainer.classList.remove('restore-layout');
        }
        if (origContainer) {
            origContainer.style.display = 'block';
        }
    }
 // Warnings area (for missing column messages)
    let warningsArea = document.getElementById('normalizeWarnings');
    if (!warningsArea) {
        warningsArea = document.createElement('div');
        warningsArea.id = 'normalizeWarnings';
        warningsArea.style.margin = '16px 0 8px 0';
        warningsArea.style.display = 'none';
        warningsArea.style.background = '#fff7ed';
        warningsArea.style.border = '1px solid #ffd8b5';
        warningsArea.style.padding = '12px';
        warningsArea.style.borderRadius = '6px';
        warningsArea.style.color = '#7a2e00';
        warningsArea.style.width = '100%';
        warningsArea.style.boxSizing = 'border-box';
        if (decContainer && decContainer.parentNode) {
            decContainer.parentNode.insertBefore(warningsArea, decContainer.nextSibling);
        } else if (computeAllBtn && computeAllBtn.parentNode) {
            computeAllBtn.parentNode.insertBefore(warningsArea, computeAllBtn.nextSibling);
        } else {
            document.body.appendChild(warningsArea);
        }
    }

    function showWarning(html) {
        warningsArea.innerHTML = html;
        warningsArea.style.display = 'block';
    }
    function clearWarning() {
        warningsArea.innerHTML = '';
        warningsArea.style.display = 'none';
    }

    // Parse projectedFds stored in wrapper.dataset.projectedFds
    function parseProjectedFdsValue(val) {
        if (!val) return [];
        if (typeof val === 'string') {
            const trimmed = val.trim();
            try {
                const parsed = JSON.parse(trimmed);
                if (Array.isArray(parsed)) {
                    return parsed.map(String).filter(s => s && s.trim()).map(s => s.trim());
                }
            } catch (e) {}
            const parts = trimmed.split(/[;\r\n]+/).map(s => s.trim()).filter(Boolean);
            return parts;
        }
        if (Array.isArray(val)) {
            return val.map(String).filter(s => s && s.trim()).map(s => s.trim());
        }
        return [];
    }

    function readProjectedFdsFromWrapper(wrapper) {
        if (!wrapper) return [];
        const raw = wrapper.dataset.projectedFds;
        return parseProjectedFdsValue(raw);
    }

    function uniqConcat(arrays) {
        const seen = new Set();
        const out = [];
        arrays.forEach(a => {
            (a || []).forEach(x => {
                if (!x) return;
                const s = String(x).trim();
                if (!s) return;
                if (!seen.has(s)) {
                    seen.add(s);
                    out.push(s);
                }
            });
        });
        return out;
    }

    async function fetchProjectedFDs(wrapper, cols) {
        if (!cols || cols.length === 0) {
            try { wrapper.dataset.projectedFds = JSON.stringify([]); } catch (e) { wrapper.dataset.projectedFds = '[]'; }
            return;
        }
        const payload = { columns: cols };
        try {
            const resp = await fetch('/normalize/project-fds', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            if (!resp.ok) {
                const text = await resp.text();
                throw new Error(text || resp.statusText);
            }
            const json = await resp.json();
            const list = json.projectedFDs || json.fds || [];
            try { wrapper.dataset.projectedFds = JSON.stringify(list); } catch (e) { wrapper.dataset.projectedFds = '[]'; }
        } catch (err) {
            console.error('fetchProjectedFDs failed', err);
            try { wrapper.dataset.projectedFds = JSON.stringify([]); } catch (e) {}
        }
    }

    // Sortable for original table — enable cloning into decomposed tables but prevent reordering in original
    if (typeof Sortable !== 'undefined') {
        Sortable.create(origHeadRow, {
            group: { name: 'columns', pull: 'clone', put: false },
            sort: false,
            animation: 150,
            draggable: 'th',
            fallbackOnBody: true,
            ghostClass: 'sortable-ghost',
            chosenClass: 'sortable-chosen',
        });
    } else {
        console.warn('SortableJS not loaded');
    }

    function getCoveredColumns() {
        let wrappers = Array.from(document.querySelectorAll('.decomposed-wrapper'));
        const relationGroup = document.querySelector('.relation-group');
        if (relationGroup) {
            const relationTables = Array.from(relationGroup.querySelectorAll('.decomposed-wrapper'));
            if (relationTables.length > 0) wrappers = relationTables;
        }
        const covered = new Set();
        wrappers.forEach(w => {
            try {
                const cols = JSON.parse(w.dataset.columns || '[]');
                if (Array.isArray(cols)) cols.forEach(c => covered.add(Number(c)));
            } catch (e) {}
        });
        return covered;
    }

    function parseColumnsFromWrapper(wrapper) {
        try {
            const cols = JSON.parse(wrapper.dataset.columns || '[]');
            return Array.isArray(cols) ? cols.map(Number) : [];
        } catch (err) {
            return [];
        }
    }

    function readManualDataString(wrapper) {
        const rows = Array.from(wrapper.querySelectorAll('tbody tr')).map(tr =>
            Array.from(tr.cells).map(td => (td.textContent ?? '').trim()).join(',')
        ).filter(Boolean);

        if (rows.length === 0 && wrapper.dataset.restoredManualData) {
            return wrapper.dataset.restoredManualData;
        }
        return rows.join(';');
    }

    function readProjectedFdsString(wrapper) {
        try {
            const fds = readProjectedFdsFromWrapper(wrapper) || [];
            return fds.map(String)
                .map(s => s.replace('\u2192', '->').replace('→', '->'))
                .map(s => s.replaceAll(/\s*,\s*/g, ','))
                .map(s => s.replaceAll(/\s*->\s*/g, '->'))
                .map(s => s.replace(/-+>/g, '->').trim())
                .filter(Boolean)
                .join(';');
        } catch (err) {
            return '';
        }
    }

    // Section for Decomposition Finished button
    // Select the required HTML element
    // Function to check if columns are covered
    function checkColumnCoverage(options = {}) {
        const wrappers = (options.wrappers && options.wrappers.length)
            ? options.wrappers
            : Array.from(document.querySelectorAll('.decomposed-wrapper'));

        if (wrappers.length === 0) {
            return { valid: false, message: 'Error: No decomposed tables exist yet.' };
        }

        const baseColumns = (options.baseColumns && options.baseColumns.length)
            ? options.baseColumns.map(Number)
            : Array.from({ length: colCount }, (_, idx) => idx);

        const covered = new Set();
        wrappers.forEach(w => {
            parseColumnsFromWrapper(w).forEach(idx => covered.add(idx));
        });

        const missing = baseColumns.filter(idx => !covered.has(idx));
        if (missing.length > 0) {
            const labels = missing.map(idx => idx + 1);
            return {
                valid: false,
                message: `Error: The following columns are missing: ${labels.join(', ')}.`
            };
        }

        const successMessage = options.successMessage
            ? options.successMessage
            : '✓ All original columns are covered by the decomposition.';
        return { valid: true, message: successMessage };
    }

    // Function that runs decomposition checks (Lossless-Join and Dependency-Preserving)
    async function runDecompositionChecks(options = {}) {
        const {
            wrappersOverride,
            baseColumns,
            fdsOverride,
            manualDataOverride,
            storeResult = true
        } = options;

        const wrappers = (wrappersOverride && wrappersOverride.length)
            ? wrappersOverride
            : Array.from(document.querySelectorAll('.decomposed-wrapper'));

        const tablesPayload = wrappers.map(w => {
            const entry = { columns: parseColumnsFromWrapper(w) };
            const manual = readManualDataString(w);
            if (manual) entry.manualData = manual;
            const tableFds = readProjectedFdsString(w);
            if (tableFds) entry.fds = tableFds;
            return entry;
        });

        const bodyObj = {
            tables: tablesPayload,
            fds: fdsOverride != null ? fdsOverride : (window.fdListWithClosure || window.fdList || '')
        };

        if (manualDataOverride !== undefined) {
            if (manualDataOverride && manualDataOverride.trim().length > 0) {
                bodyObj.manualData = manualDataOverride;
            }
        } else {
            const combinedManual = tablesPayload
                .map(t => t.manualData)
                .filter(Boolean)
                .join(';');
            if (combinedManual.length > 0) {
                bodyObj.manualData = combinedManual;
            }
        }

        if (Array.isArray(baseColumns) && baseColumns.length > 0) {
            bodyObj.baseColumns = baseColumns.map(Number);
        }

        try {
            const resp = await fetch('/normalize/decompose-all', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(bodyObj)
            });
            if (!resp.ok) {
                const text = await resp.text();
                throw new Error(text || resp.statusText);
            }
            const json = await resp.json();
            if (storeResult) {
                window._lastDecomposeResult = json; // Keep for possible future use
            }
            const ljValid = json.ljPreserved === true;
            const dpValid = json.dpPreserved === true;
            const ljMessage = ljValid
                ? '✓ The decomposition fulfill the lossless-join property.'
                : 'Error: The decomposition does not fulfill the lossless-join property.';

            const dpMessage = dpValid
                ? 'The decomposition is dependency-preserving.'
                : 'The decomposition is not dependency-preserving.';
            return {
                ljValid: ljValid,
                ljMessage: ljMessage,
                dpValid: dpValid,
                dpMessage: dpMessage,
                rawResponse: json // Pass the response for renderDpLjStatus
            };
        } catch (err) {
            return {
                ljValid: false,
                ljMessage: 'Server Error: Could not check for lossless-join.',
                dpValid: false,
                dpMessage: 'Server Error: Could not check for dependency-preserving.',
                rawResponse: null,
                error: err
            };
        }
    }

    async function handleRelationDecompositionCheck(relationGroup, baseWrapper, triggerBtn) {
        const localContainer = relationGroup.querySelector('.local-decomposition-group');
        const localWrappers = localContainer ? Array.from(localContainer.querySelectorAll('.decomposed-wrapper')) : [];

        if (localWrappers.length === 0) {
            Swal.fire({
                icon: 'warning',
                title: 'Cannot Check Relation',
                text: 'Please add decomposed tables for this relation before running the check.',
                confirmButtonText: 'Close'
            });
            return;
        }

        const baseColumns = parseColumnsFromWrapper(baseWrapper);
        if (baseColumns.length === 0) {
            Swal.fire({
                icon: 'error',
                title: 'Missing Columns',
                text: 'Could not determine the column set of the base relation.',
                confirmButtonText: 'Close'
            });
            return;
        }

        const coverageResult = checkColumnCoverage({
            wrappers: localWrappers,
            baseColumns,
            successMessage: '✓ All columns of this relation are covered by the decomposition.'
        });

        if (!coverageResult.valid) {
            Swal.fire({
                icon: 'error',
                title: 'Coverage Check Failed',
                text: coverageResult.message.replace('Error:', 'ERROR:'),
                confirmButtonText: 'Close'
            });
            return;
        }

        triggerBtn.disabled = true;
        document.body.style.cursor = 'wait';

        try {
            const fdsOverride = readProjectedFdsString(baseWrapper);
            const result = await runDecompositionChecks({
                wrappersOverride: localWrappers,
                baseColumns,
                fdsOverride,
                storeResult: false
            });

            const finalMessage = [
                'Decomposition Check Results:',
                '',
                `• ${coverageResult.message}`,
                `• ${result.ljMessage}`,
                `• ${result.dpMessage}`
            ].join('\n');

            const success = result.ljValid && result.dpValid;
            await Swal.fire({
                icon: success ? 'info' : 'warning',
                title: success ? 'Decomposition Valid' : 'Decomposition Issues',
                html: finalMessage.replace(/\n/g, '<br>'),
                confirmButtonText: success ? 'Continue' : 'Close'
            });

            if (success) {
                if (result.rawResponse) {
                    renderDpLjStatus(result.rawResponse);
                }
                localWrappers.forEach(w => {
                    try { w.dataset.baseColumns = JSON.stringify(baseColumns); } catch (err) {}
                });
                lockDecomposedTables(relationGroup);
                showAllDecomposedFds(localWrappers);

                await computeAllRic(true, localWrappers, baseColumns, relationGroup);
            }
        } catch (err) {
            Swal.fire({
                icon: 'error',
                title: 'Check Failed',
                text: err && err.message ? err.message : String(err),
                confirmButtonText: 'Close'
            });
        } finally {
            triggerBtn.disabled = false;
            document.body.style.cursor = 'default';
        }
    }

    // Safe reset
    function clearDpLjStatus() {
        const dpLjStatusBox = document.getElementById('dpLjStatusBox');
        if (dpLjStatusBox) {
            dpLjStatusBox.style.display = 'none';
        }
        const dpLjMessages = document.getElementById('dpLjMessages');
        if (dpLjMessages) {
            dpLjMessages.innerHTML = '';
        }
    }

    // Function that lock decomposed tables
    function lockDecomposedTables(targetGroup = null) {
        const scopeRoot = targetGroup || document;
        const wrappers = Array.from(scopeRoot.querySelectorAll('.decomposed-wrapper'));
        wrappers.forEach(w => {
            // Disable Sortable property in table header (prevents adding/removing/sorting columns)
            const headRow = w.querySelector('table thead tr');
            if (headRow && headRow.sortableInstance) {
                // Disable SortableJS completely (disabling adding/deleting/ordering on the decomposed tables)
                headRow.sortableInstance.option('disabled', true);
            }
            // Hide "Remove Table" button
            const removeBtn = w.querySelector('.remove-table-btn');
            if (removeBtn) {
                removeBtn.style.display = 'none';
            }
            // Hide individual column delete (X) buttons in table headers
            const deleteBtns = w.querySelectorAll('.delete-col-btn');
            deleteBtns.forEach(btn => btn.style.display = 'none');
            // Add a class to the table indicating that it is locked
            w.classList.add('locked-decomposition');
        });
        if (!targetGroup) {
            // Hide global "+Add Table" button
            if (addTableBtn) {
                addTableBtn.style.display = 'none';
            }
            // Prevent column dragging from original table
            const origHeadRow = document.getElementById('originalTableContainer')?.querySelector('table thead tr');
            if (origHeadRow && origHeadRow.sortable) {
                // Disable cloning/pulling of original table
                origHeadRow.sortable.option('group', { name: 'columns', pull: false, put: false });
            }
            if (decompositionFinishedBtn) decompositionFinishedBtn.style.display = 'none';
            if (changeDecompositionBtn) changeDecompositionBtn.style.display = 'inline-block';
        } else {
            const localAddBtns = targetGroup.querySelectorAll('.add-decomp-local-btn');
            localAddBtns.forEach(btn => btn.style.display = 'none');
            const localCheckBtns = targetGroup.querySelectorAll('.check-decomp-local-btn');
            localCheckBtns.forEach(btn => btn.style.display = 'none');
            const localChangeBtns = targetGroup.querySelectorAll('.change-decomp-local-btn');
            localChangeBtns.forEach(btn => btn.style.display = 'inline-block');
        }
    }

    // Unlocks decomposed tables modifications (for "Change Decomposition" button)
    function unlockDecomposedTables(targetGroup = null) {
        // Clear the status box
        clearDpLjStatus();
        const scopeRoot = targetGroup || document;
        const wrappers = Array.from(scopeRoot.querySelectorAll('.decomposed-wrapper'));
        wrappers.forEach(w => {
            // Re-enable Sortable (for drag-and-drop operations)
            const headRow = w.querySelector('table thead tr');
            if (headRow && headRow.sortableInstance) {
                headRow.sortableInstance.option('disabled', false);
            }
            // Show the "Remove Table" and "Delete Column(X)" buttons again
            const removeBtn = w.querySelector('.remove-table-btn');
            if (removeBtn) {
                removeBtn.style.display = 'block';
            }
            const deleteBtns = w.querySelectorAll('.delete-col-btn');
            deleteBtns.forEach(btn => btn.style.display = 'block');
            // Remove lock class
            w.classList.remove('locked-decomposition');
            // Clear FD list
            const fdUl = w.querySelector('.fd-list-container ul');
            if (fdUl) {
                fdUl.innerHTML = '';
            }
        });
        // Show "+Add Table" button again
        if (!targetGroup) {
            if (addTableBtn) {
                addTableBtn.style.display = 'inline-block';
            }
        } else {
            const localAddBtns = targetGroup.querySelectorAll('.add-decomp-local-btn');
            localAddBtns.forEach(btn => btn.style.display = 'inline-block');
            const localCheckBtns = targetGroup.querySelectorAll('.check-decomp-local-btn');
            localCheckBtns.forEach(btn => btn.style.display = 'inline-block');
            const localChangeBtns = targetGroup.querySelectorAll('.change-decomp-local-btn');
            localChangeBtns.forEach(btn => btn.style.display = 'none');
        }
        // Re-enable column dragging from original table
        if (!targetGroup) {
            const origHeadRow = document.getElementById('originalTableContainer')?.querySelector('table thead tr');
            if (origHeadRow && typeof Sortable !== 'undefined' && origHeadRow.sortable) {
                origHeadRow.sortable.option('group', { name: 'columns', pull: 'clone', put: false });
            }
        }

        // Manage buttons, show Decomposition Finished, hide others
        if (!targetGroup) {
            if (decompositionFinishedBtn) {
                decompositionFinishedBtn.style.display = 'inline-block';
            }
            if (changeDecompositionBtn) {
                changeDecompositionBtn.style.display = 'none';
            }
            if (computeAllBtn) {
                computeAllBtn.style.display = 'none';
            }
            if (continueNormalizationBtn) {
                continueNormalizationBtn.style.display = 'none';
            }
        }
    }

    // Show FDs of decomposed tables
    function showAllDecomposedFds(wrappersOverride = null) {
        const wrappers = (Array.isArray(wrappersOverride) && wrappersOverride.length)
            ? wrappersOverride
            : Array.from(document.querySelectorAll('.decomposed-wrapper'));
        if (wrappers.length === 0) {
            return;
        }
        wrappers.forEach(w => {
            const fdContainer = w.querySelector('.fd-list-container');
            const fdUl = fdContainer ? fdContainer.querySelector('ul') : null;
            if (!fdUl) return;
            let proj = [];
            // Writes the FDs stored (projected) on the wrapper to fd-list
            try { proj = readProjectedFdsFromWrapper(w); } catch (e) { proj = []; }
            fdUl.innerHTML = '';
            proj.forEach(s => {
                const li = document.createElement('li');
                li.textContent = s;
                fdUl.appendChild(li);
            });
        });
    }

    // Click event for the "Decomposition Finished" button
    if (decompositionFinishedBtn) {
        decompositionFinishedBtn.addEventListener('click', async () => {

            // Lock UI during operation
            document.body.style.cursor = 'wait';
            decompositionFinishedBtn.disabled = true;

            // Safely reset DP/LJ state
            clearDpLjStatus();

            // Variables that will hold the results
            const messages = [];

            // First control, column coverage
            const coverageResult = checkColumnCoverage();
            const isCoverageValid = coverageResult.valid;

            if (!isCoverageValid) {

                let finalMessage = "Decomposition Check Results:\n\n";
                finalMessage += "• " + coverageResult.message.replace('Error:', 'ERROR:');
                finalMessage += "\n\n------------------------------------\n";
                finalMessage += "Please revise your decomposition based on the errors.";

                // Return the interface to normal
                document.body.style.cursor = 'default';
                decompositionFinishedBtn.disabled = false;

                Swal.fire({
                    icon: 'error',
                    title: 'Check Failed: Incomplete Decomposition',
                    html: finalMessage.replace(/\n/g, '<br>'),
                    confirmButtonText: 'Close'
                });
                return;
            }

            // Second control, Lossless-Join & Dependency-Preserving
            const allChecksResult = await runDecompositionChecks();
            const isLosslessValid = allChecksResult.ljValid;

            if (!isLosslessValid) {
                let finalMessage = "Decomposition Check Results:\n\n";
                finalMessage += "• " + allChecksResult.ljMessage.replace('Error:', 'ERROR:');
                finalMessage += "\n\n------------------------------------\n";
                finalMessage += "Please revise your decomposition based on the errors.";

                // Return the interface to normal
                document.body.style.cursor = 'default';
                decompositionFinishedBtn.disabled = false;

                Swal.fire({
                    icon: 'error',
                    title: 'Check Failed: Not Lossless-Join',
                    html: finalMessage.replace(/\n/g, '<br>'),
                    confirmButtonText: 'Close'
                });
                return;
            }

            let finalMessage = "Decomposition Check Results:\n\n";
            finalMessage += "• " + coverageResult.message;
            finalMessage += "\n• " + allChecksResult.ljMessage;
            finalMessage += "\n\n------------------------------------\n";
            finalMessage += "Your decomposition is valid so far!";

            // Return the interface to normal
            document.body.style.cursor = 'default';
            decompositionFinishedBtn.disabled = false;

            // Show results to user and wait for confirmation before proceeding to RIC computation
            await Swal.fire({
                icon: 'info',
                title: 'Decomposition Valid',
                html: finalMessage.replace(/\n/g, '<br>'),
                confirmButtonText: 'Continue'
            });

            if (allChecksResult.rawResponse) {
                renderDpLjStatus(allChecksResult.rawResponse);
            }
            // Locking after successful check
            lockDecomposedTables();

            // Automatically show functional dependencies in decomposed tables
            showAllDecomposedFds();

            // If successful, manage buttons, "Decomposition Finished" hide, "Change Decomposition" show
            if (decompositionFinishedBtn) {
                decompositionFinishedBtn.style.display = 'none';
            }
            if (changeDecompositionBtn) {
                changeDecompositionBtn.style.display = 'inline-block';
            }

            await computeAllRic(true);
        });
    }

    // "Change Decomposition" button event listener
    if (changeDecompositionBtn) {
        changeDecompositionBtn.addEventListener('click', async () => {
            const result = await Swal.fire({
                title: 'Confirm Change',
                text: 'Are you sure you want to change the decomposition? All calculation results will be reset.',
                icon: 'warning',
                showCancelButton: true,
                confirmButtonColor: '#d33',
                cancelButtonColor: '#3085d6',
                confirmButtonText: 'Yes, change it!'
            });

            if (result.isConfirmed) {
                unlockDecomposedTables();
                Swal.fire('Reset!', 'Decomposition is now editable.', 'info');
            }
        });
    }

    let ricComputationInProgress = false;
    let lastScopedComputation = null;

    function buildManualDataForWrapper(wrapper) {
        if (!wrapper) return { manualData: '', baseColumns: [] };

        let cols = [];
        try { cols = JSON.parse(wrapper.dataset.columns || '[]'); } catch (e) { cols = []; }
        cols = Array.isArray(cols) ? cols.map(Number) : [];

        const manualRows = Array.from(wrapper.querySelectorAll('tbody tr')).map(tr =>
            Array.from(tr.cells).map(td => (td.textContent ?? '').trim()).join(',')
        ).filter(Boolean);

        if (manualRows.length > 0) {
            const joined = manualRows.join(';');
            wrapper.dataset.restoredManualData = joined;
            if (cols.length > 0) {
                try { wrapper.dataset.baseColumns = JSON.stringify(cols); } catch (err) {}
            }
            return { manualData: joined, baseColumns: cols };
        }

        if (wrapper.dataset.restoredManualData) {
            if (cols.length > 0) {
                try { wrapper.dataset.baseColumns = JSON.stringify(cols); } catch (err) {}
            }
            return { manualData: wrapper.dataset.restoredManualData, baseColumns: cols };
        }

        if (cols.length > 0) {
            try { wrapper.dataset.baseColumns = JSON.stringify(cols); } catch (err) {}
        }
        return { manualData: '', baseColumns: cols };
    }

    async function runGlobalRicComputation(wrappers, autoTriggered = false, options = {}) {
        const { scopedBaseColumns = null, relationGroup = null } = options;

        if (!Array.isArray(wrappers) || wrappers.length === 0) {
            Swal.fire({
                icon: 'warning',
                title: 'No Tables',
                text: 'There are no decomposed tables yet.',
                confirmButtonText: 'Close'
            });
            return null;
        }

        const tablesPayload = [];
        const perTableFdsArrays = [];
        const globalMonteCarloFlag = mcCheckboxInput ? mcCheckboxInput.checked : false;
        const globalSamplesVal = mcSamplesInput ? Math.max(parseInt(mcSamplesInput.value || '0', 10), 1) : 0;

        for (const w of wrappers) {
            let cols = [];
            try { cols = JSON.parse(w.dataset.columns || '[]'); } catch { cols = []; }
            const projFds = readProjectedFdsFromWrapper(w) || [];
            perTableFdsArrays.push(projFds);
            if (!cols || cols.length === 0) {
                const { manualData: manualDataFallback } = buildManualDataForWrapper(w);
                tablesPayload.push({
                    columns: [],
                    manualData: manualDataFallback,
                    fds: projFds.join(';'),
                    timeLimit: parseInt(timeLimitInput?.value || '30', 10),
                    monteCarlo: globalMonteCarloFlag,
                    samples: globalMonteCarloFlag ? globalSamplesVal : 0
                });
                continue;
            }
            const { manualData, baseColumns } = buildManualDataForWrapper(w);
            if (Array.isArray(baseColumns) && baseColumns.length > 0) {
                try { w.dataset.baseColumns = JSON.stringify(baseColumns); } catch {}
            }
            tablesPayload.push({
                columns: cols,
                manualData,
                baseColumns,
                fds: projFds.join(';'),
                timeLimit: parseInt(timeLimitInput?.value || '30', 10),
                monteCarlo: globalMonteCarloFlag,
                samples: globalMonteCarloFlag ? globalSamplesVal : 0
            });
        }

        let topLevelFdsArr = [];
        try {
            if (window.fdListWithClosure && String(window.fdListWithClosure).trim()) {
                topLevelFdsArr = parseProjectedFdsValue(window.fdListWithClosure);
            } else if (window.fdList && String(window.fdList).trim()) {
                topLevelFdsArr = parseProjectedFdsValue(window.fdList);
            }
        } catch {
            topLevelFdsArr = [];
        }
        if (topLevelFdsArr.length === 0) topLevelFdsArr = uniqConcat(perTableFdsArrays);

        const bodyObj = { tables: tablesPayload, fds: topLevelFdsArr.join(';') };
        if (Array.isArray(scopedBaseColumns) && scopedBaseColumns.length > 0) {
            bodyObj.baseColumns = scopedBaseColumns.map(Number);
        }

        const startResp = await fetch('/normalize/decompose-stream/start', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(bodyObj)
        });
        if (!startResp.ok) {
            const txt = await startResp.text();
            Swal.fire({
                icon: 'error',
                title: 'Decomposition Not Accepted',
                text: 'Normalization request rejected: ' + (txt || startResp.statusText),
                confirmButtonText: 'Close'
            });
            return null;
        }

        let token;
        try {
            const initJson = await startResp.json();
            token = initJson?.token ? String(initJson.token) : null;
        } catch {
            token = null;
        }
        if (!token) {
            Swal.fire({
                icon: 'error',
                title: 'Stream Error',
                text: 'Normalization stream could not be initialized (missing token).',
                confirmButtonText: 'Close'
            });
            return null;
        }

        let progressListEl = null;
        let progressOpen = false;
        const pendingProgressMessages = [];

        const ensureProgressModal = () => {
            if (progressOpen) return;
            progressOpen = true;
            Swal.fire({
                icon: 'info',
                title: 'Normalization in Progress',
                html: '<div class="normalization-progress"><ul class="progress-log"></ul></div>',
                allowOutsideClick: false,
                allowEscapeKey: false,
                showConfirmButton: false,
                didOpen: () => {
                    const container = Swal.getHtmlContainer();
                    progressListEl = container?.querySelector('.progress-log');
                    if (!progressListEl) {
                        progressListEl = document.createElement('ul');
                        progressListEl.classList.add('progress-log');
                        container?.appendChild(progressListEl);
                    }
                    Swal.showLoading();
                    if (pendingProgressMessages.length > 0) {
                        const buffered = pendingProgressMessages.splice(0, pendingProgressMessages.length);
                        buffered.forEach(({ message, type }) => {
                            renderProgress(message, type);
                        });
                    }
                }
            });
        };

        const renderProgress = (message, type = 'progress') => {
            if (!progressListEl) return;
            const li = document.createElement('li');
            li.textContent = message;
            if (type === 'error') li.classList.add('progress-error');
            if (type === 'done') li.classList.add('progress-done');
            progressListEl.appendChild(li);
            progressListEl.scrollTop = progressListEl.scrollHeight;
        };

        const appendProgress = (message, type = 'progress') => {
            if (!message) return;
            ensureProgressModal();
            if (!progressListEl) {
                pendingProgressMessages.push({ message, type });
                return;
            }
            renderProgress(message, type);
        };

        const parseSseData = (evt) => {
            if (!evt || typeof evt.data !== 'string') return {};
            try { return JSON.parse(evt.data); }
            catch { return { message: evt.data }; }
        };

        const result = await new Promise((resolve, reject) => {
            let streamClosed = false;
            let awaitingUserConfirm = false;
            let pendingPayload = null;

            const finalize = (payload, error) => {
                if (streamClosed && awaitingUserConfirm) {
                    // kullanıcı onayı beklerken finalize çağrılmışsa akışı tamamla
                    awaitingUserConfirm = false;
                }
                if (progressOpen) {
                    Swal.close();
                    progressOpen = false;
                    progressListEl = null;
                }
                if (error) reject(error);
                else resolve(payload);
            };

            const source = new EventSource(`/normalize/decompose-stream?token=${encodeURIComponent(token)}`);

            source.addEventListener('progress', (evt) => {
                const data = parseSseData(evt);
                appendProgress(data.message || 'Working...');
            });

            source.addEventListener('stream-error', (evt) => {
                const data = parseSseData(evt);
                appendProgress(data.message || 'Stream error.', 'error');
                source.close();
                streamClosed = true;
                finalize(null, new Error(data.message || 'Stream error.'));
            });

            source.addEventListener('complete', (evt) => {
                const data = parseSseData(evt);
                appendProgress('Normalization completed.', 'done');
                pendingPayload = data?.payload ?? null;
                awaitingUserConfirm = true;

                source.close();        // SSE bağlantısını hemen kapat
                streamClosed = true;

                Swal.hideLoading();

                const actions = Swal.getActions();
                if (actions) {
                    actions.style.display = 'flex';
                    actions.style.justifyContent = 'center';
                }

                const confirmBtn = Swal.getConfirmButton();
                const handleConfirm = () => {
                    confirmBtn.removeEventListener('click', handleConfirm);
                    awaitingUserConfirm = false;
                    finalize(pendingPayload, null);
                };
                if (confirmBtn) {
                    confirmBtn.style.display = 'inline-block';
                    confirmBtn.textContent = 'Continue';
                    confirmBtn.disabled = false;
                    confirmBtn.addEventListener('click', handleConfirm);
                }
                else {
                    awaitingUserConfirm = false;
                    finalize(pendingPayload, null);
                }
            });

            source.onerror = () => {
                if (streamClosed || awaitingUserConfirm) return;
                appendProgress('Connection lost.', 'error');
                source.close();
                streamClosed = true;
                finalize(null, new Error('Normalization stream connection failed.'));
            };
        });

        if (!result) return null;
        if (relationGroup) {
            lastScopedComputation = {
                group: relationGroup,
                response: result,
                timestamp: Date.now()
            };
        } else {
            window._lastDecomposeResult = result;
        }
        return result;
    }

    async function computeAllRic(autoTriggered = false, overrideWrappers = null, scopedBaseColumns = null, relationGroup = null) {
        if (ricComputationInProgress) {
            return;
        }
        const wrappers = (Array.isArray(overrideWrappers) && overrideWrappers.length)
            ? overrideWrappers
            : Array.from(document.querySelectorAll('.decomposed-wrapper'));
        if (wrappers.length === 0) {
            Swal.fire({
                icon: 'warning',
                title: 'No Tables',
                text: 'There are no decomposed tables yet.',
                confirmButtonText: 'Close'
            });
            return;
        }

        ricComputationInProgress = true;
        if (computeAllBtn) {
            computeAllBtn.style.display = 'none';
        }

        // Build tablesPayload for global decompose-all
        try {
            const json = await runGlobalRicComputation(wrappers, autoTriggered, { scopedBaseColumns, relationGroup });
            if (!json) {
                ricComputationInProgress = false;
                if (!autoTriggered && computeAllBtn) {
                    computeAllBtn.style.display = 'inline-block';
                }
                return;
            }
            if (!relationGroup) {
                window._lastDecomposeResult = json;
            }
            // For each wrapper, compute per-table RIC with single-table endpoint and update UI
            for (let i = 0; i < wrappers.length; i++) {
                const w = wrappers[i];
                try {
                    console.log('computeAll: now computing per-table RIC for wrapper idx=', i);
                    // computeRicForWrapper will fetch /normalize/decompose and update that wrapper's tbody/fd-list
                    let baseCols = null;
                    if (Array.isArray(scopedBaseColumns) && scopedBaseColumns.length) {
                        baseCols = scopedBaseColumns;
                    } else if (w.dataset.baseColumns) {
                        try {
                            const parsed = JSON.parse(w.dataset.baseColumns);
                            if (Array.isArray(parsed) && parsed.length > 0) {
                                baseCols = parsed.map(Number);
                            }
                        } catch (err) {
                            baseCols = null;
                        }
                    }
                    await computeRicForWrapper(w, baseCols);
                    console.log('computeAll: per-table RIC complete for wrapper idx=', i);
                } catch (e) {
                    console.warn('computeAll: per-table RIC failed for wrapper idx=', i, e);
                }

                // Update fd-list from global tableResults if available, but don't overwrite already-rendered table body
                const fdContainer = w.querySelector('.fd-list-container');
                const fdUl = fdContainer ? fdContainer.querySelector('ul') : null;
                const tr = (Array.isArray(json.tableResults) && json.tableResults[i]) ? json.tableResults[i] : {};
                const projList = Array.isArray(tr.projectedFDs) ? tr.projectedFDs : (tr.projectedFDs === undefined ? [] : tr.projectedFDs);
                const listToUse = (projList && projList.length > 0) ? projList : (readProjectedFdsFromWrapper(w) || []);
                if (fdUl) {
                    fdUl.innerHTML = '';
                    listToUse.forEach(s => {
                        const li = document.createElement('li');
                        li.textContent = String(s);
                        fdUl.appendChild(li);
                    });
                }
                try { w.dataset.projectedFdsOrig = JSON.stringify(listToUse); } catch (e) {}
            }

            // Check the BCNF flag
            const scopeResult = relationGroup ? lastScopedComputation?.response : window._lastDecomposeResult;
            const isBcnf = scopeResult && scopeResult.bcnfdecomposition === true;

            // Show the calculation success message (after pressing "Compute Plaque" button) and wait for it to close
            await Swal.fire({
                icon: 'success',
                title: 'Calculation Complete!',
                text: 'Relational information content calculation completed for all decomposed tables.',
                confirmButtonText: 'OK'
            });
            // ------------------------------------------------------------------------------------

            if (isBcnf) {
                const CURRENT_TIME_MS = Date.now();
                const finalElapsedSecs = Math.max(0, Math.floor((CURRENT_TIME_MS - SESSION_START_TIME_MS) / 1000));
                const finalAttempts = parseInt(attemptEl?.textContent || '0', 10);
                const finalElapsed = finalElapsedSecs;

                await Swal.fire({
                    title: 'Normalization Complete! (BCNF)',
                    text: 'Congratulations! The decomposition process is finished. Please enter your name to save the results.',
                    icon: 'success',
                    confirmButtonText: 'Continue'
                });

                if (computeAllBtn) computeAllBtn.style.display = 'none';
                if (continueNormalizationBtn) continueNormalizationBtn.style.display = 'none';
                if (changeDecompositionBtn) changeDecompositionBtn.style.display = 'inline-block';

                window._lastBcnfMeta = {
                    attempts: finalAttempts,
                    elapsed: finalElapsed
                };
                if (showBcnfTablesBtn) showBcnfTablesBtn.style.display = 'inline-block';
                ricComputationInProgress = false;
                return;
            }
            if (showBcnfTablesBtn) showBcnfTablesBtn.style.display = 'none';
            window._lastBcnfMeta = null;

            // If not BCNF, continue to flow (hide Compute Plaque button, show Continue to Normalization)
            if (!relationGroup) {
                if (computeAllBtn) {
                    computeAllBtn.style.display = 'none';
                }
                if (continueNormalizationBtn) {
                    continueNormalizationBtn.style.display = 'inline-block';
                }
            }
            ricComputationInProgress = false;
        } catch (err) {
            console.error('decompose-all failed', err);
            Swal.fire({
                icon: 'error',
                title: 'Server Error',
                text: 'Server error while computing RIC: ' + (err.message || err),
                confirmButtonText: 'Close'
            });
            stopTimer();
            ricComputationInProgress = false;
            if (!autoTriggered && computeAllBtn && !relationGroup) {
                computeAllBtn.style.display = 'inline-block';
            }
        }
    }

    if (computeAllBtn && !autoComputeEnabled) {
        computeAllBtn.addEventListener('click', async () => {
            await computeAllRic(false);
        });
    }

    if (!addTableBtn) {
        console.warn('Add Table button not found (id="addTable")');
    }

    // Helper function to create a new decomposed table (opts supported)
    //   origMode: boolean (if true, wrapper hides title/local add/remove and shows footer controls)
    //   initialColumns: array of numbers
    //   initialManualData: "a,b,c;d,e,f"
    //   initialFds: string
    //   initialRicMatrix: array | string (JSON)
    function createDecomposedTable(opts = {}) {
        const origMode = !!opts.origMode;
        const parentContainer = opts.parentContainer instanceof HTMLElement ? opts.parentContainer : null;
        const autoAppend = opts.autoAppend !== false;
        const initialCols = Array.isArray(opts.initialColumns) ? opts.initialColumns.map(n => Number(n)) : null;

        let rawManualData = opts.initialManualData;
        if (Array.isArray(rawManualData) && rawManualData.length > 0) {
            rawManualData = rawManualData[0];
        }

        const initialManualData = (typeof rawManualData === 'string') ? rawManualData : null;
        const initialFds = (typeof opts.initialFds === 'string') ? opts.initialFds : null;
        let initialRicMatrix = null;
        if (Array.isArray(opts.initialRicMatrix)) {
            initialRicMatrix = opts.initialRicMatrix;
        } else if (typeof opts.initialRicMatrix === 'string' && opts.initialRicMatrix.trim()) {
            try {
                const parsedRic = JSON.parse(opts.initialRicMatrix.trim());
                if (Array.isArray(parsedRic)) initialRicMatrix = parsedRic;
            } catch (err) {
                initialRicMatrix = null;
            }
        }

        let decomposedCols = [];

        console.log('createDecomposedTable: start', {
            origMode,
            autoAppend,
            parentContainer,
            initialCols,
            initialManualData,
            initialFds,
            initialRicMatrix
        });

        let newDecomposedNumber = 1;
        if (!origMode) {
            //  Only count 'Decomposed Table X' headers
            const existingDecomposed = document.querySelectorAll('.decomposed-wrapper:not(.orig-as-original) h3');
            const usedNumbers = new Set();
            existingDecomposed.forEach(h3 => {
                const m = h3.textContent.match(/\d+/);
                const num = parseInt(m?.[0] || 0);
                if (!isNaN(num)) usedNumbers.add(num);
            });
            while (usedNumbers.has(newDecomposedNumber)) newDecomposedNumber++;
        }

        const wrapper = document.createElement('div');
        wrapper.classList.add('decomposed-wrapper');
        if (origMode) {
            wrapper.classList.add('orig-as-original', 'relation-original-wrapper');
            wrapper.dataset.relationBase = 'true';
        } else {
            wrapper.dataset.relationBase = 'false';
        }

        // Title
        const title = document.createElement('h3');
        if (!origMode) {
            // New created decomposed table
            title.textContent = `Decomposed Table ${newDecomposedNumber}:`;
        } else {
            // Restored table: R# assignment will be made in the restore block
            title.textContent = `Relation R:`;
        }
        wrapper.appendChild(title);

        wrapper.style.minWidth = '300px';

        // Content container (table + FD list side-by-side)
        const content = document.createElement('div');
        content.classList.add('decomposed-content');
        // FD list placeholder (now between title and table)
        const fdContainer = document.createElement('div');
        fdContainer.classList.add('fd-list-container', 'fd-inline-panel', 'fd-inline-panel--compact');
        const fdTitle = document.createElement('h4');
        fdTitle.textContent = 'Functional Dependencies';
        const fdUl = document.createElement('ul');
        fdUl.id = `fdList-${Math.floor(Math.random() * 100000)}`;
        fdContainer.appendChild(fdTitle);
        fdContainer.appendChild(fdUl);
        wrapper.appendChild(fdContainer);

        wrapper.appendChild(content);

        const leftCol = document.createElement('div');
        leftCol.classList.add('decomposed-table-holder');
        content.appendChild(leftCol);

        // Table (visual only, header rows will be built from state)
        const table = document.createElement('table');
        table.classList.add('data-grid');
        table.style.tableLayout = 'fixed';
        table.style.width = '100%';
        const thead = table.createTHead();
        const headRow = thead.insertRow();
        const tbody = table.createTBody();
        leftCol.appendChild(table);

        // Warning container
        const warnDiv = document.createElement('div');
        warnDiv.classList.add('decompose-warnings');
        wrapper.appendChild(warnDiv);

        // Remove button - shown only for normal mode
        const removeBtn = document.createElement('button');
        removeBtn.classList.add('small', 'remove-table-btn');
        removeBtn.textContent = 'Remove Table';
        removeBtn.style.float = 'right';
        removeBtn.style.marginLeft = '10px';
        removeBtn.addEventListener('click', async () => {
            const result = await Swal.fire({
                title: 'Confirm Deletion',
                text: 'Do you want to delete this decomposed table?',
                icon: 'warning',
                showCancelButton: true,
                confirmButtonColor: '#d33',
                cancelButtonColor: '#3085d6',
                confirmButtonText: 'Yes, delete it!'
            });
            if (result.isConfirmed) {
                wrapper.remove();
            }
        });
        if (!origMode) wrapper.appendChild(removeBtn);

        // Append wrapper to container
        if (autoAppend) {
            if (parentContainer) {
                parentContainer.appendChild(wrapper);
            } else if (decContainer && decContainer.style.display !== 'none') {
                decContainer.appendChild(wrapper);
            } else if (relationsContainer && relationsContainer.style.display !== 'none') {
                relationsContainer.appendChild(wrapper);
            }
        }

        console.log('createDecomposedTable: appended', {
            parentContainerUsed: parentContainer || (decContainer && decContainer.style.display !== 'none' ? decContainer : relationsContainer),
            wrapper,
            wrapperParent: wrapper.parentElement
        });

        // Dataset placeholders
        wrapper.dataset.columns = JSON.stringify([]);
        wrapper.dataset.projectedFds = JSON.stringify([]);

        // Store initialManualData on wrapper (for multi-stage flow)
        if (initialManualData) {
            wrapper.dataset.restoredManualData = initialManualData;
        }
        if (initialCols) {
            try { wrapper.dataset.baseColumns = JSON.stringify(initialCols); } catch (err) {}
        }
        if (initialRicMatrix) {
            try { wrapper.dataset.ricMatrix = JSON.stringify(initialRicMatrix); } catch (err) {}
        }

        console.log('createDecomposedTable: end', {
            dataset: {
                restoredManualData: wrapper.dataset.restoredManualData,
                baseColumns: wrapper.dataset.baseColumns,
                ricMatrix: wrapper.dataset.ricMatrix
            }
        });

        console.log('createDecomposedTable: restore rendering', {
            wrapper,
            decomposedCols,
            dataset: Object.assign({}, wrapper.dataset)
        });

        // External updater
        wrapper.updateFromColumns = function(newCols) {
            decomposedCols = Array.isArray(newCols) ? newCols.slice() : [];
            try { wrapper.dataset.baseColumns = JSON.stringify(decomposedCols); } catch (err) {}
            renderDecomposed();
        };

        function renderDecomposed() {
            headRow.innerHTML = '';
            decomposedCols.forEach(idx => {
                const th = document.createElement('th');
                const colNum = (idx + 1);

                if (!origMode) {
                    th.innerHTML = `
                        <span>${colNum}</span>
                        <button type="button" class="delete-col-btn" title="Remove column ${colNum}" data-orig-idx="${idx}">×</button>
                    `;
                } else {
                    th.innerHTML = `<span>${colNum}</span>`;
                }
                th.dataset.origIdx = idx;
                th.setAttribute('data-orig-idx', String(idx));
                headRow.appendChild(th);
            });

            tbody.innerHTML = '';
            if (decomposedCols.length === 0) {
                try { wrapper.dataset.columns = JSON.stringify([]); } catch (e) { wrapper.dataset.columns = '[]'; }
                return;
            }

            let sourceRows = [];
            // If there is a .relation-group on the page, this means it is the second stage
            const isInRestoreMode = document.querySelectorAll('.relation-group').length > 0;

            if (isInRestoreMode) {
                // In subsequent normalization steps, the data source is the nearest R# table
                const relationGroup = wrapper.closest('.relation-group');
                if (relationGroup) {
                    const sourceTable = relationGroup.querySelector('.decomposed-wrapper.orig-as-original table');
                    if (sourceTable) {
                        // Read the data in the body of the R# table row by row
                        Array.from(sourceTable.querySelectorAll('tbody tr')).forEach(tr => {
                            const rowData = {}; // Keep each row as an object
                            Array.from(tr.cells).forEach(td => {
                                const origIdx = td.dataset.origIdx;
                                if (origIdx !== undefined) {
                                    rowData[origIdx] = td.textContent;
                                }
                            });
                            sourceRows.push(rowData);
                        });
                    }
                }
            } else {
                // In the first normalization step, the data source is the global originalRows array
                originalRows.forEach(rowArr => {
                    const rowData = {};
                    rowArr.forEach((cell, idx) => {
                        rowData[idx] = cell;
                    });
                    sourceRows.push(rowData);
                });
            }
            const seen = new Set();
            sourceRows.forEach(row => {
                const tuple = decomposedCols.map(i => (row[i] !== undefined ? String(row[i]) : '')).join('|');
                if (seen.has(tuple)) return;
                seen.add(tuple);
                const tr = tbody.insertRow();
                decomposedCols.forEach(idx => {
                    const td = tr.insertCell();
                    td.textContent = row[idx] || '';
                    td.style.backgroundColor = 'white';
                    td.dataset.origIdx = idx;
                    td.classList.remove('plaque-cell');
                    td.style.backgroundColor = 'white';
                });
            });
            try { wrapper.dataset.columns = JSON.stringify(decomposedCols); } catch (e) { wrapper.dataset.columns = '[]'; }
        }

        // Sortable on the headRow
        if (typeof Sortable !== 'undefined') {
            headRow.sortableInstance = Sortable.create(headRow, {
                group: {
                    name: 'columns',
                    pull: (origMode ? 'clone' : true),
                    put: !origMode
                },
                animation: 150,
                draggable: 'th',
                sort: true,
                fallbackOnBody: true,
                ghostClass: 'sortable-ghost',
                chosenClass: 'sortable-chosen',

                onMove: (evt) => {
                    // If the target of the drag operation is an R# table (i.e. the hovered table has the .orig-as-original class), block this move
                    if (evt.to.closest('.orig-as-original')) {
                        return false;
                    }
                    // Allow carry in all other cases (R# -> T#, T# -> T#, Original -> T#)
                    return true;
                },
                onAdd: (evt) => {
                    const item = evt.item;
                    let idx = NaN;
                    try {
                        if (item && item.dataset && item.dataset.origIdx !== undefined && item.dataset.origIdx !== '') {
                            idx = parseInt(item.dataset.origIdx, 10);
                        } else if (item && item.getAttribute && item.getAttribute('data-orig-idx')) {
                            idx = parseInt(item.getAttribute('data-orig-idx'), 10);
                        } else {
                            const txt = item && item.textContent ? item.textContent.trim() : '';
                            const parsed = parseInt(txt, 10);
                            if (!isNaN(parsed)) idx = parsed - 1;
                        }
                    } catch (e) { idx = NaN; }

                    if (!Number.isFinite(idx)) {
                        if (item && item.parentNode === headRow) item.remove();
                        Swal.fire({
                            icon: 'warning',
                            title: 'Column Parse Warning',
                            text: 'Could not parse dropped column index, ignoring.',
                            confirmButtonText: 'Close'
                        });
                        return;
                    }

                    if (decomposedCols.includes(idx)) {
                        if (item && item.parentNode === headRow) item.remove();
                        return;
                    }

                    const insertAt = Math.max(0, Math.min(evt.newIndex, decomposedCols.length));
                    decomposedCols.splice(insertAt, 0, idx);

                    setTimeout(() => {
                        if (item && item.parentNode === headRow) item.remove();
                        renderDecomposed();
                        fetchProjectedFDs(wrapper, decomposedCols);
                    }, 0);
                },

                onRemove: (evt) => {
                    const item = evt.item;
                    if (origMode) {
                        // Just remove the cloned element from the DOM
                        if (item && item.parentNode === headRow) item.remove();
                        return; // Do not change the state
                    }
                    let idx = NaN;
                    try {
                        if (item && item.dataset && item.dataset.origIdx !== undefined) {
                            idx = parseInt(item.dataset.origIdx, 10);
                        } else if (item && item.getAttribute('data-orig-idx')) {
                            idx = parseInt(item.getAttribute('data-orig-idx'), 10);
                        }
                    } catch (e) { idx = NaN; }

                    if (Number.isFinite(idx)) {
                        decomposedCols = decomposedCols.filter(c => Number(c) !== Number(idx));
                        renderDecomposed();
                        fetchProjectedFDs(wrapper, decomposedCols);
                    }
                    if (item && item.parentNode === headRow) item.remove();
                },

                onEnd: (evt) => {
                    // If this table is a source for cloning (origMode), do not change its state (columns) at the end of the drag
                    // This prevents the column body from disappearing
                    if (origMode) {
                        return;
                    }

                    decomposedCols = Array.from(headRow.children).map(th => parseInt(th.dataset.origIdx, 10));

                    const dropX = evt.originalEvent && evt.originalEvent.clientX != null ? evt.originalEvent.clientX : 0;
                    const dropY = evt.originalEvent && evt.originalEvent.clientY != null ? evt.originalEvent.clientY : 0;
                    if (dropX === 0 && dropY === 0) {
                        renderDecomposed();
                        fetchProjectedFDs(wrapper, decomposedCols);
                        return;
                    }

                    const rect = headRow.getBoundingClientRect();
                    const isInside = dropX >= rect.left && dropX <= rect.right && dropY >= rect.top && dropY <= rect.bottom;
                    renderDecomposed();
                    fetchProjectedFDs(wrapper, decomposedCols);
                }
            });

            headRow.addEventListener('click', async (e) => {
                const target = e.target;
                if (!origMode && target.matches('.delete-col-btn')) {
                    // Get origIdx from button
                    const idxToDelete = parseInt(target.dataset.origIdx, 10);
                    const result = await Swal.fire({
                        title: 'Confirm Column Deletion',
                        text: `Are you sure you want to delete column ${idxToDelete + 1}?`,
                        icon: 'warning',
                        showCancelButton: true,
                        confirmButtonColor: '#d33',
                        cancelButtonColor: '#3085d6',
                        confirmButtonText: 'Yes, delete it!'
                    });

                    if (result.isConfirmed) {
                        // Remove from the state array (decomposedCols)
                        decomposedCols = decomposedCols.filter(c => Number(c) !== idxToDelete);
                        // Re-render the table headers and body
                        renderDecomposed();
                        // Recalculate FDs for the modified table
                        fetchProjectedFDs(wrapper, decomposedCols);
                    }
                }
            });

        } else {
            console.warn('SortableJS not loaded (decomp headRow)');
        }

        // Initial population from opts.initialColumns / initialManualData if present
        if (initialCols && initialCols.length > 0) {
            decomposedCols = initialCols.slice();

            // If we are in normal phase (first phase), just call renderDecomposed
            if (!origMode) {
                renderDecomposed();
            }

            // If we are in restore mode (origMode: true) or if there is manual data
            if (origMode || (initialManualData && initialManualData.trim())) {
                // Render titles, only if origMode is set to 0, manually draw the titles (since renderDecomposed is skipped)
                headRow.innerHTML = '';
                decomposedCols.forEach(idx => {
                    const th = document.createElement('th');
                    const colNum = (idx + 1);

                    // Delete button in header, only add if originMode is not
                    if (!origMode) {
                        th.innerHTML = `
                        <span>${colNum}</span>
                        <button type="button" class="delete-col-btn" title="Remove column ${colNum}" data-orig-idx="${idx}">×</button>
                    `;
                    } else {
                        th.innerHTML = `<span>${colNum}</span>`; // In restore mode, only show the column number
                    }

                    th.dataset.origIdx = idx;
                    th.setAttribute('data-orig-idx', String(idx));
                    headRow.appendChild(th);
                });


                // Restore body, redraw body from initialManualData
                if (initialCols && initialCols.length > 0 && initialManualData && initialManualData.trim()) {
                    try {
                        let manualDataToSplit = initialManualData;
                        if (typeof initialManualData === 'string') {
                            manualDataToSplit = initialManualData.trim();
                        } else if (Array.isArray(initialManualData)) {
                            manualDataToSplit = normalizeManualEntry(initialManualData);
                        } else {
                            manualDataToSplit = '';
                        }

                        const rows = manualDataToSplit.length > 0
                            ? manualDataToSplit.split(';').map(r => r.split(',').map(c => (c == null ? '' : String(c).trim())))
                            : [];
                        tbody.innerHTML = '';

                        // Safely parse the RIC matrix and column mapping information from the PageController
                        let tableRic = [];
                        if (wrapper.dataset.ricMatrix) {
                            try {
                                const parsed = JSON.parse(wrapper.dataset.ricMatrix);
                                if (Array.isArray(parsed)) tableRic = parsed;
                            } catch (err) {
                                tableRic = [];
                            }
                        }

                        if (tableRic.length === 0 && Array.isArray(initialRicMatrix) && initialRicMatrix.length > 0) {
                            tableRic = initialRicMatrix;
                        }

                        for (let r = 0; r < rows.length; r++) {
                            const rArr = rows[r];
                            const tr = tbody.insertRow();
                            for (let cIdx = 0; cIdx < decomposedCols.length; cIdx++) {
                                const td = tr.insertCell();
                                const origColIdx = decomposedCols[cIdx]; // Original column index of this table

                                td.textContent = (rArr[cIdx] !== undefined ? rArr[cIdx] : '');
                                td.dataset.origIdx = origColIdx;

                                let ricVal = NaN;
                                if (Array.isArray(tableRic) && Array.isArray(tableRic[r])) {
                                    const v = tableRic[r][cIdx];
                                    const parsed = parseFloat(v);
                                    if (!isNaN(parsed)) ricVal = parsed;
                                }

                				if (!isNaN(ricVal) && ricVal < 1) {
                                    td.classList.add('plaque-cell');
                                    const lightness = 10 + 90 * ricVal;
                                    td.style.backgroundColor = `hsl(220,100%,${lightness}%)`;
                                } else {
                                    td.style.backgroundColor = 'white';
                                    td.classList.remove('plaque-cell');
                                }
                            }
                        }
                    } catch (e) {
                        console.error("Manual data restore failed:", e);
                    }
                } else if (origMode) {
                    // If originMode is active and initialManualData is empty, still clear tbody
                    tbody.innerHTML = '';
                }
            }
            try { wrapper.dataset.columns = JSON.stringify(decomposedCols); } catch(e) {}
        }

        // If initialFds provided, set dataset and fd-list
        if (initialFds && initialFds.trim()) {
            try {
                const list = initialFds.split(/[;\r\n]+/).map(s => s.trim()).filter(Boolean);
                wrapper.dataset.projectedFds = JSON.stringify(list);
                const fdUlLocal = wrapper.querySelector('.fd-list-container ul');
                if (fdUlLocal) {
                    fdUlLocal.innerHTML = '';
                    list.forEach(s => { const li = document.createElement('li'); li.textContent = s; fdUlLocal.appendChild(li); });
                }
            } catch(e) {}
        }

        // Per-Table Control Buttons in Restore Mode
        if (origMode) {
            const footer = document.createElement('div');
            footer.classList.add('orig-as-decomposed-footer');
            footer.style.textAlign = 'right';
            footer.style.paddingTop = '10px';
            footer.style.borderTop = '1px solid #ddd';

            const addTableLocalBtn = document.createElement('button');
            addTableLocalBtn.classList.add('button', 'pill', 'add-decomp-local-btn');
            addTableLocalBtn.textContent = '+ Add Decomposed Table';
            addTableLocalBtn.style.marginRight = '5px';
            addTableLocalBtn.addEventListener('click', () => {
                const relationGroup = wrapper.closest('.relation-group');
                if (!relationGroup) return;
                const localContainer = relationGroup.querySelector('.local-decomposition-group');
                if (!localContainer) return;
                const newWrapper = createDecomposedTable({ origMode: false, parentContainer: localContainer });
                if (wrapper.dataset.baseColumns) newWrapper.dataset.baseColumns = wrapper.dataset.baseColumns;
                const existing = localContainer.querySelectorAll('.decomposed-wrapper');
                const titleEl = newWrapper.querySelector('h3');
                if (titleEl) titleEl.textContent = `Decomposed Table ${existing.length}:`;
            });
            footer.appendChild(addTableLocalBtn);

            const checkDecompLocalBtn = document.createElement('button');
            checkDecompLocalBtn.type = 'button';
            checkDecompLocalBtn.classList.add('button', 'pill', 'check-decomp-local-btn');
            checkDecompLocalBtn.textContent = 'Decomposition Finished';
            checkDecompLocalBtn.addEventListener('click', async () => {
                const relationGroup = wrapper.closest('.relation-group');
                if (!relationGroup) return;
                await handleRelationDecompositionCheck(relationGroup, wrapper, checkDecompLocalBtn);
            });
            footer.appendChild(checkDecompLocalBtn);

            const changeLocalBtn = document.createElement('button');
            changeLocalBtn.type = 'button';
            changeLocalBtn.classList.add('button', 'pill', 'change-decomp-local-btn');
            changeLocalBtn.textContent = 'Change Decomposition';
            changeLocalBtn.style.marginLeft = '5px';
            changeLocalBtn.style.display = 'none';
            changeLocalBtn.addEventListener('click', async () => {
                const relationGroup = wrapper.closest('.relation-group');
                if (!relationGroup) return;
                const confirm = await Swal.fire({
                    title: 'Confirm Change',
                    text: 'Are you sure you want to change the decomposition? All calculation results will be reset.',
                    icon: 'warning',
                    showCancelButton: true,
                    confirmButtonColor: '#d33',
                    cancelButtonColor: '#3085d6',
                    confirmButtonText: 'Yes, change it!'
                });
                if (confirm.isConfirmed) {
                    unlockDecomposedTables(relationGroup);
                    Swal.fire('Reset!', 'Decomposition is now editable.', 'info');
                }
            });
            footer.appendChild(changeLocalBtn);

            wrapper.appendChild(footer);
        }

        return wrapper;
    }

    // Attaching global Add Table button
    if (addTableBtn) {
        addTableBtn.addEventListener('click', () => createDecomposedTable());
    }

    // DP/LJ render + Continue button
    function renderDpLjStatus(resp) {
        let box = document.getElementById('dpLjStatusBox');
        if (!box) {
            box = document.createElement('div');
            box.id = 'dpLjStatusBox';
            box.style.display = 'block';
            box.style.margin = '12px 0';
            box.style.padding = '10px';
            box.style.border = '1px solid #ccc';
            document.getElementById('decomposedTablesContainer')?.after(box);
        }

        const msgs = document.getElementById('dpLjMessages') || (() => {
            const d = document.createElement('div'); d.id='dpLjMessages'; box.prepend(d); return d;
        })();

        console.log('renderDpLjStatus: dp,lj=', resp.dpPreserved, resp.ljPreserved);
        const continueBtn = document.getElementById('continueNormalizationBtn');

        msgs.innerHTML = '';

        const dp = Boolean(resp.dpPreserved);

        const ul = document.createElement('ul');
        ul.style.margin = '0';
        ul.style.paddingLeft = '1.1em';

        const liDp = document.createElement('li');
        liDp.textContent = dp ? 'The decomposition is dependency-preserving.' : 'The decomposition is not dependency-preserving.';
        ul.appendChild(liDp);
        liDp.style.color = dp ? 'green' : '#b65a00';
        ul.appendChild(liDp);

        msgs.appendChild(ul);

        box.style.display = 'block';

        const undo = document.getElementById('undoDecompBtn');
        if (continueBtn && undo && continueBtn.parentNode) {
            continueBtn.parentNode.insertBefore(undo, continueBtn);
        }

        const wrappers = document.querySelectorAll('.decomposed-wrapper');
    }

    function collectDecompositionState() {
        const wrappers = Array.from(document.querySelectorAll('.decomposed-wrapper'));
        if (wrappers.length === 0) {
            return null;
        }

        const tablesData = wrappers.map(w => {
            const cols = JSON.parse(w.dataset.columns || '[]');
            const manualRows = Array.from(w.querySelectorAll('tbody tr')).map(tr => Array.from(tr.cells).map(td => td.textContent).join(','));
            let manualDataString = manualRows.join(';');

            if (manualDataString.length === 0 && w.dataset.restoredManualData) {
                manualDataString = w.dataset.restoredManualData;
            }

            let localFds = [];
            try { localFds = readProjectedFdsFromWrapper(w) || []; } catch (e) { localFds = []; }

            let originalFds = localFds.slice();
            if (w.dataset.projectedFdsOrig) {
                try {
                    const parsed = JSON.parse(w.dataset.projectedFdsOrig);
                    if (Array.isArray(parsed)) {
                        originalFds = parsed.map(String).map(s => s.replace(/→/g, '->').trim()).filter(Boolean);
                    }
                } catch (err) {
                    // keep fallback
                }
            }

            let ricMatrix = [];
            if (w.dataset.ricMatrix) {
                try {
                    const parsedRic = JSON.parse(w.dataset.ricMatrix);
                    if (Array.isArray(parsedRic)) {
                        ricMatrix = parsedRic;
                    }
                } catch (err) {
                    ricMatrix = [];
                }
            }

            return {
                columns: cols,
                manualData: manualDataString,
                localFds,
                originalFds,
                ricMatrix
            };
        });

        const lastResult = window._lastDecomposeResult || {};

        const originalTableString = originalRows.length
            ? originalRows.map(row => row.join(',')).join(';')
            : '';

        const globalRicMatrix = Array.isArray(lastResult.globalRic) && lastResult.globalRic.length
            ? lastResult.globalRic
            : ricMatrix;

        const unionColumnMapping = Array.isArray(lastResult.unionCols) && lastResult.unionCols.length
            ? lastResult.unionCols
            : (originalRows[0] ? originalRows[0].map((_, idx) => idx) : []);

        return {
            tablesData,
            payload: {
                columnsPerTable: tablesData.map(t => t.columns),
                manualPerTable: tablesData.map(t => t.manualData),
                fdsPerTable: tablesData.map(t => t.localFds.join(';')),
                fdsPerTableOriginal: tablesData.map(t => t.originalFds.join(';')),
                ricPerTable: tablesData.map(t => t.ricMatrix),
                globalRic: globalRicMatrix,
                unionCols: unionColumnMapping,
                originalTable: originalTableString,
                originalRic: Array.isArray(ricMatrix) ? ricMatrix : []
            }
        };
    }

    async function handleContinueNormalization() {
        const state = collectDecompositionState();
        if (!state) {
            Swal.fire({
                icon: 'warning',
                title: 'Cannot Proceed',
                text: 'No decomposed tables to continue with.',
                confirmButtonText: 'Close'
            });
            return;
        }
        const payload = state.payload;

        try {
            const res = await fetch('/normalize/continue', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            if (res.redirected) {
                window.location.href = res.url; // Follow the redirect
            } else if (!res.ok) {
                throw new Error(await res.text());
            } else {
                window.location.reload();
            }
        } catch (err) {
            Swal.fire({
                icon: 'error',
                title: 'Error Continuing',
                text: 'Failed to continue to the next step: ' + err.message,
                confirmButtonText: 'Close'
            });
        }
    }

    async function handleShowBcnfTables() {
        const state = collectDecompositionState();
        let payload;
        if (!state) {
            if (alreadyBcnf && window._alreadyBcnfPayload) {
                payload = { ...window._alreadyBcnfPayload };
            } else {
                Swal.fire({
                    icon: 'warning',
                    title: 'No Decomposed Tables',
                    text: 'There are no decomposed tables to display.',
                    confirmButtonText: 'Close'
                });
                return;
            }
        } else {
            payload = { ...state.payload };
        }

        const bcnfMeta = window._lastBcnfMeta || { attempts: 0, elapsed: 0 };
        payload.attempts = bcnfMeta.attempts || 0;
        payload.elapsedTime = bcnfMeta.elapsed || 0;

        try {
            const res = await fetch('/normalize/bcnf-review', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            if (res.redirected) {
                window.location.href = res.url;
                return;
            }

            if (res.ok) {
                const json = await res.json().catch(() => null);
                if (json && json.redirectUrl) {
                    window.location.href = json.redirectUrl;
                    return;
                }
                window.location.href = '/normalize/bcnf-summary';
                return;
            }

            throw new Error(await res.text());
        } catch (err) {
            Swal.fire({
                icon: 'error',
                title: 'Navigation Failed',
                text: 'Unable to open BCNF tables page: ' + err.message,
                confirmButtonText: 'Close'
            });
        }
    }

    const continueBtn = document.getElementById('continueNormalizationBtn');
    if (continueBtn) continueBtn.addEventListener('click', handleContinueNormalization);

    if (showBcnfTablesBtn) {
        showBcnfTablesBtn.addEventListener('click', handleShowBcnfTables);
    }
});