namespace Shop
{
    public class Cart
    {
        private int count;

        public int Total(int price)
        {
            return price * count;
        }

        public string Name() => "cart";
    }
}
