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
        for (Line line : invoice.lines()) {
            out.append(line.description()).append(": ").append(line.amount()).append('\n');
        }
        out.append("Total: ").append(total(invoice));
        return out.toString();
    }

    public long total(Invoice invoice) {
        long sum = 0;
        for (Line line : invoice.lines()) {
            long tax = line.amount() * taxes.rate(line.category()) / 100;
            sum += line.amount() + tax;
        }
        return sum;
    }

    public boolean overdue(Invoice invoice, long today) {
        if (invoice.paid()) {
            return false;
        }
        long due = invoice.issued() + invoice.terms() * 24L * 60 * 60 * 1000;
        return today > due && invoice.reminders() < 3;
    }

    public void send(Invoice invoice, Mailer mailer) {
        mailer.send(invoice.customer(), render(invoice));
    }
}
