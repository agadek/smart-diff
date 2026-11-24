<?php

namespace Shop;

use InvalidArgumentException;

class Order
{
    private int $id;

    private array $items = [];

    public function __construct(int $orderId)
    {
        $this->id = $orderId;
    }

    public function total(): int
    {
        $sum = 0;
        foreach ($this->items as $item) {
            $sum += $item->price;
        }
        return $sum;
    }

    public function describe(): string
    {
        return sprintf('order %d', $this->id);
    }
}
