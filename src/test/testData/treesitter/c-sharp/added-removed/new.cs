public class Cart
{
    public int Count { get; set; }

    public event EventHandler Changed;

    public bool IsEmpty() => Count == 0;
}
