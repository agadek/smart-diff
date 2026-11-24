#include <string>
#include <vector>

static std::string body(const std::vector<std::string>& lines) {
    std::string text;
    for (const auto& line : lines) {
        text += "- " + line + "\n";
    }
    text += "End of report with " + std::to_string(lines.size()) + " lines\n";
    return text;
}

std::string render(const std::vector<std::string>& lines) {
    std::string text = "Report\n";
    text += body(lines);
    return text;
}
