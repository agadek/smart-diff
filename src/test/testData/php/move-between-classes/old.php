<?php

class Point
{
    public int $x = 0;
    public int $y = 0;
    public string $label = '';
}

class Line
{
    public Point $from;
    public Point $to;
}
