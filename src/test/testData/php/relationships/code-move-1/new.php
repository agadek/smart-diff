<?php

function process(Order $order): bool
{
    if ($order->total <= 0 || count($order->items) === 0) {
        throw new InvalidArgumentException('invalid order');
    }
    if (!save($order)) {
        return false;
    }
    notify($order->customer, $order->id, 'processed');
    return true;
}
