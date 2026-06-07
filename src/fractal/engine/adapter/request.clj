(ns fractal.engine.adapter.request
  "L3 · request assembly (05 §4, GD10). Kept OUT of the port ns so the port stays
   engine-dep-free; this requires cache/prompt/payload-io. `run-step!` calls
   `build-request` with the handle's store, the strong current-view, and cfg."
  (:require [clojure.string :as str]
            [fractal.engine.cache :as cache]
            [fractal.engine.payload-io :as payload-io]
            [fractal.engine.prompt :as prompt]
            [fractal.engine.store :as store]))

(defn- system-message
  "Base doctrine ++ cfg :system-overlay ++ session :session/system-overlay
   (05 §4, GD32) — the overlays specialize behavior, never add functions."
  [view cfg]
  (let [text (->> [(prompt/system-prompt)
                   (:system-overlay cfg)
                   (:session/system-overlay (:session view))]
                  (remove str/blank?)
                  (str/join "\n\n"))]
    {:message/role :system :message/content text}))

(defn- observation->user
  "Map the engine-internal :observation role to :user (+ \"Observation:\\n\"),
   on the still-namespaced shape — the adapter never sees :observation (05 §4)."
  [msg]
  (if (= :observation (:message/role msg))
    (assoc msg :message/role :user
               :message/content (str "Observation:\n" (:message/content msg)))
    msg))

(defn- to-wire
  "FINAL step (GD11): namespaced :message/* → the adapter's {:role :content}."
  [msg]
  {:role (:message/role msg) :content (:message/content msg)})

(defn build-request
  "Assemble the narrowed, text-only adapter request from the view + cfg:
   compaction-aware kept messages → hydrate content → observation→user → prepend
   the assembled system message → emit the wire shape; attach the opaque cache."
  [store view cfg]
  {:model    (:model cfg)
   :messages (->> (store/kept-messages view)
                  (map #(payload-io/hydrate-message store %))
                  (map observation->user)
                  (cons (system-message view cfg))
                  (mapv to-wire))
   :cache    (cache/build-cache-opts view cfg)})
