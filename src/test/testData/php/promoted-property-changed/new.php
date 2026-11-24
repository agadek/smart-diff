<?php

final class User
{
    public function __construct(
        public readonly string $name,
        private int $age = 0,
    ) {
    }
}
