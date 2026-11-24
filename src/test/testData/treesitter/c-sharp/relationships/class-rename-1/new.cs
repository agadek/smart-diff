public class Coordinate
{
    public double X { get; set; }
    public double Y { get; set; }

    public double Length()
    {
        return Math.Sqrt(X * X + Y * Y);
    }
}
