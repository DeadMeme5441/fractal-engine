(ns fractal-engine.cli
  (:require [clojure.string :as str]
            [fractal-engine.inspect :as inspect]
            [fractal-engine.process :as process]
            [fractal-engine.resume :as resume]))

(defn arg-map [args]
  (loop [xs args m {}]
    (if (empty? xs)
      m
      (let [[k v & more] xs]
        (if (str/starts-with? k "--")
          (recur more (assoc m (keyword (subs k 2)) v))
          (recur (rest xs) m))))))

(defn script-for [name]
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
    ["```clojure\n(FINAL :ok)\n```"]))

(defn response-fn-for [name]
  (case name
    "map-lm"
    (fn [request]
      (let [content (:message/content (last (:request/messages request)))]
        (cond
          (str/includes? content "map-lm")
          "```clojure\n(def answer (map-lm [{:id 1} {:id 2}] \"Return the id as EDN.\" :edn))\n(FINAL {:leaves answer})\n```"
          (str/includes? content "{:id 1}") "{:id 1}"
          (str/includes? content "{:id 2}") "{:id 2}"
          :else "{:unknown true}")))
    "map-rlm"
    (fn [request]
      (let [content (:message/content (last (:request/messages request)))]
        (cond
          (str/includes? content "child fan-out")
          "```clojure\n(def children (map-rlm [\"Return FINAL 1\" \"Return FINAL 2\"]))\n(FINAL {:children children})\n```"
          (str/includes? content "Return FINAL 1") "```clojure\n(FINAL 1)\n```"
          (str/includes? content "Return FINAL 2") "```clojure\n(FINAL 2)\n```"
          :else "```clojure\n(FINAL :unknown)\n```")))
    nil))

(defn cfg-from-opts [opts]
  (let [provider (keyword (or (:provider opts) "scripted"))
        model (or (:model opts) "scripted")
        script-name (:fake-script opts)
        response-fn (response-fn-for script-name)
        script (when (and script-name (nil? response-fn))
                 (atom (vec (script-for script-name))))]
    (process/config
     (cond-> {:models {:root {:provider provider :model model}
                       :leaf {:provider provider :model model}
                       :child {:provider provider :model model}}}
       response-fn (assoc :scripted/response-fn response-fn)
       script (assoc :scripted/responses script)))))

(defn print-result [result]
  (println "Session:" (str (:dir result)))
  (println "Status:" (:status result))
  (when (contains? result :final-value)
    (println "Final:" (pr-str (:final-value result)))))

(defn run-command [opts]
  (print-result (process/run-task! (cfg-from-opts opts) (or (:question opts) "Return a compact final value."))))

(defn resume-command [opts]
  (print-result (resume/resume! (cfg-from-opts opts) (:dir opts) (or (:question opts) "Continue and call FINAL."))))

(defn inspect-command [opts]
  (print (inspect/summary-string (:dir opts))))

(defn usage []
  (println "fractal-engine commands:")
  (println "  run --question TEXT [--provider openai --model MODEL] [--fake-script simple]")
  (println "  chat --question TEXT")
  (println "  inspect --dir runs/session-id")
  (println "  resume --dir runs/session-id --question TEXT [--fake-script resume-use]")
  (println "  fork --dir runs/session-id --new-dir runs/session-fork --question TEXT")
  (println)
  (println "The default provider is the offline scripted provider. Live providers use clojure-llm-sdk."))

(defn -main [& args]
  (let [[cmd & rest] args
        opts (arg-map rest)]
    (case cmd
      "run" (run-command opts)
      "chat" (run-command opts)
      "inspect" (inspect-command opts)
      "resume" (resume-command opts)
      "fork" (print-result (resume/fork! (cfg-from-opts opts) (:dir opts) (:new-dir opts) (or (:question opts) "Continue.")))
      (usage))))
