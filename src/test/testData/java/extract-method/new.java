package billing;

import java.util.List;

public class InvoiceService {
    private final TaxTable taxes;

    public InvoiceService(TaxTable taxes) {
        this.taxes = taxes;
    }

    public String render(Invoice invoice) {
        StringBuilder out = new StringBuilder();
        out.append("Invoice ").append(invoice.number()).append('\n');
        renderLines(invoice.lines(), out);
        out.append("Total: ").append(total(invoice));
        return out.toString();
    }

    private void renderLines(List<Line> lines, StringBuilder out) {
        for (Line line : lines) {
            out.append(line.description()).append(": ").append(line.amount()).append('\n');
        }
    }

    public long total(Invoice invoice) {
        return Taxes.totalWithTax(invoice, taxes);
    }

    public boolean overdue(Invoice invoice, long today) {
        return new Reminders().isOverdue(invoice, today);
    }

    public void send(Invoice invoice, Mailer mailer) {
        mailer.send(invoice.customer(), render(invoice));
    }

    static class Reminders {
        boolean isOverdue(Invoice invoice, long today) {
            if (invoice.paid()) {
                return false;
            }
            long due = invoice.issued() + invoice.terms() * 24L * 60 * 60 * 1000;
            return today > due && invoice.reminders() < 3;
        }
    }
}

final class Taxes {
    private Taxes() {
    }

    static long totalWithTax(Invoice invoice, TaxTable taxes) {
        long sum = 0;
        for (Line line : invoice.lines()) {
            long tax = line.amount() * taxes.rate(line.category()) / 100;
            sum += line.amount() + tax;
        }
        return sum;
    }
}
