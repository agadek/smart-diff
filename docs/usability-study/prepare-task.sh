#!/usr/bin/env bash
# Prepare one usability-study task from its pinned corpus commit.
# Usage: prepare-task.sh <corpus-root> <T1..T5> <output-dir>
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <corpus-root> <T1..T5> <output-dir>" >&2
  exit 2
fi

corpus=$1
id=$2
out=$3

case "$id" in
  T1)
    repo=spring-petclinic
    commit=e0db9b184e028d41bcb626f3cbf03a942f67e104
    parent=51045d1648dad955df586150c1a1a6e22ef400c2
    file=src/main/java/org/springframework/samples/petclinic/owner/PetController.java
    ;;
  T2)
    repo=okhttp
    commit=a2cf5aa9f8711dabe514871e9dc4a9336a0e403f
    parent=5410de9760bce8719129c3d08eb19e5bace75f6e
    file=okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/RetryTlsHandshake.kt
    ;;
  T3)
    repo=zio
    commit=01ec1b457072f9ea29f70462b268d13068d69a06
    parent=bcbd0cefa72d1a06e743f12223712c0961bbb323
    file=concurrent/src/main/scala/zio/concurrent/CyclicBarrier.scala
    ;;
  T4)
    repo=scala3
    commit=878f4dba7670fa35df4388b2a434e8674df7fc34
    parent=32e78d4f88ac3084d1854d885c27991c26447f05
    file=compiler/src/dotty/tools/dotc/parsing/Parsers.scala
    ;;
  T5)
    repo=Exposed
    commit=80f98e796e6567ce86e1816f21edc9a9fe9002b3
    parent=38b0f398b3791a470425890de26b73a6b7f65c8a
    file=exposed-core/src/main/kotlin/org/jetbrains/exposed/v1/core/LiteralOp.kt
    ;;
  *)
    echo "unknown task: $id" >&2
    exit 2
    ;;
esac

repo_dir="$corpus/$repo"
if [ ! -d "$repo_dir/.git" ]; then
  echo "not a Git clone: $repo_dir" >&2
  exit 2
fi

mkdir -p "$out/$(dirname "$file")"
git -C "$repo_dir" show "$parent:$file" > "$out/$file"
git -C "$out" init -q
git -C "$out" add -A
git -C "$out" -c user.name='usability study' -c user.email='study@example.invalid' commit -qm 'task base'
git -C "$repo_dir" show "$commit:$file" > "$out/$file"

echo "Prepared $id in $out"
echo "Open $out in the IDE; Local Changes shows the one file."
echo "Do not commit or stage the task file before the participant sees it."
