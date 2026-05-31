# Eval results

Benchmark: **oolong**  ·  split:   ·  engine 78444eedd7261ef344998911f66a1918bfbe6e6e (prompt v17)

| run | n | correct | accuracy | num-acc | loose-acc | cost | tokens | mean ms |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| engine | 15 | 13 | 0.867 | 0.998 | — | $3.3989 | 6721220 | 95714.000 |

Reproduce: `clojure -M:evals run --runs-dir evals/results/oolong-v17/runs --leaf-model gemini-3.1-flash-lite-preview --child-provider vertex-gemini --leaf-provider vertex-gemini --child-model gemini-3.5-flash --mode engine --benchmark oolong --out evals/results/oolong-v17 --call-timeout-ms 180000 --max-turns 1000000 --budget-usd 50 --provider vertex-gemini --data evals/data/oolong-smart.jsonl --model gemini-3.5-flash`
