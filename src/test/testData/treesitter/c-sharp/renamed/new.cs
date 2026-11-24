public class Cart
{
    public int ComputeTotal(int[] prices)
    {
        var sum = 0;
        foreach (var price in prices) { sum += price; }
        return sum;
    }
}
