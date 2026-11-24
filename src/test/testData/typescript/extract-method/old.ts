export class Report {
  render(rows: Row[]): string {
    let total = 0;
    for (const row of rows) {
      total += row.amount;
    }
    const header = "Total: " + total.toFixed(2);
    return header + "\n" + rows.map((row) => row.label).join("\n");
  }
}
