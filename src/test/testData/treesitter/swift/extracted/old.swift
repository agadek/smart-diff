final class Report {
    func render(lines: [String]) -> String {
        var text = "Report\n"
        for line in lines {
            text += "- " + line.trimmingCharacters(in: .whitespaces) + "\n"
        }
        text += "End of report with \(lines.count) lines\n"
        return text
    }
}
