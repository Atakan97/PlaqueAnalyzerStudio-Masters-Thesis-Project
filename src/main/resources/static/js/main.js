document.addEventListener('DOMContentLoaded', function() {
    const ricMatrix = window.ricMatrix || [];
    // Deleting old rows from the FD table
    const fdTbody = document.querySelector('#fdTable tbody');
    if (fdTbody) fdTbody.innerHTML = '';

    // Adding and deleting functional dependencies
    const addFdButton = document.getElementById('addFdBtn');
    const fdTableBody = document.getElementById('fdTable')?.querySelector('tbody');
    if (addFdButton && fdTableBody) {
        addFdButton.addEventListener('click', () => {
            const rowCount = fdTableBody.rows.length + 1;
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td>${rowCount}</td>
                <td contenteditable></td>
                <td>→</td>
                <td contenteditable></td>
                <td><button class="delFd">×</button></td>
            `;
            fdTableBody.appendChild(tr);
        });
        fdTableBody.addEventListener('click', e => {
            if (e.target.matches('.delFd')) {
                e.target.closest('tr').remove();
                Array.from(fdTableBody.rows).forEach((r, i) => r.cells[0].textContent = i + 1);
            }
        });

        // If the table is empty (no restored data), insert a blank row
        if (fdTableBody.rows.length === 0) {
            const tr = document.createElement('tr');
            tr.innerHTML = `
            <td>1</td>
            <td contenteditable></td>
            <td>→</td>
            <td contenteditable></td>
            <td><button class="delFd">×</button></td>
        `;
            fdTableBody.appendChild(tr);
        }
    }

    // Dynamic manual data section
    const manualDataTable = document.getElementById('manualDataTable');
    const addRowButton = document.getElementById('addRow');
    const updateColumnsBtn = document.getElementById('updateColumnsBtn');
    const columnCountInput = document.getElementById('columnCountInput');

    // Main function that updates the table according to the desired number of columns
    function updateManualTable(colCount) {
        if (!manualDataTable || colCount < 1) return;

        const thead = manualDataTable.querySelector('thead');
        const tbody = manualDataTable.querySelector('tbody');

        // Clear existing header and body
        thead.innerHTML = '';
        tbody.innerHTML = '';

        // Create new header
        const headerRow = thead.insertRow();
        for (let i = 1; i <= colCount; i++) {
            const th = document.createElement('th');
            th.textContent = i;
            headerRow.appendChild(th);
        }
        // Add a cell titled "Del" for the delete button
        const delTh = document.createElement('th');
        delTh.textContent = 'Del';
        headerRow.appendChild(delTh);

        // Add at least one blank line
        addManualRow();
    }

    // Function that adds a new row
    function addManualRow() {
        const tbody = manualDataTable.querySelector('tbody');
        if (!tbody) return;

        // Read the number of columns directly from input
        const colCount = parseInt(columnCountInput.value, 10);
        if (colCount < 1) return;

        const newRow = tbody.insertRow();

        // Add editable cells (td) as many times as the number of columns
        for (let i = 0; i < colCount; i++) {
            const cell = newRow.insertCell();
            cell.contentEditable = true;
        }

        // Add row delete button
        const deleteCell = newRow.insertCell();
        deleteCell.innerHTML = `<button class="delManualRow">×</button>`;
    }

    // Function to detect and remove duplicate tuples from manual data table
    function detectAndRemoveDuplicateTuples() {
        const tbody = manualDataTable.querySelector('tbody');
        if (!tbody) return 0;

        const rows = Array.from(tbody.querySelectorAll('tr'));
        const seenTuples = new Set();
        const rowsToRemove = [];

        rows.forEach(row => {
            const cells = Array.from(row.querySelectorAll('td[contenteditable]'));
            const tupleContent = cells.map(cell => cell.textContent.trim()).join(',');

            // Skip completely empty rows
            if (tupleContent.replace(/,/g, '').trim() === '') {
                return;
            }

            // Normalize the tuple (remove extra spaces, standardize format)
            const normalizedTuple = tupleContent.toLowerCase().replace(/\s+/g, '');

            if (seenTuples.has(normalizedTuple)) {
                // This is a duplicate
                rowsToRemove.push(row);
            } else {
                seenTuples.add(normalizedTuple);
            }
        });

        // Remove duplicate rows
        rowsToRemove.forEach(row => row.remove());

        return rowsToRemove.length;
    }

    // Update the table when the "Update Table" button is clicked
    if (updateColumnsBtn && columnCountInput) {
        updateColumnsBtn.addEventListener('click', () => {
            const count = parseInt(columnCountInput.value, 10);
            updateManualTable(count);
        });
    }

    // Add a new row when the "+ Add Row" button is clicked
    if (addRowButton) {
        addRowButton.addEventListener('click', addManualRow);
    }

    // Manage row deletion event (with event deletion)
    if (manualDataTable) {
        manualDataTable.addEventListener('click', e => {
            if (e.target.matches('.delManualRow')) {
                e.target.closest('tr').remove();
            }
        });

        // Initialize table with default 3 columns on page load
        updateManualTable(3);
    }

    // Preview CSV files
    const csvInput = document.getElementById('csvfile');
    let fullCsvData = [];
    if (csvInput) {
        csvInput.addEventListener('change', () => {
            const file = csvInput.files[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = evt => {
                const text = evt.target.result;
                // Use delimiter auto-detection to support both CSV (comma) and TSV (tab) files
                const parsed = Papa.parse(text, {
                    skipEmptyLines: true,
                    delimiter: "",  // Empty string triggers auto-detection
                    delimitersToGuess: [',', '\t', ';', '|']  // Common delimiters to check
                });
                if (parsed.errors?.length) {
                    console.error('CSV parse errors', parsed.errors);
                    Swal.fire({
                        icon: 'error',
                        title: 'CSV could not read',
                        text: parsed.errors[0].message,
                        confirmButtonText: 'Close'
                    });
                    return;
                }
                const rows = parsed.data;
                fullCsvData = rows;
                populateManualTableFromParsedCsv(rows);
                const duplicateCount = detectAndRemoveDuplicateTuples();
                syncManualDataFromTable();
                console.info('Duplicate rows removed:', duplicateCount);
            };
            reader.readAsText(file);
        });
    }

    function populateManualTableFromParsedCsv(rows) {
        if (!Array.isArray(rows) || rows.length === 0) return;
        const columnCount = rows[0].length;
        const columnCountInput = document.getElementById('columnCountInput');
        if (columnCountInput) columnCountInput.value = columnCount;
        updateManualTable(columnCount);
        const tbody = manualDataTable.querySelector('tbody');
        if (!tbody) return;
        tbody.innerHTML = '';
        rows.forEach(rowValues => {
            const newRow = tbody.insertRow();
            for (let i = 0; i < columnCount; i++) {
                const newCell = newRow.insertCell();
                newCell.setAttribute('contenteditable', 'true');
                newCell.textContent = rowValues[i] ?? '';
            }
            const deleteCell = newRow.insertCell();
            deleteCell.innerHTML = '<button type="button" class="delManualRow">×</button>';
        });
    }

    function syncManualDataFromTable() {
        const manualRows = Array.from(document.querySelectorAll('#manualDataTable tbody tr'));
        // Serialize rows - quote values that contain comma or semicolon
        const rowsData = manualRows.map(row => {
            const cells = Array.from(row.querySelectorAll('td[contenteditable]'));
            return cells.map(cell => cell.textContent.trim());
        }).filter(row => row.some(cell => cell !== ''));

        const serializedRows = rowsData.map(row => {
            const quotedCells = row.map(cell => {
                // If cell contains comma, semicolon, or double quote, wrap in quotes and escape internal quotes
                if (cell.includes(',') || cell.includes(';') || cell.includes('"')) {
                    const escaped = cell.replace(/"/g, '""');
                    return `"${escaped}"`;
                }
                return cell;
            });
            return quotedCells.join(',');
        });
        const serialized = serializedRows.join(';');
        document.getElementById('manualData').value = serialized;
    }

    // Take CSV rows and fill manual FD table
    function populateFdTableFromCsv(fdLines) {
        const fdTableBody = document.querySelector('#fdTable tbody');
        if (!fdTableBody) return;
        // Clear existing FDs
        fdTableBody.innerHTML = '';
        fdLines.forEach((fdText, index) => {
            // Normalize the separator
            const line = fdText.replace(/→/g, '->');
            const parts = line.split('->');
            if (parts.length !== 2) return;

            const left = parts[0].trim();
            const right = parts[1].trim();

            const newRow = fdTableBody.insertRow();
            newRow.innerHTML = `
                <td>${index + 1}</td>
                <td contenteditable>${left}</td>
                <td>→</td>
                <td contenteditable>${right}</td>
                <td><button type="button" class="delFd">×</button></td>
            `;
        });

        // Rearrange line numbers
        Array.from(fdTableBody.rows).forEach((r, i) => r.cells[0].textContent = i + 1);
    }

    // Functional dependencies file preview section
    const fdFileInput = document.getElementById('fdfile');
    let fullFdData     = [];
    if (fdFileInput) {
        fdFileInput.addEventListener('change', () => {
            const file = fdFileInput.files[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = evt => {
                // Read, normalize and filter FD rows
                fullFdData = evt.target.result
                    .split(/\r?\n/)
                    // Normalize the separator
                    .map(l => l.trim().replace(/→/g, '->'))
                    // Keep only valid FD rows
                    .filter(l => l !== '' && l.includes('->'));

                // Fill manual FD table
                populateFdTableFromCsv(fullFdData);
            };
            reader.readAsText(file);
        });
    }

    // Adding Monte Carlo optimization checkbox
    const monteCarloCheckbox = document.getElementById('mcCheckbox');
    const samples = document.getElementById('samples');
    if (monteCarloCheckbox && samples) {
        const params = new URLSearchParams(window.location.search);
        const restoredMonteCarlo = params.get('monteCarlo');
        const restoredSamples = params.get('samples');
        if (restoredMonteCarlo !== null) {
            const shouldCheck = restoredMonteCarlo === 'true' || restoredMonteCarlo === 'on' || restoredMonteCarlo === '1';
            monteCarloCheckbox.checked = shouldCheck;
            samples.disabled = !shouldCheck;
        }
        if (restoredSamples !== null && restoredSamples !== '') {
            samples.value = restoredSamples;
        } else {
            samples.value = '100000';
        }
        monteCarloCheckbox.addEventListener('change', () => {
            samples.disabled = !monteCarloCheckbox.checked;
        });
    }

    // Collecting data with using form submit
    const form = document.getElementById('calcForm');
    if (!form) return console.error("Form could not be found.");

    const computeBtn = document.getElementById('computeBtn');

    form.addEventListener('submit', e => {
        // Stop form submit by default
        e.preventDefault();

        // Detect and remove duplicate tuples before processing
        const duplicateCount = detectAndRemoveDuplicateTuples();

        // Collecting CSV file or manual data
        let manualContent = '';

        // Always read from the table as the manual table is the only and editable data source
        const manualRows = Array.from(document.querySelectorAll('#manualDataTable tbody tr'));
        // Use PapaParse to properly serialize rows with values containing commas or semicolons
        const rowsData = manualRows.map(row => {
            const cells = Array.from(row.querySelectorAll('td[contenteditable]'));
            return cells.map(cell => cell.textContent.trim());
        }).filter(row => row.some(cell => cell !== ''));

        // Serialize each row - quote values that contain comma or semicolon
        const serializedRows = rowsData.map((row, rowIndex) => {
            const quotedCells = row.map((cell, cellIndex) => {
                // If cell contains comma, semicolon, or double quote, wrap in quotes and escape internal quotes
                if (cell.includes(',') || cell.includes(';') || cell.includes('"')) {
                    // Escape existing double quotes by doubling them
                    const escaped = cell.replace(/"/g, '""');
                    return `"${escaped}"`;
                }
                return cell;
            });
            return quotedCells.join(',');
        });
        manualContent = serializedRows.join(';');
        document.getElementById('manualData').value = manualContent;

        // Collecting functional dependencies
        let fdLines = Array.from(document.querySelectorAll('#fdTable tbody tr'))
            .map(row => {
                const lhs = row.cells[1].textContent.trim();
                const rhs = row.cells[3].textContent.trim();
                return lhs && rhs ? `${lhs}->${rhs}` : null;
            })
            .filter(s => s);
        document.getElementById('fdsInput').value = fdLines.join(';');


        const finalDataString = manualContent.trim();
        const finalFdsString = fdLines.join(';').trim();

        const isDataMissing = finalDataString.length === 0;
        const isFdsMissing = finalFdsString.length === 0;

        // Both Data and FD are missing
        if (isDataMissing && isFdsMissing) {
            Swal.fire({
                icon: 'error',
                title: 'No input was entered',
                text: 'Please enter data and functional dependencies.',
                confirmButtonText: 'Close'
            });
            return;
        }

        // Only table data is missing
        if (isDataMissing) {
            Swal.fire({
                icon: 'error',
                title: 'Table Data Missing',
                text: 'Please enter any table data (manual or CSV).',
                confirmButtonText: 'Close'
            });
            return;
        }

        startLiveComputation({
            manualData: finalDataString,
            fds: finalFdsString,
            monteCarloSelected: !!(document.getElementById('mcCheckbox')?.checked),
            samples: document.getElementById('samples')?.value || '100000',
            duplicatesRemoved: duplicateCount
        });
    });

    function startLiveComputation({ manualData, fds, monteCarloSelected, samples, duplicatesRemoved }) {
        if (computeBtn) computeBtn.disabled = true;


        let swalInstance;
        const progressItems = [];
        let lastProgressAt = performance.now();
        const streamStartedAt = performance.now();
        const MIN_PROGRESS_MS = 1200;
        const MIN_AFTER_LAST_PROGRESS_MS = 600;

        const buildProgressHtml = (noteText, status = 'running') => {
            const listItems = progressItems.map(item => `<li>${item}</li>`).join('');
            const iconHtml = status === 'running'
                ? '<div class="live-status-spinner"></div>'
                : '<div class="live-status-icon live-status-icon--success">✓</div>';
            return `
                <div class="live-status-wrapper">
                    ${iconHtml}
                    <div class="live-status-content">
                        <p class="live-status-note">${noteText}</p>
                        <ul class="live-status-log">${listItems}</ul>
                    </div>
                </div>
            `;
        };

        const renderModal = (title, htmlContent) => {
            const result = Swal.fire({
                title,
                html: htmlContent,
                allowOutsideClick: false,
                allowEscapeKey: false,
                showConfirmButton: false,
                didOpen: () => {
                    Swal.getHtmlContainer().querySelector('.live-status-log')?.scrollTo({ top: 999999, behavior: 'smooth' });
                }
            });
            swalInstance = result;
        };

        const updateModal = () => {
            const html = buildProgressHtml('Calculation is running. After the calculations are completed, results page can be directed.', 'running');
            if (!Swal.isVisible()) {
                renderModal('Computation Status', html);
            } else {
                Swal.update({ html });
                Swal.getHtmlContainer().querySelector('.live-status-log')?.scrollTo({ top: 999999, behavior: 'smooth' });
            }
        };

        const appendStatus = (message) => {
            if (!message) return;
            progressItems.push(message);
            lastProgressAt = performance.now();
            updateModal();
        };

        const showCompletion = (redirectUrl) => {
            const elapsed = performance.now() - streamStartedAt;
            const sinceLastProgress = performance.now() - lastProgressAt;
            const basicWait = Math.max(MIN_PROGRESS_MS - elapsed, 0);
            const afterProgressWait = Math.max(MIN_AFTER_LAST_PROGRESS_MS - sinceLastProgress, 0);
            const waitMs = Math.max(basicWait, afterProgressWait);
            setTimeout(() => {
                const completionHtml = buildProgressHtml('Computation completed. The calculation steps can be examined below, by clicking the "Show Results" button page will be directed to the calculation results.', 'success');
                Swal.fire({
                    title: 'Computation Status',
                    html: completionHtml,
                    allowOutsideClick: false,
                    allowEscapeKey: false,
                    showConfirmButton: true,
                    confirmButtonText: 'Show Results',
                    focusConfirm: true,
                    didOpen: () => {
                        Swal.getHtmlContainer().querySelector('.live-status-log')?.scrollTo({ top: 999999, behavior: 'smooth' });
                    }
                }).then(() => {
                    window.location.href = redirectUrl;
                });
            }, waitMs);
        };

        // Build init params and request a short-lived token to avoid huge EventSource URLs
        const initParams = new URLSearchParams();
        initParams.set('manualData', manualData);
        if (fds) initParams.set('fds', fds);
        initParams.set('monteCarlo', monteCarloSelected ? 'true' : 'false');
        initParams.set('samples', samples || '100000');
        initParams.set('duplicatesRemoved', duplicatesRemoved || '0');

        fetch('/compute/stream-init', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
            body: initParams.toString()
        })
        .then(resp => {
            if (!resp.ok) throw new Error('Failed to initialize computation.');
            return resp.json();
        })
        .then(data => {
            const token = data && data.token;
            if (!token) throw new Error('Computation token is missing.');

            const source = new EventSource(`/compute/stream?token=${encodeURIComponent(token)}`);

            source.onopen = () => {
                // Connection opened
            };
            source.addEventListener('progress', event => {
                try {
                    const payload = JSON.parse(event.data);
                    appendStatus(payload.message);
                } catch (err) {
                    appendStatus(event.data);
                }
            });

            source.addEventListener('complete', event => {
                source.close();
                if (computeBtn) computeBtn.disabled = false;
                let data;
                try {
                    data = JSON.parse(event.data);
                } catch (err) {
                    data = {};
                }
                const redirectUrl = data && data.redirectUrl ? data.redirectUrl : '/calc-results';
                showCompletion(redirectUrl);
            });

            source.addEventListener('error', event => {
                let message = 'Unexpected error during computation.';
                try {
                    const payload = JSON.parse(event.data);
                    if (payload && payload.message) message = payload.message;
                } catch (err) {
                    if (event.data) message = event.data;
                }
                if (Swal.isVisible()) {
                    Swal.fire({
                        icon: 'error',
                        title: 'Computation failed',
                        text: message,
                        confirmButtonText: 'Close'
                    });
                } else {
                    Swal.fire({
                        icon: 'error',
                        title: 'Computation failed',
                        text: message,
                        confirmButtonText: 'Close'
                    });
                }
                if (computeBtn) computeBtn.disabled = false;
                source.close();
            });

            source.onerror = (event) => {
                // Only show error if not already closed by complete event
                if (source.readyState === EventSource.CLOSED) {
                    // Check if we should ignore this (might be expected after complete)
                    if (Swal.isVisible() && progressItems.some(item => item.includes('Completed'))) {
                        return;
                    }
                }

                Swal.fire({
                    icon: 'error',
                    title: 'Connection lost',
                    text: 'Connection lost while receiving computation updates. Please check if the server is running.',
                    confirmButtonText: 'Close'
                });
                if (computeBtn) computeBtn.disabled = false;
                source.close();
            };
        })
        .catch(err => {
            Swal.fire({
                icon: 'error',
                title: 'Initialization failed',
                text: err && err.message ? err.message : 'Unable to start computation.',
                confirmButtonText: 'Close'
            });
            if (computeBtn) computeBtn.disabled = false;
        });
    }
});
