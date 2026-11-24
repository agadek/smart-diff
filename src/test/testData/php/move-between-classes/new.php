<?php

class Point
{
    public int $x = 0;
    public int $y = 0;
}

class Line
{
    public Point $from;
    public Point $to;
    public string $label = '';
}
