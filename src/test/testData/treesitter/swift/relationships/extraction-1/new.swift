final class Report {
    func render(lines: [String]) -> String {
        var text = "Report\n"
        text += body(lines: lines)
        return text
    }

    func body(lines: [String]) -> String {
        var text = ""
        for line in lines {
            text += "- " + line.trimmingCharacters(in: .whitespaces) + "\n"
        }
        text += "End of report with \(lines.count) lines\n"
        return text
    }
}
