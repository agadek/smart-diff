namespace Shop
{
    public class Cart
    {
        private int count;

        public int Total(int price)
        {
            return price * count + 1;
        }

        public string Name() => "cart";
    }
}
