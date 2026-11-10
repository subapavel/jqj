# T: self tail call. State [i, acc]; the call is the last thing the function does.
. as $a | ($a|length) as $L
| def s: if .[0] == $L then .[1] else [.[0] + 1, .[1] + $a[.[0]]] | s end;
  [0, 0] | s
