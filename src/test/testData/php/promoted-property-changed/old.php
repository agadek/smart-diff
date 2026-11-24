<?php

final class User
{
    public function __construct(
        private string $name,
        private int $age = 0,
    ) {
    }
}
