<?php

class Post
{
    use Timestamps, SoftDeletes;

    public string $title = '';
}
