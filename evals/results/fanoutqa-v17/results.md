# Eval results

Benchmark: **fanoutqa**  ·  split:   ·  engine 78444eedd7261ef344998911f66a1918bfbe6e6e (prompt v17)

| run | n | correct | accuracy | num-acc | loose-acc | cost | tokens | mean ms |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| engine | 15 | 8 | 0.533 | — | 0.695 | $4.5360 | 7129764 | 91577.533 |

Reproduce: `clojure -M:evals run --runs-dir evals/results/fanoutqa-v17/runs --leaf-model gemini-3.1-flash-lite-preview --child-provider vertex-gemini --leaf-provider vertex-gemini --child-model gemini-3.5-flash --mode engine --benchmark fanoutqa --out evals/results/fanoutqa-v17 --call-timeout-ms 180000 --max-turns 1000000 --budget-usd 50 --provider vertex-gemini --data evals/data/fanoutqa-smart.jsonl --model gemini-3.5-flash`
