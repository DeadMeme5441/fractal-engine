(ns fractal.engine.config
  "L4 · make-config (07 §1). Normalizes/validates engine config, validates the
   default capability profile, resolves + stamps :context-window via the static
   catalog, and RECORDS the adapter choice keyword — it never constructs the
   adapter instance (start-session! is the sole composition root, GD5)."
  (:require [fractal.engine.capability :as capability]
            [fractal.engine.catalog :as catalog]
            [fractal.engine.prompt :as prompt]
            [fractal.engine.surface :as surface]
            [clojure.string :as str]))

(def defaults
  {:adapter          :sdk
   :model            nil
   :provider         nil           ; EXPLICIT provider override (Phase 3). When set it
                                   ; WINS over catalog model-id resolution — required for
                                   ; codex OAuth, whose model ids catalog-resolve to :openai
                                   ; (05/GD gotcha). nil ⇒ resolve from the model id.
   :fake/respond     nil
   :provider/config  nil
   :capability       :default
   ;; Phase 3 · the harness switch (the hot-swap). Drives BOTH the system prompt AND
   ;; which engine host fns are assembled, coherently:
   ;;   :clojure (default) = the Phase-1/2 plain coding harness — engine-fns #{:FINAL
   ;;             :inspect}, the Phase-1 prompt. Byte-for-byte Phase-1/2 behavior.
   ;;   :rlm     = the rlm-native recursive harness — recursive fns injected (gated by the
   ;;             capability profile's :engine-fns) + the v24 recursion doctrine prompt.
   ;; Switching is CONFIG-ONLY (zero code edits). Default :clojure keeps Phase-1/2 default.
   :harness          :clojure
   ;; Phase 3 · leaf/child model + provider resolution (defaults to the root model/provider).
   :leaf-model       nil           ; nil ⇒ the root :model
   :leaf-provider    nil           ; nil ⇒ the resolved root provider
   :child-model      nil           ; nil ⇒ the root :model
   :child-provider   nil           ; nil ⇒ the resolved root provider
   :max-fanout       50            ; the ≤50 fan-out cap (map-lm/map-rlm); over ⇒ chunk
   :fanout-pool      16            ; max worker threads per fan-out call (bounded pool)
   :leaf-concurrency 8             ; the GLOBAL leaf semaphore size (avoids rate-limit storms)
   :max-steps        25
   :max-turns        nil
   :call-timeout-ms  120000
   :retry            true
   :stream?          false
   :cache-ttl        "1h"
   :store            :memory       ; :memory (default/Phase 1) | :sqlite (Phase 2, durable)
   :store/dir        nil           ; REQUIRED when :store :sqlite — the dir holding events.db + blobs/
   :writer-lease-ttl-ms nil        ; bj1 · staleness window for the sqlite advisory writer
                                   ; lease; nil ⇒ the store default (10 min)
   :writer-lease-steal? false      ; bj1 · explicit crashed-writer escape hatch: take ANY
                                   ; foreign lease (operator asserts the prior writer is dead)
   :live/queue-bound 1024
   :live/drop        :drop-transient
   :context          {:compact-at 0.80 :hard-at 0.95 :unknown-window-chars 400000}
   ;; the observation window (fit-or-stub caps, chars). THE blind-root lever:
   ;; how much of a return value the model SEES before it must inspect/slice.
   :observe          {:ok-fit 400 :final-fit 1200}
   :system-overlay   nil
   :surfaces         []
   ;; 37i · embedder-injected base doctrine: {:doctrine/name kw :doctrine/version
   ;; pos-int :doctrine/text str} REPLACES the harness-selected base prompt for
   ;; this session (and, via cfg inheritance, its children). Stamped at config
   ;; time (prompt/stamp-prompt) so injected doctrine is as auditable as baked
   ;; doctrine; the stamp feeds the session's bundle identity. nil ⇒ built-in.
   :doctrine         nil
   ;; jz3b · surface-call recording/replay (see surface.clj):
   :surface/record?      false  ; record every surface call as a durable :surface/called event
   :surface/replay-calls nil})  ; recorded calls (fe/surface-calls) to serve instead of live fns

