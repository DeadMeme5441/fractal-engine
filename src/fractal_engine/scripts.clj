(ns fractal-engine.scripts
  "Offline scripted-provider fixtures for `--fake-script NAME`. These are canned
  model responses used for keyless development and tests; they are NOT the engine.
  Kept out of the CLI (the entry surface) and the provider (the boundary) so each
  stays about one thing. Both the CLI and the test suite resolve scripts here.")

(defn script-for
  "An ordered vector of canned assistant responses for a named scenario, replayed
  in sequence by the scripted provider."
  [name]
  (case name
    "simple" ["```clojure\n(def x 42)\nx\n```"
              "```clojure\n(FINAL {:answer x})\n```"]
    "lm" ["```clojure\n(def answer (lm {:text \"alpha\"} \"Return a short label.\"))\n(FINAL {:leaf answer})\n```"
          "alpha-label"]
    "map-lm" ["```clojure\n(def answer (map-lm [{:id 1} {:id 2}] \"Return the id as EDN.\" :edn))\n(FINAL {:leaves answer})\n```"
              "{:id 1}"
              "{:id 2}"]
    "rlm" ["```clojure\n(def child (rlm \"Return FINAL {:child true}\"))\n(FINAL {:child child})\n```"
           "```clojure\n(FINAL {:child true})\n```"]
    "map-rlm" ["```clojure\n(def children (map-rlm [\"Return FINAL 1\" \"Return FINAL 2\"]))\n(FINAL {:children children})\n```"
               "```clojure\n(FINAL 1)\n```"
               "```clojure\n(FINAL 2)\n```"]
    "resume-setup" ["```clojure\n(def saved 99)\n(FINAL {:saved saved})\n```"]
    "resume-use" ["```clojure\n(FINAL {:restored saved})\n```"]
    "fake-source" ["```clojure\n(def x 42)\n(FINAL {:x x})\n```"]
    "fake-resume" ["```clojure\n(FINAL {:resumed x :plus-one (inc x)})\n```"]
    "multi-turn-chat" ["```clojure\n(def x 42)\n(FINAL {:saved x})\n```"
                       "```clojure\n(FINAL {:restored x})\n```"]
    ["```clojure\n(FINAL :ok)\n```"]))

(defn response-fn-for
  "Some scenarios need a content-sensitive response (the same scripted model must
  answer differently for the root prompt vs each fanned-out leaf/child). Those
  return a fn of the request; scenarios with a fixed sequence return nil."
  [name]
  (case name
    "map-lm"
    (fn [request]
      (let [content (:message/content (last (:request/messages request)))]
        (cond
          (clojure.string/includes? content "map-lm")
          "```clojure\n(def answer (map-lm [{:id 1} {:id 2}] \"Return the id as EDN.\" :edn))\n(FINAL {:leaves answer})\n```"
          (clojure.string/includes? content "{:id 1}") "{:id 1}"
          (clojure.string/includes? content "{:id 2}") "{:id 2}"
          :else "{:unknown true}")))
    "map-rlm"
    (fn [request]
      (let [content (:message/content (last (:request/messages request)))]
        (cond
          (clojure.string/includes? content "child fan-out")
          "```clojure\n(def children (map-rlm [\"Return FINAL 1\" \"Return FINAL 2\"]))\n(FINAL {:children children})\n```"
          (clojure.string/includes? content "Return FINAL 1") "```clojure\n(FINAL 1)\n```"
          (clojure.string/includes? content "Return FINAL 2") "```clojure\n(FINAL 2)\n```"
          :else "```clojure\n(FINAL :unknown)\n```")))
    nil))
