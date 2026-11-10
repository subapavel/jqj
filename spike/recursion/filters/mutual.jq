# M: two functions calling each other, both in tail position.
. as $a | ($a|length) as $L
| def ev: def od: if .[0] == $L then .[1] else [.[0] + 1, .[1] + $a[.[0]]] | ev end;
          if .[0] == $L then .[1] else [.[0] + 1, .[1] + $a[.[0]]] | od end;
  [0, 0] | ev
