# E: self call inside "for each result" (jq's own recurse/1 shape): the call runs once per result of f.
. as $a | ($a|length) as $L
| def r(f): def rr: ., (f | rr); rr;
  reduce (0 | r(if . < $L - 1 then . + 1 else empty end)) as $i (0; . + $a[$i])
