(ns fractal-engine.cli
  (:require [clojure.string :as str]
            [fractal-engine.inspect :as inspect]
            [fractal-engine.process :as process]
            [fractal-engine.resume :as resume]
            [fractal-engine.session :as session]))

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
    "multi-turn-chat" ["```clojure\n(def x 42)\n(FINAL {:saved x})\n```"
                       "```clojure\n(FINAL {:restored x})\n```"]
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
        leaf-provider (keyword (or (:leaf-provider opts) (name provider)))
        leaf-model (or (:leaf-model opts) model)
        child-provider (keyword (or (:child-provider opts) (name provider)))
        child-model (or (:child-model opts) model)
        script-name (:fake-script opts)
        response-fn (response-fn-for script-name)
        script (when (and script-name (nil? response-fn))
                 (atom (vec (script-for script-name))))]
    (process/config
     (cond-> {:models {:root {:provider provider :model model}
                       :leaf {:provider leaf-provider :model leaf-model}
                       :child {:provider child-provider :model child-model}}}
       response-fn (assoc :scripted/response-fn response-fn)
       script (assoc :scripted/responses script)))))

(defn print-result [result]
  (println "Session:" (str (:dir result)))
  (when (:turn-id result)
    (println "Turn:" (:turn-id result)))
  (println "Status:" (:status result))
  (when (contains? result :final-value)
    (println "Final:" (pr-str (:final-value result)))))

(defn run-command [opts]
  (let [s (session/start-session! (cfg-from-opts opts))
        result (session/run-turn! s (or (:question opts) "Return a compact final value."))]
    (session/stop-session! s)
    (print-result result)))

(defn chat-session [opts]
  (if-let [dir (:dir opts)]
    (session/resume-session! (cfg-from-opts opts) dir)
    (session/start-session! (cfg-from-opts opts))))

(defn chat-command [opts]
  (let [s (chat-session opts)]
    (println "Session:" (str (:dir s)))
    (loop []
      (if-let [line (read-line)]
        (if (#{".quit" ":quit" "/quit" "/exit"} (str/trim line))
          (session/stop-session! s)
          (do
            (let [trimmed (str/trim line)]
              (when-not (empty? trimmed)
                (let [result (session/run-turn! s trimmed)]
                  (println "Turn:" (:turn-id result))
                  (if (= :error (:status result))
                    (println "Error:" (pr-str (:error result)))
                    (println "Final:" (pr-str (:final-value result)))))))
            (recur)))
        (session/stop-session! s)))))

(defn resume-command [opts]
  (if (:chat opts)
    (chat-command opts)
    (print-result (resume/resume! (cfg-from-opts opts) (:dir opts) (or (:question opts) "Continue and call FINAL.")))))

(defn inspect-command [opts]
  (print (inspect/summary-string (:dir opts))))

(defn usage []
  (println "fractal-engine commands:")
  (println "  run --question TEXT [--provider openai --model MODEL] [--leaf-provider openai --leaf-model MODEL] [--child-provider openai --child-model MODEL] [--fake-script simple]")
  (println "  chat [--dir runs/session-id] [--fake-script multi-turn-chat]")
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
      "chat" (chat-command opts)
      "inspect" (inspect-command opts)
      "resume" (resume-command opts)
      "fork" (print-result (resume/fork! (cfg-from-opts opts) (:dir opts) (:new-dir opts) (or (:question opts) "Continue.")))
      (usage))))
