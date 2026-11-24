public class Report
{
    public string Render(string[] lines)
    {
        var text = "Report\n";
        foreach (var line in lines)
        {
            text += "- " + line.Trim() + "\n";
        }
        text += "End of report with " + lines.Length + " lines\n";
        return text;
    }
}
