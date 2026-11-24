public class Parser
{
    public bool Parse(string text, int value) { return false; }

    public bool Parse(string text, out int value) { value = 0; return true; }
}
