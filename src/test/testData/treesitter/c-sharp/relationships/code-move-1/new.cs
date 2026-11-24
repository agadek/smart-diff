public class OrderService
{
    public void Process(Order order)
    {
        if (order.Total <= 0 || order.Items.Count == 0)
        {
            throw new InvalidOperationException("invalid order");
        }
        store.Save(order);
        notifier.Send(order.Customer, order.Id, "processed");
        audit.Record(order.Id, "processed");
    }
}
