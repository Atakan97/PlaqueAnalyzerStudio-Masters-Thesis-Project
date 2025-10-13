document.addEventListener('DOMContentLoaded', () => {
    // UI elements and state
    const elapsedEl   = document.getElementById('elapsed');
    const attemptEl   = document.getElementById('attemptCount');
    const addTableBtn = document.getElementById('addTable');
    const decContainer= document.getElementById('decomposedTablesContainer');

    const IS_RESTORE_MODE = Array.isArray(window.currentRelationsColumns) && window.currentRelationsColumns.length > 0;

    // Get session start time (if not available, use current time)
    const SESSION_START_TIME_MS = window.normalizationStartTimeMs || Date.now();

    //Compute plaque and continue to normalization buttons
    const computeAllBtn = document.getElementById('computeAllBtn');
    const continueNormalizationBtn = document.getElementById('continueNormalizationBtn');

    const changeDecompositionBtn = document.getElementById('changeDecompositionBtn');

    // Lossless & Dependency options (not used in restore but kept)
    const losslessCheckbox   = document.getElementById('losslessJoin');
    const dependencyCheckbox = document.getElementById('dependencyPreserve');
    const timeLimitInput     = document.getElementById('timeLimit');

    // Functional dependencies transitive closure calculation logic
    const showClosureNormBtn = document.getElementById('showClosureNormBtn');
    const fdListUlNorm = document.getElementById('fdListUl');

    // Data from Thymeleaf
    const transitiveFdsArrayNorm = window.transitiveFdsArray || [];
    const fdItemsNorm = window.fdItems || [];
    // Available red FDs
    const fdInferredNorm = new Set(window.fdInferred || []);

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

    // Parse original table (initialCalcTable passed from server as window.originalTable)
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

    // Render original table with RIC coloring
    const origContainer = document.getElementById('originalTableContainer');
    const origTable     = document.createElement('table');
    origTable.classList.add('data-grid');
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
                    const lightness = 10 + 90 * ricVal;
                    td.style.backgroundColor = `hsl(220,100%,${lightness}%)`;
                } else {
                    td.style.backgroundColor = 'white';
                }
                td.dataset.origIdx = c;
            });
        });
    }

    renderOrigBody(originalRows);
    if (origContainer) origContainer.appendChild(origTable);

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
    async function computeRicForWrapper(wrapper) {
        if (!wrapper) return;

        // Parse columns array for this wrapper (original zero-based indices)
        let cols = [];
        try { cols = JSON.parse(wrapper.dataset.columns || '[]'); } catch (e) { cols = []; }
        cols = Array.isArray(cols) ? cols.map(Number) : [];

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
        const payload = {
            columns: cols,
            manualData: manualData,
            fds: fdsPayloadString,
            timeLimit: parseInt(timeLimitInput?.value || '30', 10),
            monteCarlo: false,
            samples: 0
        };

        console.log('computeRicForWrapper: sending /normalize/decompose payload=', payload);

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

    let restoreMode = false;
    // Restoring decomposed-as-original
    try {
        function normalizeWindowArray(winVal) {
            if (winVal == null) return [];
            if (Array.isArray(winVal)) return winVal;
            if (typeof winVal === 'string') {
                const s = winVal.trim();
                if (s === '') return [];
                try {
                    // Just parse the incoming data assuming it represents a JSON array
                    const parsed = JSON.parse(s);
                    if (Array.isArray(parsed)) return parsed;
                } catch (e) {
                    // Return empty array if there is a JSON parse error
                    return [];
                }
            }
            return [];
        }

        const crColsRaw = window.currentRelationsColumns || null;
        const crManualRaw = window.currentRelationsManual || null;
        const crFdsRaw = window.currentRelationsFds || null;

        const crCols = normalizeWindowArray(crColsRaw);
        const crManual = normalizeWindowArray(crManualRaw);
        const crFds = normalizeWindowArray(crFdsRaw);

        restoreMode = Array.isArray(crCols) && crCols.length > 0;

        if (restoreMode) {

            // Hide old "Original Table"
            if (origContainer) origContainer.style.display = 'none';

            // Show global control buttons again in restore mode
            if (addTableBtn) addTableBtn.style.display = 'inline-block'; // +Add Decomposed Table
            const fdListContainer = document.getElementById('fdListContainer');
            if (fdListContainer) fdListContainer.style.display = 'none'; // Hide FD list

            // Hide Compute Plaque and Continue buttons (these should be visible after check decomposition)
            if (computeAllBtn) computeAllBtn.style.display = 'none';
            if (continueNormalizationBtn) continueNormalizationBtn.style.display = 'none';

            // Show Check Decomposition button
            const checkDecompositionBtn = document.getElementById('checkDecompositionBtn');
            if (checkDecompositionBtn) checkDecompositionBtn.style.display = 'none';

            // Hide Change Decomposition button (should be visible after check decomposition)
            if (changeDecompositionBtn) changeDecompositionBtn.style.display = 'none';

            const globalActionsDiv = document.querySelector('.global-actions');
            if (globalActionsDiv) globalActionsDiv.style.display = 'none';

            // Clean container and hide
            if (decContainer) decContainer.innerHTML = '';
            let restoreTableCounter = 0; // New R# counter

            for (let i = 0; i < crCols.length; i++) {
                restoreTableCounter++;
                let colsEntry = crCols[i];
                // Convert the data coming as JSON or string to an array
                if (typeof colsEntry === 'string') {
                    try {
                        const p = JSON.parse(colsEntry);
                        colsEntry = Array.isArray(p) ? p : [];
                    } catch (e) {
                        colsEntry = colsEntry.replace(/[\[\]]/g, '').split(',').map(x => x.trim()).filter(Boolean).map(x => Number(x));
                    }
                }
                colsEntry = Array.isArray(colsEntry) ? colsEntry.map(n => Number(n)) : [];

                const manualStr = (typeof crManual[i] === 'string') ? crManual[i] : (crManual[i] == null ? '' : String(crManual[i]));
                const fdsStr = (typeof crFds[i] === 'string') ? crFds[i] : (crFds[i] == null ? '' : String(crFds[i]));

                // Create wrapper in origMode so it behaves as "original replacement"
                let w;
                try {
                    // Orig Mode: true (hide header/delete button), initial Columns: previous decomposed columns
                    w = createDecomposedTable({
                        origMode: true,
                        initialColumns: colsEntry,
                        initialManualData: manualStr,
                        initialFds: fdsStr,
                    });

                    // Finalize the header assignment: Relation R1, R2, ...
                    const titleElement = w.querySelector('h3');
                    if(titleElement) {
                        titleElement.textContent = `Relation R${restoreTableCounter}:`;
                    }

                    // Add a CSS class to make the newly created wrapper look like the "original table"
                    w.classList.add('orig-as-decomposed-row-item');

                } catch (e) {
                    console.error('createDecomposedTable failed during restore', e);
                    continue;
                }

                // Ensure dataset.projectedFds set and FD list visible
                try {
                    const fdListToStore = (fdsStr && fdsStr.length > 0) ? (fdsStr.split(/[;\\r\\n]+/).map(s => s.trim()).filter(Boolean)) : [];
                    w.dataset.projectedFds = JSON.stringify(fdListToStore);
                } catch (e) {
                    try { w.dataset.projectedFds = '[]'; } catch (e2) {}
                }
            }
            // Visible decContainer
            if (decContainer) {
                decContainer.style.display = 'flex';
                decContainer.style.flexDirection = 'row';
                decContainer.style.flexWrap = 'wrap';
            }
            }
        }  catch (e) {
        console.warn('Restore (decomposed-as-original) failed', e);
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
        const wrappers = Array.from(document.querySelectorAll('.decomposed-wrapper'));
        const covered = new Set();
        wrappers.forEach(w => {
            try {
                const cols = JSON.parse(w.dataset.columns || '[]');
                if (Array.isArray(cols)) cols.forEach(c => covered.add(Number(c)));
            } catch (e) {}
        });
        return covered;
    }

    // Section for check decomposition button
    // Select the required HTML element
    const checkDecompositionBtn = document.getElementById('checkDecompositionBtn');
    // Function to check if columns are covered
    function checkColumnCoverage() {
        const wrappers = Array.from(document.querySelectorAll('.decomposed-wrapper'));
        if (wrappers.length === 0) {
            return { valid: false, message: 'Error: No decomposed tables exist yet.' };
        }
        const covered = getCoveredColumns();
        const missing = [];
        for (let i = 0; i < colCount; i++) {
            if (!covered.has(i)) {
                missing.push(i + 1);
            }
        }
        if (missing.length > 0) {
            return {
                valid: false,
                message: `Error: The following columns from the original table are missing: ${missing.join(', ')}.`
            };
        }
        return { valid: true, message: '✓ All original columns are covered by the decomposition.' };
    }

    // Function that runs decomposition checks (Lossless-Join and Dependency-Preserving)
    async function runDecompositionChecks() {
        const wrappers = Array.from(document.querySelectorAll('.decomposed-wrapper'));
        const tablesPayload = wrappers.map(w => ({
            columns: JSON.parse(w.dataset.columns || '[]')
        }));
        const bodyObj = { tables: tablesPayload, fds: (window.fdListWithClosure || window.fdList || '') };
        try {
            const resp = await fetch('/normalize/decompose-all', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(bodyObj)
            });
            const json = await resp.json();
            window._lastDecomposeResult = json; // Keep for possible future use
            const ljValid = json.ljPreserved === true;
            const dpValid = json.dpPreserved === true;
            const ljMessage = ljValid
                ? '✓ The decomposition fulfill the lossless join property.'
                : 'Error: The decomposition does not fulfill the lossless join property.';

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
                ljMessage: 'Server Error: Could not check for lossless join.',
                dpValid: false,
                dpMessage: 'Server Error: Could not check for dependency preserving.',
                rawResponse: null
            };
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
    function lockDecomposedTables() {
        const wrappers = Array.from(document.querySelectorAll('.decomposed-wrapper'));
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
        // Hide global "+Add Table" button
        const addTableBtn = document.getElementById('addTable');
        if (addTableBtn) {
            addTableBtn.style.display = 'none';
        }
        // Prevent column dragging from original table
        const origHeadRow = document.getElementById('originalTableContainer')?.querySelector('table thead tr');
        if (origHeadRow && origHeadRow.sortable) {
            // Disable cloning/pulling of original table
            origHeadRow.sortable.option('group', { name: 'columns', pull: false, put: false });
        }
    }

    // Unlocks decomposed tables modifications (for "Change Decomposition" button)
    function unlockDecomposedTables() {
        // Clear the status box
        clearDpLjStatus();
        const wrappers = Array.from(document.querySelectorAll('.decomposed-wrapper'));
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
        if (addTableBtn) {
            addTableBtn.style.display = 'inline-block';
        }
        // Re-enable column dragging from original table
        const origHeadRow = document.getElementById('originalTableContainer')?.querySelector('table thead tr');

        if (origHeadRow && typeof Sortable !== 'undefined' && origHeadRow.sortable) {
            origHeadRow.sortable.option('group', { name: 'columns', pull: 'clone', put: false });
        }

        // Manage buttons, show Check Decomposition, hide others
        if (checkDecompositionBtn) {
            checkDecompositionBtn.style.display = 'inline-block';
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

    // Show FDs of decomposed tables
    function showAllDecomposedFds() {
        const wrappers = Array.from(document.querySelectorAll('.decomposed-wrapper'));
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

    // Click event for the "Check Decomposition" button
    if (checkDecompositionBtn) {
        checkDecompositionBtn.addEventListener('click', async () => {

            // Lock UI during operation
            document.body.style.cursor = 'wait';
            checkDecompositionBtn.disabled = true;

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
                checkDecompositionBtn.disabled = false;

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
                checkDecompositionBtn.disabled = false;

                Swal.fire({
                    icon: 'error',
                    title: 'Check Failed: Lossy Join',
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
            checkDecompositionBtn.disabled = false;

            // Show results to user
            Swal.fire({
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

            // If successful, manage buttons, "Check Decomposition" hide, "Change Decomposition" show
            if (checkDecompositionBtn) {
                checkDecompositionBtn.style.display = 'none';
            }
            if (changeDecompositionBtn) {
                changeDecompositionBtn.style.display = 'inline-block';
            }

            // If successful, show "Compute Plaque" button
            const computeRicBtn = document.getElementById('computeAllBtn');
            if (computeRicBtn) {
                computeRicBtn.style.display = 'inline-block';
            }
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

    // Compute All button
    computeAllBtn.addEventListener('click', async () => {
        const wrappers = Array.from(document.querySelectorAll('.decomposed-wrapper'));
        if (wrappers.length === 0) {
            Swal.fire({
            icon: 'warning',
            title: 'No Tables',
            text: 'There are no decomposed tables yet.',
            confirmButtonText: 'Close'
        }); return; }

        // Build tablesPayload for global decompose-all
        const tablesPayload = [];
        const perTableFdsArrays = [];
        for (const w of wrappers) {
            let cols = [];
            try { cols = JSON.parse(w.dataset.columns || '[]'); } catch (e) { cols = []; }
            const projFds = readProjectedFdsFromWrapper(w);
            perTableFdsArrays.push(projFds || []);
            if (!cols || cols.length === 0) {
                tablesPayload.push({ columns: [], manualData: '', fds: projFds.join(';'), timeLimit: parseInt(timeLimitInput?.value || '30',10), monteCarlo:false, samples:0 });
                continue;
            }
            const seen = new Set();
            const manualRows = [];
            originalRows.forEach(row => {
                const tupArr = cols.map(i => (row[i] !== undefined ? String(row[i]) : ''));
                const key = tupArr.join('|');
                if (!seen.has(key)) { seen.add(key); manualRows.push(tupArr.join(',')); }
            });
            const manualData = manualRows.join(';');
            tablesPayload.push({ columns: cols, manualData: manualData, fds: projFds.join(';'), timeLimit: parseInt(timeLimitInput?.value || '30',10), monteCarlo:false, samples:0 });
        }

        // Top-level FDs
        let topLevelFdsArr = [];
        try {
            if (window.fdListWithClosure && String(window.fdListWithClosure).trim()) {
                topLevelFdsArr = parseProjectedFdsValue(window.fdListWithClosure);
            } else if (window.fdList && String(window.fdList).trim()) {
                topLevelFdsArr = parseProjectedFdsValue(window.fdList);
            }
        } catch (e) { topLevelFdsArr = []; }
        if (topLevelFdsArr.length === 0) topLevelFdsArr = uniqConcat(perTableFdsArrays);
        const bodyObj = { tables: tablesPayload, fds: topLevelFdsArr.join(';') };
        try {
            // Call global endpoint for dp/lj + unionCols metadata
            const resp = await fetch('/normalize/decompose-all', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(bodyObj)
            });

            if (!resp.ok) {
                const txt = await resp.text();
                Swal.fire({
                    icon: 'error',
                    title: 'Decomposition Not Accepted',
                    text: 'Decomposition not accepted: ' + (txt || resp.statusText),
                    confirmButtonText: 'Close'
                });
                return;
            }

            const json = await resp.json();
            window._lastDecomposeResult = json;
            // For each wrapper, compute per-table RIC with single-table endpoint and update UI
            for (let i = 0; i < wrappers.length; i++) {
                const w = wrappers[i];
                try {
                    console.log('computeAll: now computing per-table RIC for wrapper idx=', i);
                    // computeRicForWrapper will fetch /normalize/decompose and update that wrapper's tbody/fd-list
                    await computeRicForWrapper(w);
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
            const isBcnf = window._lastDecomposeResult && window._lastDecomposeResult.bcnfdecomposition === true;

            // Show the calculation success message (after pressing "Compute Plaque" button) and wait for it to close
            await Swal.fire({
                icon: 'success',
                title: 'Calculation Complete!',
                text: 'Relational information content calculation completed for all decomposed tables.',
                confirmButtonText: 'OK'
            });
            // ------------------------------------------------------------------------------------

            if (isBcnf) {
                // If BCNF: Show the modal that will get the username
                // Calculate the total elapsed time
                const CURRENT_TIME_MS = Date.now();
                // Calculate the total elapsed time in seconds
                const finalElapsedSecs = Math.max(0, Math.floor((CURRENT_TIME_MS - SESSION_START_TIME_MS) / 1000));

                // Collect data
                const finalAttempts = parseInt(attemptEl?.textContent || '0', 10);
                const finalElapsed = finalElapsedSecs;
                const { value: userName } = await Swal.fire({
                    title: 'Normalization Complete! (BCNF)',
                    text: 'Congratulations! The decomposition process is finished. Please enter your name to save the results.',
                    icon: 'success',
                    input: 'text',
                    inputLabel: 'Your Name:',
                    inputValidator: (value) => {
                        if (!value) {
                            return 'You must enter your name!';
                        }
                    },
                    confirmButtonText: 'Save and Finish'
                });

                if (userName) {
                    // Make the logging API call
                    await fetch(`/normalize/log-success?userName=${encodeURIComponent(userName.trim())}&attempts=${finalAttempts}&elapsedTime=${finalElapsed}`, {
                        method: 'POST'
                    }).then(res => {
                        if (res.ok) {
                            // Notify if logging was successful
                            Swal.fire('Success!', `Results are saved`, 'success');
                        } else {
                            Swal.fire('Logging Failed', 'Could not save the results to the log.', 'error');
                        }
                    }).catch(err => {
                        Swal.fire('Network Error', 'Failed to connect to logging service.', 'error');
                    });
                } else {
                    // Refused/canceled entering username
                    Swal.fire('Log Skipped', 'Results were not saved.', 'info');
                }


                // End the stream
                if (computeAllBtn) computeAllBtn.style.display = 'none';
                if (continueNormalizationBtn) continueNormalizationBtn.style.display = 'none';
                if (changeDecompositionBtn) changeDecompositionBtn.style.display = 'inline-block';

                return;
            }

            // If not BCNF, continue to flow (hide Compute Plaque button, show Continue to Normalization)
            if (computeAllBtn) {
                computeAllBtn.style.display = 'none';
            }
            if (continueNormalizationBtn) {
                continueNormalizationBtn.style.display = 'inline-block';
            }
        } catch (err) {
            console.error('decompose-all failed', err);
            Swal.fire({
                icon: 'error',
                title: 'Server Error',
                text: 'Server error while computing RIC: ' + (err.message || err),
                confirmButtonText: 'Close'
            });
            stopTimer();
        }
    });

    // Add decomposed table handler
    if (!addTableBtn) {
        console.warn('Add Table button not found (id="addTable")');
        return;
    }

    // Helper function to create a new decomposed table (opts supported)
    //   origMode: boolean (if true, wrapper hides title/local add/remove and shows footer controls)
    //   initialColumns: array of numbers
    //   initialManualData: "a,b,c;d,e,f"
    //   initialFds: string
    function createDecomposedTable(opts = {}) {
        const origMode = !!opts.origMode;
        const initialCols = Array.isArray(opts.initialColumns) ? opts.initialColumns.map(n => Number(n)) : null;

        let rawManualData = opts.initialManualData;
        if (Array.isArray(rawManualData) && rawManualData.length > 0) {
            rawManualData = rawManualData[0];
        }

        const initialManualData = (typeof rawManualData === 'string') ? rawManualData : null;
        const initialFds = (typeof opts.initialFds === 'string') ? opts.initialFds : null;

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
        if (origMode) wrapper.classList.add('orig-as-original');

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
        wrapper.style.marginRight = '20px';
        wrapper.style.marginBottom = '20px';

        // Content container (table + FD list side-by-side)
        const content = document.createElement('div');
        content.classList.add('decomposed-content');
        wrapper.appendChild(content);

        const leftCol = document.createElement('div');
        leftCol.style.flex = '0 0 50%';
        leftCol.style.minWidth = '180px';
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

        // FD list placeholder
        const fdContainer = document.createElement('div');
        fdContainer.classList.add('fd-list-container');
        const fdTitle = document.createElement('h4');
        fdTitle.textContent = 'Functional Dependencies';
        const fdUl = document.createElement('ul');
        fdUl.id = `fdList-${Math.floor(Math.random() * 100000)}`;
        fdContainer.appendChild(fdTitle);
        fdContainer.appendChild(fdUl);
        content.appendChild(fdContainer);

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
        decContainer.appendChild(wrapper);

        // Dataset placeholders
        wrapper.dataset.columns = JSON.stringify([]);
        wrapper.dataset.projectedFds = JSON.stringify([]);

        // Store initialManualData on wrapper (for multi-stage flow)
        if (initialManualData) {
            wrapper.dataset.restoredManualData = initialManualData;
        }

        // Per-table state
        let decomposedCols = [];

        // External updater
        wrapper.updateFromColumns = function(newCols) {
            decomposedCols = Array.isArray(newCols) ? newCols.slice() : [];
            renderDecomposed();
        };

        // Render function
        function renderDecomposed() {
            headRow.innerHTML = '';
            decomposedCols.forEach(idx => {
                const th = document.createElement('th');
                const colNum = (idx + 1);

                // Add column delete button only in normal mode (origMode: false)
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

            tbody.innerHTML = '';
            if (decomposedCols.length === 0) {
                try { wrapper.dataset.columns = JSON.stringify([]); } catch (e) { wrapper.dataset.columns = '[]'; }
                return;
            }
            const seen = new Set();
            originalRows.forEach(row => {
                const tuple = decomposedCols.map(i => (row[i] !== undefined ? String(row[i]) : '')).join('|');
                if (seen.has(tuple)) return;
                seen.add(tuple);
                const tr = tbody.insertRow();
                decomposedCols.forEach(idx => {
                    const td = tr.insertCell();
                    td.textContent = row[idx];
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
                    // Allows the source to be only the original table header
                    put: function (to, from, draggedElement) {
                        // Check globally defined original table header (origHeadRow)
                        return from.el === origHeadRow;
                    }
                },
                animation: 150,
                draggable: 'th',
                sort: true,
                fallbackOnBody: true,
                ghostClass: 'sortable-ghost',
                chosenClass: 'sortable-chosen',

                onMove: (evt) => {
                    return evt.from === origHeadRow || evt.from === headRow;
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

                    if (Number.isFinite(idx)) {
                        decomposedCols = decomposedCols.filter(c => Number(c) !== Number(idx));
                        renderDecomposed();
                        fetchProjectedFDs(wrapper, decomposedCols);
                    }
                    if (item && item.parentNode === headRow) item.remove();
                },

                onEnd: (evt) => {
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
                        if (Array.isArray(initialManualData)) {
                            manualDataToSplit = initialManualData.join(';');
                        } else if (typeof initialManualData === 'string') {
                            manualDataToSplit = initialManualData.trim();
                        } else {
                            manualDataToSplit = '';
                        }

                        const rows = initialManualData.split(';').map(r => r.split(',').map(c => (c == null ? '' : String(c).trim())));
                        tbody.innerHTML = '';

                        // Safely parse the RIC matrix and column mapping information from the PageController
                        let globalRic = [];
                        let unionCols = [];

                        try { globalRic = JSON.parse(window.currentGlobalRic || '[]'); } catch (e) { globalRic = []; console.warn("Global RIC parse failed:", e); }
                        try { unionCols = JSON.parse(window.currentUnionCols || '[]'); } catch (e) { unionCols = []; console.warn("Union Cols parse failed:", e); }

                        for (let r = 0; r < rows.length; r++) {
                            const rArr = rows[r];
                            const tr = tbody.insertRow();
                            for (let cIdx = 0; cIdx < decomposedCols.length; cIdx++) {
                                const td = tr.insertCell();
                                const origColIdx = decomposedCols[cIdx]; // Original column index of this table

                                td.textContent = (rArr[cIdx] !== undefined ? rArr[cIdx] : '');
                                td.dataset.origIdx = origColIdx;

                                // Apply ric coloring
                                let ricVal = NaN;

                                // Find the column index in the globalRic matrix
                                const globalRicColIndex = (unionCols && Array.isArray(unionCols)) ? unionCols.indexOf(origColIdx) : -1;

                                // get RIC value: globalRic[row][global_column_index]
                                if (globalRicColIndex !== -1 && Array.isArray(globalRic[r])) {
                                    const v = globalRic[r][globalRicColIndex];
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

            // +Add Decomposed Table button
            const addTableLocalBtn = document.createElement('button');
            addTableLocalBtn.classList.add('button', 'small', 'add-decomp-local-btn');
            addTableLocalBtn.textContent = '+ Add Decomposed Table';
            addTableLocalBtn.style.marginRight = '5px';
            // Click logic, create the new table and insert it immediately after the existing table
            addTableLocalBtn.addEventListener('click', () => {
                // Count the decomposed (origMode: false) children immediately below this parent
                let localCount = 0;
                // Start from Parent's next sibling
                let nextElement = wrapper.nextElementSibling;

                // Count as long as nextElement exists and this element is not a 'decomposed-wrapper' and 'orig-as-original'
                while(nextElement && nextElement.classList.contains('decomposed-wrapper') && !nextElement.classList.contains('orig-as-original')) {
                    localCount++;
                    nextElement = nextElement.nextElementSibling;
                }

                // Create new table
                const newWrapper = createDecomposedTable({ origMode: false });

                // Assigning local number
                const newLocalNumber = localCount + 1;
                const newTitleElement = newWrapper.querySelector('h3');
                if(newTitleElement) {
                    // Update the title of the newly created table with the local number
                    newTitleElement.textContent = `Decomposed Table ${newLocalNumber}:`;
                }

                wrapper.parentNode.insertBefore(newWrapper, wrapper.nextSibling);
            });
            footer.appendChild(addTableLocalBtn);

            // Check Decomposition button
            const checkDecompLocalBtn = document.createElement('button');
            checkDecompLocalBtn.type = 'button';
            checkDecompLocalBtn.classList.add('button', 'small', 'check-decomp-local-btn');
            checkDecompLocalBtn.textContent = 'Check Decomposition';

            checkDecompLocalBtn.addEventListener('click', () => {
                document.getElementById('checkDecompositionBtn')?.click();
            });
            footer.appendChild(checkDecompLocalBtn);

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

    async function handleContinueNormalization() {
        const result = await Swal.fire({
            title: 'Confirm Proceed',
            text: 'Proceed to the next stage with this decomposition?',
            icon: 'question',
            showCancelButton: true,
            confirmButtonColor: '#3b82f6',
            cancelButtonColor: '#d33',
            confirmButtonText: 'Yes, Proceed'
        });

        if (!result.isConfirmed) return;

        const wrappers = Array.from(document.querySelectorAll('.decomposed-wrapper'));
        if (wrappers.length === 0) {
            Swal.fire({
                icon: 'warning',
                title: 'Cannot Proceed',
                text: 'No decomposed tables to continue with.',
                confirmButtonText: 'Close'
            });
            return;
        }

        const tablesData = wrappers.map(w => {
            const cols = JSON.parse(w.dataset.columns || '[]');
            // Read data directly from the table's tbody
            const manualRows = Array.from(w.querySelectorAll('tbody tr')).map(tr => {
                return Array.from(tr.cells).map(td => td.textContent).join(',');
            });
            let manualDataString = manualRows.join(';');

            // If tbody is empty, use the stored restore data
            if (manualDataString.length === 0 && w.dataset.restoredManualData) {
                manualDataString = w.dataset.restoredManualData;
            }
            return {
                columns: cols,
                manualData: manualDataString,
                fds: (readProjectedFdsFromWrapper(w) || []).join(';')
            };
        });

        // Get plaque and column mapping data
        const lastResult = window._lastDecomposeResult || {};
        const globalRic = lastResult.globalRic || [];
        const unionCols = lastResult.unionCols || [];

        // Payload to be sent to the backend
        const payload = {
            columnsPerTable: tablesData.map(t => t.columns),
            manualPerTable: tablesData.map(t => t.manualData),
            fdsPerTable: tablesData.map(t => t.fds),
            globalRic: globalRic,
            unionCols: unionCols
        };

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

    const continueBtn = document.getElementById('continueNormalizationBtn');
    if (continueBtn) continueBtn.addEventListener('click', handleContinueNormalization);

});
