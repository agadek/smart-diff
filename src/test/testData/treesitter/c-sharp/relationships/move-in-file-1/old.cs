public class Picker
{
    public int First(int[] values)
    {
        return values.Length > 0 ? values[0] : 0;
    }

    public int Second(int[] values)
    {
        return values.Length > 1 ? values[1] : 0;
    }

    public int Third(int[] values)
    {
        return values.Length > 2 ? values[2] : 0;
    }
}
