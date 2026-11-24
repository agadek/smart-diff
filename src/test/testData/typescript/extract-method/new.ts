export class Report {
  render(rows: Row[]): string {
    const header = "Total: " + this.total(rows).toFixed(2);
    return header + "\n" + rows.map((row) => row.label).join("\n");
  }

  private total(rows: Row[]): number {
    let total = 0;
    for (const row of rows) {
      total += row.amount;
    }
    return total;
  }
}
