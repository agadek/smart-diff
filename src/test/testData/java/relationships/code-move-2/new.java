class Report {
    void process(List<Order> orders) {
        double sum = 0;
        for (Order order : orders) {
            sum += order.total();
        }
        this.sum = sum;
    }

    void finish() {
        writer.flush();
        for (Order order : orders) {
            logger.info("processed order " + order.id() + " for " + order.customer());
        }
        writer.close();
    }
}
