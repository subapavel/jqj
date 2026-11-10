# N: non-tail recursion; the addition waits for the recursive result.
. as $a | ($a|length) as $L
| def s: if . == $L then 0 else $a[.] + (. + 1 | s) end;
  0 | s
