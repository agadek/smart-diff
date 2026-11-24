<?php

enum Suit: string
{
    case Hearts = 'H';
    case Spades = 'S';

    public function label(): string
    {
        return ucfirst($this->name);
    }
}
