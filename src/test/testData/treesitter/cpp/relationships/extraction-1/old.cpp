#include <string>
#include <vector>

std::string render(const std::vector<std::string>& lines) {
    std::string text = "Report\n";
    for (const auto& line : lines) {
        text += "- " + line + "\n";
    }
    text += "End of report with " + std::to_string(lines.size()) + " lines\n";
    return text;
}