(defn- normalize-doctrine
  "Validate + stamp a :doctrine override. Accepts {:doctrine/name kw
   :doctrine/version pos-int :doctrine/text str}; returns the stamped prompt map
   ({:prompt/name :prompt/version :prompt/hash :prompt/text}). nil passes."
  [d]
  (when d
    (when-not (map? d)
      (throw (ex-info ":doctrine must be a map of :doctrine/name, :doctrine/version, :doctrine/text"
                      {:error/type :config/invalid-doctrine :doctrine d})))
    (let [{:doctrine/keys [name version text]} d]
      (when-not (keyword? name)
        (throw (ex-info ":doctrine/name must be a keyword"
                        {:error/type :config/invalid-doctrine :doctrine/name name})))
      (when-not (pos-int? version)
        (throw (ex-info ":doctrine/version must be a positive integer"
                        {:error/type :config/invalid-doctrine :doctrine/version version})))
      (when-not (and (string? text) (not (str/blank? text)))
        (throw (ex-info ":doctrine/text must be a non-blank string"
                        {:error/type :config/invalid-doctrine})))
      (prompt/stamp-prompt name version text))))

(defn- validate-surface-replay!
  "jz3b · :surface/replay-calls must be a vector of recorded call maps (the
   fe/surface-calls shape), and is mutually exclusive with :surface/record?."
  [cfg]
  (let [calls (:surface/replay-calls cfg)]
    (when (some? calls)
      (when-not (and (vector? calls) (every? #(and (map? %) (symbol? (:surface/function %))) calls))
        (throw (ex-info ":surface/replay-calls must be a vector of recorded surface-call maps"
                        {:error/type :config/invalid-surface-replay})))
      (when (:surface/record? cfg)
        (throw (ex-info ":surface/record? and :surface/replay-calls are mutually exclusive"
                        {:error/type :config/invalid-surface-replay}))))))

(defn make-config
  "Normalize + validate engine config. Returns a cfg map with the capability
   default resolved to a validated PROFILE VALUE, :context-window resolved
   (:unknown when the catalog doesn't know the model), and the adapter recorded
   as a keyword only."
  [opts]
  (let [cfg (-> (merge defaults opts)
                (update :context #(merge (:context defaults) %))
                (update :observe #(merge (:observe defaults) %))
                (update :surfaces surface/normalize-surfaces)
                (update :doctrine normalize-doctrine))]
    (validate-surface-replay! cfg)
    (when-not (and (pos-int? (get-in cfg [:observe :ok-fit]))
                   (pos-int? (get-in cfg [:observe :final-fit])))
      (throw (ex-info ":observe :ok-fit/:final-fit must be positive integers"
                      {:error/type :config/invalid-observe :observe (:observe cfg)})))
    (when-not (#{"5m" "1h"} (:cache-ttl cfg))
      (throw (ex-info "invalid :cache-ttl (only \"5m\" or \"1h\")"
                      {:error/type :config/invalid-cache-ttl :cache-ttl (:cache-ttl cfg)})))
    (when-not (#{:sdk :fake} (:adapter cfg))
      (throw (ex-info "invalid :adapter (only :sdk or :fake)"
                      {:error/type :config/invalid-adapter :adapter (:adapter cfg)})))
    (when (and (= :fake (:adapter cfg)) (nil? (:fake/respond cfg)))
      (throw (ex-info ":adapter :fake requires :fake/respond"
                      {:error/type :config/missing-responder})))
    (when (nil? (:model cfg))
      (throw (ex-info ":model is required" {:error/type :config/missing-model})))
    (when-not (#{:clojure :rlm} (:harness cfg))
      (throw (ex-info "invalid :harness (only :clojure or :rlm)"
                      {:error/type :config/invalid-harness :harness (:harness cfg)})))
    (when-not (#{:memory :sqlite} (:store cfg))
      (throw (ex-info "invalid :store (only :memory or :sqlite)"
                      {:error/type :config/invalid-store :store (:store cfg)})))
    (when (and (= :sqlite (:store cfg)) (nil? (:store/dir cfg)))
      (throw (ex-info ":store :sqlite requires :store/dir (the durable storage directory)"
                      {:error/type :config/missing-store-dir})))
    (let [profile (capability/validate-profile! (capability/resolve-profile (:capability cfg)))
          window  (or (catalog/context-window (:model cfg)) :unknown)]
      (assoc cfg
             :capability      profile
             :capability/name (:capability/name profile)
             :context-window  window
             ;; resolve leaf/child model defaults up front so recursion never has to
             ;; re-derive them (provider defaults stay nil here — resolved at spawn time
             ;; against the root provider, since :fake/sdk resolution differs).
             :leaf-model      (or (:leaf-model cfg) (:model cfg))
             :child-model     (or (:child-model cfg) (:model cfg))))))
