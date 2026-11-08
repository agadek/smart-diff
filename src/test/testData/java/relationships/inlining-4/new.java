class Receipt {
    String total(String customer, int amount) {
        StringBuilder text =
                new StringBuilder(customer);
        text.append(": ")
                .append(amount);
        text.append(" due");
        return text.toString() + "\n";
    }
}
