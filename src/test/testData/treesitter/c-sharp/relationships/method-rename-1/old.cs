public class Stats
{
    public int Total(int[] values)
    {
        var sum = 0;
        foreach (var value in values) { sum += value; }
        return sum;
    }

    public int Largest(int[] values)
    {
        var best = int.MinValue;
        foreach (var value in values) { if (value > best) best = value; }
        return best;
    }
}
