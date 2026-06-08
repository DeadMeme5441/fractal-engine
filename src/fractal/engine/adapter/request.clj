(ns fractal.engine.adapter.request
  "L3 · request assembly (05 §4, GD10). Kept OUT of the port ns so the port stays
   engine-dep-free; this requires cache/prompt/payload-io. `run-step!` calls
   `build-request` with the handle's store, the strong current-view, and cfg."
  (:require [clojure.string :as str]
            [fractal.engine.cache :as cache]
            [fractal.engine.payload-io :as payload-io]
            [fractal.engine.prompt :as prompt]
            [fractal.engine.store :as store]
            [fractal.engine.surface :as surface]))

(defn- system-message
  "Base doctrine ++ generated SDK surface card ++ cfg :system-overlay ++
   session :session/system-overlay (05 §4, GD32). Surface cards describe only
   functions that are both configured and capability-allowed."
  [view cfg profile]
  (let [text (->> [(prompt/system-prompt (:harness cfg))   ; the harness mode selects the base doctrine
                   (surface/prompt-card (:surfaces cfg) profile)
                   (:system-overlay cfg)
                   (:session/system-overlay (:session view))]
                  (remove str/blank?)
                  (str/join "\n\n"))]
    {:message/role :system :message/content text}))

(defn- current-turn [view]
  (last (:turns view)))

(defn- current-step [view]
  (last (:steps view)))

(defn- request-prompt-message [handle view cfg profile]
  (when handle
    (when-let [text (surface/request-prompt-card
                      (:surfaces cfg)
                      profile
                      {:handle handle
                       :session/id (:session-id handle)
                       :cfg cfg
                       :view view
                       :turn/id (:turn/id (current-turn view))
                       :step/id (:step/id (current-step view))})]
      {:message/role :user :message/content text})))

(defn- insert-before-last [messages msg]
  (cond
    (nil? msg) messages
    (seq messages) (let [v (vec messages)]
                     (vec (concat (butlast v) [msg (last v)])))
    :else [msg]))

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
  ([store view cfg]
   (build-request store view cfg (:capability cfg) nil))
  ([store view cfg profile]
   (build-request store view cfg profile nil))
  ([store view cfg profile handle]
   (let [dynamic-msg (request-prompt-message handle view cfg profile)
         messages (->> (store/kept-messages view)
                       (map #(payload-io/hydrate-message store %))
                       (map observation->user)
                       (#(insert-before-last % dynamic-msg))
                       (cons (system-message view cfg profile))
                       (mapv to-wire))]
     {:model    (:model cfg)
      :messages messages
      :cache    (cache/build-cache-opts view cfg {:dynamic-request? (some? dynamic-msg)})})))
