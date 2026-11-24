<?php

function sum(array $values): int
{
    $total = 0;
    foreach ($values as $v) {
        $total += (int) $v;
    }
    return $total;
}

function second(array $values): int
{
    $count = 0;
    foreach ($values as $v) {
        $count++;
    }
    return $count;
}
