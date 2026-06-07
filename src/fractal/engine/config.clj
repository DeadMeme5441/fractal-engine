(ns fractal.engine.config
  "L4 · make-config (07 §1). Normalizes/validates engine config, validates the
   default capability profile, resolves + stamps :context-window via the static
   catalog, and RECORDS the adapter choice keyword — it never constructs the
   adapter instance (start-session! is the sole composition root, GD5)."
  (:require [fractal.engine.capability :as capability]
            [fractal.engine.catalog :as catalog]))

(def defaults
  {:adapter          :sdk
   :model            nil
   :fake/respond     nil
   :provider/config  nil
   :capability       :default
   :max-steps        25
   :max-turns        nil
   :call-timeout-ms  120000
   :retry            true
   :stream?          false
   :cache-ttl        "1h"
   :store            :memory       ; :memory (default/Phase 1) | :sqlite (Phase 2, durable)
   :store/dir        nil           ; REQUIRED when :store :sqlite — the dir holding events.db + blobs/
   :live/queue-bound 1024
   :live/drop        :drop-transient
   :context          {:compact-at 0.80 :hard-at 0.95 :unknown-window-chars 400000}
   :system-overlay   nil})

(defn make-config
  "Normalize + validate engine config. Returns a cfg map with the capability
   default resolved to a validated PROFILE VALUE, :context-window resolved
   (:unknown when the catalog doesn't know the model), and the adapter recorded
   as a keyword only."
  [opts]
  (let [cfg (-> (merge defaults opts)
                (update :context #(merge (:context defaults) %)))]
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
             :context-window  window))))
