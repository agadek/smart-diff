<?php

class Report
{
    public function render(array $items): int
    {
        return $this->sumItems($items);
    }

    private function sumItems(array $items): int
    {
        $sum = 0;
        foreach ($items as $i) {
            $sum += $i;
        }
        return $sum;
    }
}
