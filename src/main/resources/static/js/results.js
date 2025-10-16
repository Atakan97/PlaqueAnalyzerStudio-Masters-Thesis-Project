document.addEventListener('DOMContentLoaded', () => {
    // Check if ricMatrix is available (passed via Thymeleaf inline JS in calc-results.html)
    const ricMatrix = window.ricMatrix || [];

    // UI elements
    const showCalcBtn = document.getElementById('showCalcBtn');
    const returnBtn = document.getElementById('returnBtn'); // Geri Eklenen Buton
    const initialCalcTable = document.getElementById('initialCalcTable');
    const ricTable = document.getElementById('ricTable');
    // FD Closure Logic
    const showClosureBtn = document.getElementById('showClosureBtn');
    const fdListUl = document.getElementById('fdListUl');
    // Transitive list, data passed from Thymeleaf
    const transitiveFdsArray = window.transitiveFdsArray || [];

    if (showClosureBtn && fdListUl) {
        showClosureBtn.addEventListener('click', () => {
            // Do not run again if already clicked or disabled
            if (showClosureBtn.disabled) return;
            // Add new FDs to existing list
            const fragment = document.createDocumentFragment();
            transitiveFdsArray.forEach(fd => {
                const li = document.createElement('li');
                li.textContent = fd;
                // Red coloring
                li.classList.add('inferred');
                fragment.appendChild(li);
            });
            fdListUl.appendChild(fragment);

            // Once the process is complete, completely remove the button from the DOM
            showClosureBtn.remove();
        });
    }

    // Function that calculates HSL lightness adjustment (Plaque Coloring)
    function getPlaqueColor(value) {
        // value: 0..1, 1 => no plaque (white)
        const darkness = Math.max(0, Math.min(1, 1 - value));
        // map darkness to lightness: light (85%) .. dark (30%)
        const lightness = 85 - 55 * darkness; // 85..30
        return `hsl(220, 85%, ${lightness}%)`;
    }

    // Applying color scale
    function applyColorScale(tableSelector) {
        if (!ricMatrix || !ricMatrix.length) return;
        document.querySelectorAll(`${tableSelector} tbody tr`)
            .forEach((tr, i) => {
                const ricRow = ricMatrix[i];
                if (!Array.isArray(ricRow)) return;
                Array.from(tr.cells).forEach((td, j) => {
                    const raw = ricRow[j];
                    if (raw == null) return;
                    const val = parseFloat(raw);

                    // Apply color only if value is between 0 and 1 (exclusive of 1)
                    if (!isNaN(val) && val >= 0 && val < 1) {
                        td.style.backgroundColor = getPlaqueColor(val);
                        td.classList.add('plaque-cell');

                        if (val < 0.5) {
                            td.style.color = '#ffffff';
                        } else {
                            td.style.color = '';
                        }
                    } else {
                        // Reset background color for RIC >= 1
                        td.classList.remove('plaque-cell');
                        td.style.backgroundColor = '';
                        td.style.color = '';
                    }
                });
            });
    }

    // Button and Table Toggle Logic
    if (showCalcBtn && returnBtn && initialCalcTable && ricTable) {
        // Initial state
        ricTable.style.display = 'none';
        returnBtn.style.display = 'none';
        showCalcBtn.style.display = 'inline-block';
        // Initial color application (on load)
        applyColorScale('#initialCalcTable');
        // Button listener, show ric matrix
        showCalcBtn.addEventListener('click', () => {
            initialCalcTable.style.display = 'none';
            ricTable.style.display = 'table';
            showCalcBtn.style.display = 'none';
            returnBtn.style.display = 'inline-block';
            // Apply color scale to RIC table when showing it
            applyColorScale('#ricTable');
        });
        // Button listener, return to initial data
        returnBtn.addEventListener('click', () => {
            ricTable.style.display = 'none';
            initialCalcTable.style.display = 'table'; // Show initial data table
            returnBtn.style.display = 'none';
            showCalcBtn.style.display = 'inline-block'; // Show showCalcBtn
            // Apply color scale to initial table
            applyColorScale('#initialCalcTable');
        });
    } else {
        console.warn('Buttons or tables not found on the results page. Check element IDs.');
    }
});