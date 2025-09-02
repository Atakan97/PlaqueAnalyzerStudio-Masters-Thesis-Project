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
    }

    // Adding and deleting manual data section
    const addRowButton = document.getElementById('addRow');
    const manualTableBody = document.getElementById('manualTable')?.querySelector('tbody');
    if (addRowButton && manualTableBody) {
        addRowButton.addEventListener('click', () => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td contenteditable></td>
                <td contenteditable></td>
                <td contenteditable></td>
                <td><button class="delManualRow">×</button></td>
            `;
            manualTableBody.appendChild(tr);
        });
        manualTableBody.addEventListener('click', e => {
            if (e.target.matches('.delManualRow')) {
                e.target.closest('tr').remove();
            }
        });
    }

    // Preview CSV files
    const csvInput = document.getElementById('csvfile');
    const csvPreview = document.getElementById('csvPreview');
    const showMoreCsv = document.getElementById('showMoreCsv');
    let fullCsvData = [];
    if (csvInput) {
        csvInput.addEventListener('change', () => {
            const file = csvInput.files[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = evt => {
                const lines = evt.target.result
                    .split(/\r?\n/)
                    .filter(l => l.trim() !== '');
                fullCsvData = lines;
                renderCsvPreview(5);
                document.getElementById('manualData').value = fullCsvData.join('\n');
            };
            reader.readAsText(file);
        });
    }
    if (showMoreCsv) {
        showMoreCsv.addEventListener('click', e => {
            e.preventDefault();
            renderCsvPreview(fullCsvData.length);
        });
    }
    function renderCsvPreview(count) {
        if (!csvPreview) return;
        csvPreview.tHead.innerHTML = '';
        let tbody = csvPreview.querySelector('tbody');
        if (!tbody) {
            tbody = document.createElement('tbody');
            csvPreview.appendChild(tbody);
        }
        tbody.innerHTML = '';
        if (fullCsvData.length === 0) return;
        const numCols = fullCsvData[0].split(',').length;
        const headers = Array.from({length: numCols}, (_, i) => 'col' + (i + 1));
        const theadRow = document.createElement('tr');
        headers.forEach(h => {
            const th = document.createElement('th');
            th.textContent = h;
            theadRow.appendChild(th);
        });
        csvPreview.tHead.appendChild(theadRow);

        fullCsvData.slice(0, count).forEach(line => {
            const tr = document.createElement('tr');
            line.split(',').forEach(val => {
                const td = document.createElement('td');
                td.textContent = val;
                tr.appendChild(td);
            });
            tbody.appendChild(tr);
        });
    }

    // Functional dependencies file preview section
    const fdFileInput = document.getElementById('fdfile');
    const fdPreview    = document.getElementById('fdPreview');
    const showMoreFd   = document.getElementById('showMoreFd');
    let fullFdData     = [];
    if (fdFileInput) {
        fdFileInput.addEventListener('change', () => {
            const file = fdFileInput.files[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = evt => {
                fullFdData = evt.target.result
                    .split(/\r?\n/)
                    .map(l => l.trim())
                    .filter(l => l !== '');
                renderFdPreview(5);
            };
            reader.readAsText(file);
        });
    }
    if (showMoreFd) {
        showMoreFd.addEventListener('click', e => {
            e.preventDefault();
            renderFdPreview(fullFdData.length);
        });
    }
    function renderFdPreview(count) {
        if (!fdPreview) return;
        const tbody = fdPreview.querySelector('tbody');
        tbody.innerHTML = '';
        fullFdData.slice(0, count).forEach(line => {
            const parts = line.split(/->|→/).map(s => s.trim());
            if (parts.length !== 2) return;
            const tr = document.createElement('tr');
            tr.innerHTML = `<td>${parts[0]}</td><td>→</td><td>${parts[1]}</td>`;
            tbody.appendChild(tr);
        });
    }

    // Switching between tabs
    function setupTab(idBtn, showId, hideId) {
        const tabBtn = document.getElementById(idBtn);
        if (!tabBtn) return;
        tabBtn.addEventListener('click', () => {
            document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            tabBtn.classList.add('active');
            document.getElementById(showId).style.display = 'block';
            document.getElementById(hideId).style.display = 'none';
        });
    }
    setupTab('tab-upload', 'section-upload', 'section-manual');
    setupTab('tab-manual', 'section-manual', 'section-upload');
    setupTab('tab-fd-manual', 'fd-manual', 'fd-upload');
    setupTab('tab-fd-upload', 'fd-upload', 'fd-manual');

    // Adding Monte Carlo optimization checkbox
    const monteCarloCheckbox = document.getElementById('mcCheckbox');
    const samples = document.getElementById('samples');
    if (monteCarloCheckbox && samples) {
        monteCarloCheckbox.addEventListener('change', () => {
            samples.disabled = !monteCarloCheckbox.checked;
        });
    }

    // Collecting data with using form submit
    const form = document.getElementById('calcForm');
    if (!form) return console.error("Form could not be found.");
    form.addEventListener('submit', e => {

        // Collecting CSV file or manual data
        let rows = fullCsvData.length
            ? fullCsvData
            : Array.from(document.querySelectorAll('#manualTable tbody tr'))
                .map(row => Array.from(row.querySelectorAll('td[contenteditable]'))
                    .map(td => td.textContent.trim()).join(','))
                .filter(r => r);

        document.getElementById('manualData').value = rows.join(';');

        // Collecting functional dependencies
        let fdLines = Array.from(document.querySelectorAll('#fdTable tbody tr'))
            .map(row => {
                const lhs = row.cells[1].textContent.trim();
                const rhs = row.cells[3].textContent.trim();
                return lhs && rhs ? `${lhs}->${rhs}` : null;
            })
            .filter(s => s);
        if (fdLines.length === 0 && fullFdData.length) {
            fdLines = fullFdData.map(l => l.replace('→','->').trim());
        }
        document.getElementById('fdsInput').value = fdLines.join(';');
    });

    // Function that calculates HSL lightness adjustment
    function getPlaqueColor(value) {
        // value: 0..1, 1 => no plaque (white)
        // darkness = 1 - value -> 0..1
        const darkness = Math.max(0, Math.min(1, 1 - value));
        // map darkness to lightness: light (85%) .. dark (30%)
        const lightness = 85 - 55 * darkness; // 85..30
        return `hsl(220, 85%, ${lightness}%)`;
    }

    // Applying color scale
    function applyColorScale(tableSelector) {
        if (!window.ricMatrix || !window.ricMatrix.length) return;
        document.querySelectorAll(`${tableSelector} tbody tr`)
            .forEach((tr, i) => {
                const ricRow = window.ricMatrix[i];
                if (!Array.isArray(ricRow)) return;
                Array.from(tr.cells).forEach((td, j) => {
                    const raw = ricRow[j];
                    if (raw == null) return;
                    const val = parseFloat(raw);
                    if (!isNaN(val) && val >= 0 && val < 1) {
                        td.style.backgroundColor = getPlaqueColor(val);
                    }
            });
        });
    }
    // After pressing compute button, coloring the initial input data table
    if (ricMatrix.length > 0 && document.getElementById('initialCalcTable')) {
        applyColorScale('#initialCalcTable');
    }

    const showButton   = document.getElementById('showCalcBtn');
    const returnButton = document.getElementById('returnBtn');
    const initial   = document.getElementById('initialCalcTable');
    const ric       = document.getElementById('ricTable');

    // Show Information Contents button adjustments:
    if (showButton && returnButton && initial && ric) {
        showButton.addEventListener('click', () => {
            initial.style.display = 'none';
            ric.style.display = 'table';
            showButton.style.display = 'none';
            returnButton.style.display = 'inline-block';
            applyColorScale('#ricTable');
        });

        // Return to Input button section
        returnButton.addEventListener('click', () => {
            ric.style.display = 'none';
            initial.style.display = 'table';
            returnButton.style.display = 'none';
            showButton.style.display = 'inline-block';
            applyColorScale('#initialCalcTable');
        });
    } else {
        console.warn('Buttons or tables not found – listeners not attached');
    }
});
