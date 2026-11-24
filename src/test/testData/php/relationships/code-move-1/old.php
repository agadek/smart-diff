<?php

function process(Order $order): bool
{
    if (!save($order)) {
        return false;
    }
    notify($order->customer, $order->id, 'processed');
    if ($order->total <= 0 || count($order->items) === 0) {
        throw new InvalidArgumentException('invalid order');
    }
    return true;
}
