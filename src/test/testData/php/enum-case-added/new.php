<?php

enum Suit: string
{
    case Hearts = 'H';
    case Spades = 'S';
    case Clubs = 'C';

    public function label(): string
    {
        return ucfirst($this->name);
    }
}
