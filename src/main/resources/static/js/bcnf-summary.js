document.addEventListener('DOMContentLoaded', () => {
    const container = document.getElementById('bcnfTablesContainer');
    const form = document.getElementById('bcnfLogForm');
    const userNameInput = document.getElementById('bcnfUserName');

    /**
     * Function to apply vertical scrolling wrapper for tables with many rows.
     * Will be activated when table has more than 10 rows.
     */
    function applyVerticalScrollingToTable(table, rowCount, threshold = 10) {
        if (!table || !table.parentElement) return;

        const parent = table.parentElement;
        const existingWrapper = parent.classList.contains('table-vertical-scroll-wrapper')
            ? parent
            : null;

        // If row count exceeds threshold and no wrapper exists, add one
        if (rowCount > threshold && !existingWrapper) {
            const scrollWrapper = document.createElement('div');
            scrollWrapper.classList.add('table-vertical-scroll-wrapper');

            // Insert wrapper before table and move table inside it
            const tableParent = table.parentElement;
            tableParent.insertBefore(scrollWrapper, table);
            scrollWrapper.appendChild(table);
        }
        // If row count is below threshold and wrapper exists, remove it
        else if (rowCount <= threshold && existingWrapper) {
            const wrapperParent = existingWrapper.parentElement;
            if (wrapperParent) {
                // Move table back to wrapper's parent and remove wrapper
                wrapperParent.insertBefore(table, existingWrapper);
                existingWrapper.remove();
            }
        }
    }

    const summaryData = window.bcnfSummaryData;
    if (!summaryData || !container) {
        return;
    }

    let parsed;
    if (typeof summaryData === 'string') {
        try {
            parsed = JSON.parse(summaryData);
        } catch (err) {
            console.error('Failed to parse BCNF summary JSON', err);
            return;
        }
    } else {
        parsed = summaryData;
    }

    const originalTableRaw = typeof parsed.originalTable === 'string' ? parsed.originalTable : '';
    const originalRicMatrix = Array.isArray(parsed.originalRic) ? parsed.originalRic : [];
    const columnsPerTable = Array.isArray(parsed.columnsPerTable) ? parsed.columnsPerTable : [];
    const manualPerTable = Array.isArray(parsed.manualPerTable) ? parsed.manualPerTable : [];
    const localFdsPerTable = Array.isArray(parsed.fdsPerTable) ? parsed.fdsPerTable : [];
    const originalFdsPerTable = Array.isArray(parsed.fdsPerTableOriginal) ? parsed.fdsPerTableOriginal : [];
    const ricPerTable = Array.isArray(parsed.ricPerTable) ? parsed.ricPerTable : [];

    const globalRic = Array.isArray(parsed.globalRic) ? parsed.globalRic : [];
    const unionCols = Array.isArray(parsed.unionCols) ? parsed.unionCols : [];

    const attempts = typeof parsed.attempts === 'number' ? parsed.attempts : Number(parsed.attempts || 0);
    const elapsedTime = typeof parsed.elapsedTime === 'number' ? parsed.elapsedTime : Number(parsed.elapsedTime || 0);

    if (!Number.isNaN(attempts)) {
        window.bcnfAttempts = attempts;
    }
    if (!Number.isNaN(elapsedTime)) {
        window.bcnfElapsedSeconds = elapsedTime;
    }

    const getPlaqueColorFn = typeof window.getPlaqueColor === 'function'
        ? window.getPlaqueColor
        : function getPlaqueColor(value) {
            const darkness = Math.max(0, Math.min(1, 1 - value));
            const lightness = 85 - 55 * darkness;
            return `hsl(220, 85%, ${lightness}%)`;
        };

    const originalContainer = document.getElementById('bcnfOriginalTable');
    const tablesFragment = document.createDocumentFragment();

    if (originalContainer && originalTableRaw) {
        const originalSection = document.createElement('div');
        originalSection.classList.add('bcnf-original-card');

        const originalTable = document.createElement('table');
        originalTable.classList.add('data-grid', 'bcnf-original-table');
        const originalHead = originalTable.createTHead();
        const originalHeadRow = originalHead.insertRow();

        const originalRows = originalTableRaw.split(';').filter(Boolean);
        const columnCount = originalRows[0]?.split(',').length || 0;
        for (let colIdx = 0; colIdx < columnCount; colIdx += 1) {
            const th = document.createElement('th');
            th.textContent = (colIdx + 1).toString();
            originalHeadRow.appendChild(th);
        }

        const originalBody = originalTable.createTBody();
        originalRows.forEach((rowStr, rowIdx) => {
            const row = originalBody.insertRow();
            const cellValues = rowStr.split(',');
            cellValues.forEach((value, colIdx) => {
                const td = row.insertCell();
                td.textContent = value;

                // Only apply plaque coloring if enabled
                if (window.plaqueMode === 'enabled') {
                    let ricVal = NaN;
                    if (Array.isArray(originalRicMatrix) && Array.isArray(originalRicMatrix[rowIdx])) {
                        ricVal = parseFloat(originalRicMatrix[rowIdx]?.[colIdx]);
                    }

                    if (!Number.isNaN(ricVal) && ricVal < 1) {
                        td.classList.add('plaque-cell');
                        td.style.backgroundColor = getPlaqueColorFn(ricVal);
                        if (ricVal < 0.5) {
                            td.classList.add('plaque-light-text');
                        }
                    } else {
                        td.classList.remove('plaque-cell');
                        td.classList.remove('plaque-light-text');
                        td.style.backgroundColor = '';
                    }
                }
            });
        });

        originalSection.appendChild(originalTable);
        originalContainer.appendChild(originalSection);

        // Apply vertical scrolling if table has more than 10 rows
        applyVerticalScrollingToTable(originalTable, originalRows.length);
    }

    columnsPerTable.forEach((cols, tableIdx) => {
        const wrapper = document.createElement('div');
        wrapper.classList.add('bcnf-table-card');

        const fdSection = document.createElement('div');
        fdSection.classList.add('fd-inline-panel', 'fd-inline-panel--compact', 'bcnf-fd-panel');
        const fdTitle = document.createElement('h4');
        fdTitle.textContent = 'Functional Dependencies:';
        fdSection.appendChild(fdTitle);
        const ul = document.createElement('ul');
        ul.classList.add('fd-pill-list');
        const fdListRaw = (originalFdsPerTable[tableIdx] ?? localFdsPerTable[tableIdx]) || '';
        const fdList = typeof fdListRaw === 'string'
            ? fdListRaw.split(/[;\r\n]+/).map(s => s.trim()).filter(Boolean)
            : [];
        fdList.forEach(fd => {
            const li = document.createElement('li');
            // Replace -> with → for consistency
            li.textContent = fd.replace(/->/g, '→');
            ul.appendChild(li);
        });
        if (fdList.length === 0) {
            const li = document.createElement('li');
            li.textContent = '—';
            ul.appendChild(li);
        }
        fdSection.appendChild(ul);

        const table = document.createElement('table');
        table.classList.add('data-grid');
        const thead = table.createTHead();
        const headRow = thead.insertRow();

        const columnIndices = Array.isArray(cols) ? cols : [];
        columnIndices.forEach((origIdx) => {
            const th = document.createElement('th');
            th.textContent = (Number(origIdx) + 1).toString();
            headRow.appendChild(th);
        });

        const tbody = table.createTBody();
        const manualDataRaw = manualPerTable[tableIdx] || '';
        const manualRows = typeof manualDataRaw === 'string'
            ? manualDataRaw.split(';').filter(Boolean)
            : [];

        const localRicMatrix = Array.isArray(ricPerTable[tableIdx]) ? ricPerTable[tableIdx] : [];

        manualRows.forEach((rowStr, rowIdx) => {
            const tr = tbody.insertRow();
            const cellValues = rowStr.split(',');
            columnIndices.forEach((_, colIdx) => {
                const td = tr.insertCell();
                td.textContent = cellValues[colIdx] ?? '';

                // Only apply plaque coloring if enabled
                if (window.plaqueMode === 'enabled') {
                    let ricVal = NaN;
                    if (Array.isArray(localRicMatrix) && Array.isArray(localRicMatrix[rowIdx]) && localRicMatrix[rowIdx][colIdx] != null) {
                        ricVal = parseFloat(localRicMatrix[rowIdx][colIdx]);
                    } else if (Array.isArray(globalRic) && Array.isArray(globalRic[rowIdx])) {
                        const unionIdx = columnIndices[colIdx];
                        if (Array.isArray(unionCols)) {
                            const globalColIndex = unionCols.indexOf(unionIdx);
                            if (globalColIndex >= 0) {
                                ricVal = parseFloat(globalRic[rowIdx]?.[globalColIndex]);
                            }
                        }
                    }

                    if (!Number.isNaN(ricVal) && ricVal < 1) {
                        td.classList.add('plaque-cell');
                        td.style.backgroundColor = getPlaqueColorFn(ricVal);
                        if (ricVal < 0.5) {
                            td.classList.add('plaque-light-text');
                        } else {
                            td.classList.remove('plaque-light-text');
                        }
                    } else {
                        td.classList.remove('plaque-cell');
                        td.classList.remove('plaque-light-text');
                        td.style.backgroundColor = '';
                    }
                }
            });
        });

        wrapper.appendChild(fdSection);
        wrapper.appendChild(table);

        // Apply vertical scrolling if table has more than 10 rows
        applyVerticalScrollingToTable(table, manualRows.length);

        tablesFragment.appendChild(wrapper);
    });

    if (tablesFragment.childNodes.length === 0) {
        const emptyState = document.createElement('p');
        emptyState.textContent = 'No decomposed tables were captured for this session.';
        container.appendChild(emptyState);
    } else {
        container.appendChild(tablesFragment);
    }

    if (form) {
        form.addEventListener('submit', async (event) => {
            event.preventDefault();

            const userName = userNameInput?.value?.trim();
            if (!userName) {
                Swal.fire({
                    icon: 'warning',
                    title: 'Missing Name',
                    text: 'Please enter your name before saving the results.',
                    confirmButtonText: 'Close'
                });
                return;
            }

            try {
                const response = await fetch(`/normalize/log-success?userName=${encodeURIComponent(userName)}&attempts=${encodeURIComponent(window.bcnfAttempts ?? 0)}&elapsedTime=${encodeURIComponent(window.bcnfElapsedSeconds ?? 0)}`, {
                    method: 'POST'
                });

                if (response.ok) {
                    Swal.fire({
                        icon: 'success',
                        title: 'Results Saved',
                        text: 'BCNF results have been saved successfully.',
                        confirmButtonText: 'OK'
                    }).then(() => {
                        form.reset();
                    });
                } else {
                    throw new Error(await response.text());
                }
            } catch (err) {
                Swal.fire({
                    icon: 'error',
                    title: 'Save Failed',
                    text: 'Unable to save BCNF results: ' + err.message,
                    confirmButtonText: 'Close'
                });
            }
        });
    }
});
