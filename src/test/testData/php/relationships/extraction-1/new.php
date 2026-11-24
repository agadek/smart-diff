<?php

class Order
{
    private array $items = [];

    public function render(): int
    {
        return $this->sumItems();
    }

    private function sumItems(): int
    {
        $sum = 0;
        foreach ($this->items as $item) {
            $sum += $item->price * $item->quantity;
        }
        return $sum;
    }
}
