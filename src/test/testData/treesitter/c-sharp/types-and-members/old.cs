namespace Shop
{
    public interface IPriced { double Price { get; } }

    public record Item(string Name);

    public struct Point { public int X, Y; }

    public delegate void Handler(object sender);
}
