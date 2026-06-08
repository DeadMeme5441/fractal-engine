(ns fractal.engine.compaction
  "L3 · compaction (07 §4) — v1's mechanism, adapted to SCI vars + the
   event-sourced store. `assess`/`should-compact?` estimate request tokens vs the
   model window (ceil(chars/4), `:unknown-window-chars` fallback). `compact-session!`
   summarizes the transcript via the root model into ONE continuation frame,
   snapshots the vars for durability, and appends a SINGLE `:session/compacted`
   event (no torn-write window). It does NOT restore/clear the live vars."
  (:require [clojure.string :as str]
            [fractal.engine.adapter :as adapter]
            [fractal.engine.cache :as cache]
            [fractal.engine.concurrent :as concurrent]
            [fractal.engine.kernel :as kernel]
            [fractal.engine.payload :as payload]
            [fractal.engine.payload-io :as payload-io]
            [fractal.engine.prompt :as prompt]
            [fractal.engine.store :as store]))

;; ---------------------------------------------------------------------------
;; Assess (07 §4)
;; ---------------------------------------------------------------------------

(defn- thresholds
  "Decide compact?/hard? from a total-char count vs the model window (token
   estimate = ceil(chars/4)); fall back to the char cap when the window is
   :unknown so an unknown window never disables BOTH gates (the safety net)."
  [chars cfg]
  (let [tokens (long (Math/ceil (/ chars 4.0)))
        window (:context-window cfg)
        {:keys [compact-at hard-at unknown-window-chars]} (:context cfg)]
    (if (= :unknown window)
      {:tokens tokens :window :unknown
       :ratio    (/ (double chars) unknown-window-chars)
       :compact? (> chars (* compact-at unknown-window-chars))
       :hard?    (> chars (* hard-at unknown-window-chars))}
      {:tokens tokens :window window
       :ratio    (/ (double tokens) window)
       :compact? (> tokens (* compact-at window))
       :hard?    (> tokens (* hard-at window))})))

(defn- content-or-ref-chars
  "Char count of a :message/content-or-ref without hydrating: an inline string
   is counted; a ref uses its :payload/size as a proxy."
  [content-or-ref]
  (if (payload/payload-ref? content-or-ref)
    (or (:payload/size content-or-ref) 0)
    (count (str content-or-ref))))

(defn- view-chars [view]
  (reduce + 0 (map (comp content-or-ref-chars :message/content-or-ref)
                   (store/kept-messages view))))

(defn assess
  "Estimate the assembled REQUEST and report {:tokens :window :ratio :compact?
   :hard?}. Mid-step run-step! uses :hard? only (07 §3)."
  [request cfg]
  (thresholds (reduce + 0 (map (comp count str :content) (:messages request))) cfg))

(defn should-compact?
  "Pre-turn gate (07 §2): does the current transcript exceed :compact-at?
   Estimated over the view's kept messages (no store/hydration needed)."
  [view cfg]
  (:compact? (thresholds (view-chars view) cfg)))

;; ---------------------------------------------------------------------------
;; Compact (07 §4) — the impl, called under the held turn-lock
;; ---------------------------------------------------------------------------

(defn format-transcript
  "A role-labeled transcript over hydrated :message/content (the shape the
   formatter consumes — GD41/GD11)."
  [hydrated-messages]
  (str/join "\n\n"
            (for [m hydrated-messages]
              (str "[" (name (:message/role m)) "] " (:message/content m)))))

(defn compact-session!
  "Summarize the completed transcript into ONE continuation frame and append a
   single :session/compacted event (vars-ref + the stamped compact message +
   the prune boundary). The live SCI vars are untouched (03 §6)."
  [handle]
  (let [store   (:store handle)
        sid     (:session-id handle)
        cfg     (:cfg handle)
        adapter (:adapter handle)
        view    (store/current-view store sid)
        msgs    (map #(payload-io/hydrate-message store %) (store/kept-messages view))
        req     {:model    (:model cfg)
                 :messages [{:role :system :content (prompt/compaction-prompt)}
                            {:role :user   :content (format-transcript msgs)}]
                 :cache    (cache/build-cache-opts view cfg)}
        resp    (concurrent/with-deadline (:call-timeout-ms cfg)
                  (adapter/-complete adapter req {:retry (:retry cfg) :stream? false}))
        summary (:text resp)
        snap    (kernel/snapshot-vars @(:sci-ctx handle) sid)
        vars-ref (payload-io/maybe-intern store snap {:payload/kind :vars})
        ;; the boundary == this :session/compacted event's own id (peek = assign,
        ;; safe under the held turn-lock / single writer)
        boundary (store/peek-next-id store sid :event)
        compact-msg {:message/role :user
                     :message/turn-id nil
                     :message/content-or-ref (payload-io/maybe-intern store summary {:payload/kind :message})}]
    (let [compact-ev (store/append-event! store sid
                                           {:event/type :session/compacted
                                            :vars-ref vars-ref
                                            :compact-from-event-id boundary
                                            :message compact-msg})]
      (store/publish-head! store sid
                           {:head/kind :compaction
                            :head/to-event-id (:event/id compact-ev)
                            :head/vars-ref vars-ref
                            :head/final-ref nil
                            :head/compact-from-event-id boundary}))
    handle))
