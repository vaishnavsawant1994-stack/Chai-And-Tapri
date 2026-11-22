const NUMBER_VALUE = /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/;
const FORMULA_PREFIX = /^[\t\r\n ]*[=+@]/;
const FORMULA_MINUS_PREFIX = /^[\t\r\n ]*-/;

function csvField(value) {
  let text = value === null || value === undefined ? '' : String(value);
  if ((FORMULA_PREFIX.test(text) || FORMULA_MINUS_PREFIX.test(text)) && !NUMBER_VALUE.test(text)) text = `'${text}`;
  return `"${text.replaceAll('"', '""')}"`;
}

/** Build a self-describing CSV from the last successfully loaded owner report. */
export function buildOwnerReportCsv({ title, shopName, scope, loadedAt, metrics = [], evidence = [], sections = [] }) {
  if (!title || !Array.isArray(metrics) || !Array.isArray(evidence) || !Array.isArray(sections)) {
    throw new TypeError('Invalid owner report export.');
  }
  const rows = [
    ['Chai And Tapri owner report'],
    ['Report', title],
    ['Shop', shopName ?? 'Shop name unavailable'],
    ['Scope', scope ?? 'Scope unavailable'],
    ['Source', 'Last successfully loaded server-accepted view'],
    ['Loaded at UTC', loadedAt ?? 'Timestamp unavailable'],
    []
  ];
  if (metrics.length) {
    rows.push(['Summary', 'Value', 'Note']);
    for (const metric of metrics) rows.push([metric.title, metric.value, metric.note]);
    rows.push([]);
  }
  for (const section of sections) {
    if (!Array.isArray(section.headers) || !Array.isArray(section.rows)) throw new TypeError('Invalid report section.');
    rows.push([section.title ?? 'Report details']);
    rows.push(section.headers);
    for (const row of section.rows) {
      if (!Array.isArray(row) || row.length !== section.headers.length) throw new TypeError('Invalid report row.');
      rows.push(row);
    }
    rows.push([]);
  }
  if (evidence.length) {
    rows.push(['Evidence and limitations']);
    for (const item of evidence) rows.push([item]);
  }
  return `\uFEFF${rows.map((row) => row.map(csvField).join(',')).join('\r\n')}\r\n`;
}
