#!/usr/bin/env python3
"""Post-process a fractal-eval results.json into headline + extrapolation metrics.

Reads:
  - results.json (harness output: summary + per-example runs)
  - the smart-subset jsonl (to join each example id -> meta.len_bucket / context_len)
  - (oolong only) oolong-full-meta.jsonl for the full-set length distribution

Prints a JSON blob to stdout. No secrets, no env values touched.
"""
import json, sys, collections, statistics

def load_jsonl(p):
    out=[]
    for line in open(p):
        line=line.strip()
        if line: out.append(json.loads(line))
    return out

def main():
    results_path = sys.argv[1]
    subset_path  = sys.argv[2]
    full_meta_path = sys.argv[3] if len(sys.argv) > 3 else None
    full_n = int(sys.argv[4]) if len(sys.argv) > 4 else None

    rj = json.load(open(results_path))
    label = next(iter(rj['summary']))         # 'engine'
    agg = rj['summary'][label]
    runs = rj['runs'][label]

    subset = {d['id']: d for d in load_jsonl(subset_path)}

    # per-example cost (already in run rows as :cost-usd written by fixed harness)
    per = []
    for r in runs:
        rid = r['id']
        meta = subset.get(rid, {}).get('meta', {})
        per.append({
            'id': rid,
            'cost': r.get('cost-usd'),
            'tokens': r.get('tokens-total'),
            'wall_ms': r.get('wall-ms'),
            'correct': r.get('correct?'),
            'len_bucket': meta.get('len_bucket'),
            'context_len': meta.get('context_len'),
        })

    n = len(per)
    costs = [p['cost'] for p in per if isinstance(p['cost'], (int, float))]
    total_cost = sum(costs) if costs else 0.0
    cost_per_q = total_cost / n if n else 0.0

    # per-bucket mean cost (subset)
    bucket_costs = collections.defaultdict(list)
    for p in per:
        if isinstance(p['cost'], (int, float)) and p['len_bucket']:
            bucket_costs[p['len_bucket']].append(p['cost'])
    bucket_mean = {b: (sum(v)/len(v)) for b, v in bucket_costs.items()}

    out = {
        'label': label,
        'n': n,
        'accuracy': agg.get('accuracy'),
        'numeric_accuracy_mean': agg.get('numeric-accuracy-mean'),
        'loose_accuracy_mean': agg.get('loose-accuracy-mean'),
        'n_correct': agg.get('n-correct'),
        'n_errors': agg.get('n-errors'),
        'total_cost_usd_harness': agg.get('total-cost-usd'),
        'total_cost_usd_recomputed': total_cost,
        'cost_per_q_usd': cost_per_q,
        'tokens_total': agg.get('tokens-total'),
        'wall_ms_mean': agg.get('wall-ms-mean'),
        'spent_usd': agg.get('spent-usd'),
        'stopped_early': agg.get('stopped-early?'),
        'per_example': per,
        'bucket_mean_cost': bucket_mean,
        'bucket_counts_subset': {b: len(v) for b, v in bucket_costs.items()},
    }

    # ---- extrapolation ----
    if full_meta_path and full_n:
        fm = load_jsonl(full_meta_path)
        # map full-set context_len -> our 3 sampled buckets by nearest sampled length.
        # sampled length anchors: short<=2048, medium~=32768, long>=262144.
        def bucket_of(cl):
            if cl <= 4096: return 'short'
            if cl <= 65536: return 'medium'
            return 'long'
        full_bucket = collections.Counter(bucket_of(d['context_len']) for d in fm)
        full_total = sum(full_bucket.values())
        # proportions on the meta grid, applied to canonical full_n
        full_prop = {b: full_bucket[b]/full_total for b in full_bucket}
        # flat estimate
        flat_est = cost_per_q * full_n
        # length-weighted estimate: sum over buckets of (prop*full_n * bucket_mean_cost)
        weighted = 0.0
        weighted_detail = {}
        missing = []
        for b, prop in full_prop.items():
            bn = prop * full_n
            bc = bucket_mean.get(b)
            if bc is None:
                # fall back to overall mean for buckets we didn't sample cost for
                bc = cost_per_q
                missing.append(b)
            weighted += bn * bc
            weighted_detail[b] = {'full_share': prop, 'full_n_est': bn,
                                  'subset_mean_cost': bucket_mean.get(b), 'used_cost': bc}
        out['extrapolation'] = {
            'full_n_canonical': full_n,
            'full_meta_rows': len(fm),
            'full_bucket_share': full_prop,
            'flat_estimate_usd': flat_est,
            'length_weighted_estimate_usd': weighted,
            'weighted_detail': weighted_detail,
            'buckets_without_subset_cost': missing,
        }
    elif full_n:
        out['extrapolation'] = {
            'full_n_canonical': full_n,
            'flat_estimate_usd': cost_per_q * full_n,
        }

    print(json.dumps(out, indent=2))

if __name__ == '__main__':
    main()
