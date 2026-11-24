<?php

class Report
{
    public function render(array $items): int
    {
        $sum = 0;
        foreach ($items as $i) {
            $sum += $i;
        }
        return $sum;
    }
}
