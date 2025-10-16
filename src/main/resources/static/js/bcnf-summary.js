document.addEventListener('DOMContentLoaded', () => {
    const container = document.getElementById('bcnfTablesContainer');
    const form = document.getElementById('bcnfLogForm');
    const userNameInput = document.getElementById('bcnfUserName');

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

    const tablesFragment = document.createDocumentFragment();

    columnsPerTable.forEach((cols, tableIdx) => {
        const wrapper = document.createElement('div');
        wrapper.classList.add('bcnf-table-card');

        const title = document.createElement('h4');
        title.textContent = `Table ${tableIdx + 1}`;
        wrapper.appendChild(title);

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

                let ricVal = NaN;
                if (Array.isArray(localRicMatrix) && Array.isArray(localRicMatrix[rowIdx]) && localRicMatrix[rowIdx][colIdx] != null) {
                    ricVal = parseFloat(localRicMatrix[rowIdx][colIdx]);
                } else if (globalRic && Array.isArray(globalRic) && Array.isArray(globalRic[rowIdx])) {
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
                    const lightness = 10 + 90 * ricVal;
                    td.style.backgroundColor = `hsl(220,100%,${lightness}%)`;
                }
            });
        });

        wrapper.appendChild(table);

        const fdListRaw = (originalFdsPerTable[tableIdx] ?? localFdsPerTable[tableIdx]) || '';
        const fdList = typeof fdListRaw === 'string'
            ? fdListRaw.split(/[;\r\n]+/).map(s => s.trim()).filter(Boolean)
            : [];

        const fdSection = document.createElement('div');
        fdSection.classList.add('fd-list-container');
        const fdTitle = document.createElement('h5');
        fdTitle.textContent = 'Functional Dependencies';
        fdSection.appendChild(fdTitle);
        const ul = document.createElement('ul');
        fdList.forEach(fd => {
            const li = document.createElement('li');
            li.textContent = fd;
            ul.appendChild(li);
        });
        if (fdList.length === 0) {
            const li = document.createElement('li');
            li.textContent = '—';
            ul.appendChild(li);
        }
        fdSection.appendChild(ul);
        wrapper.appendChild(fdSection);

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
