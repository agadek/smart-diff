namespace Shop
{
    public interface IPriced { double Price { get; } decimal Tax { get; } }

    public record Item(string Name, int Count);

    public struct Point { public int X, Y, Z; }

    public delegate void Handler(object sender, EventArgs e);

    public enum Mode { On, Off }
}
