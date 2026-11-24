public static class Format
{
    public static string Price(int cents)
    {
        var dollars = cents / 100;
        var rest = cents % 100;
        var padded = rest < 10 ? "0" + rest : rest.ToString();
        return "$" + dollars + "." + padded;
    }

    public static string Count(int count)
    {
        return count + " items";
    }
}
