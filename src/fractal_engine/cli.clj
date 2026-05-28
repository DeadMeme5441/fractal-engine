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
          (if (or (nil? v) (str/starts-with? v "--"))
            (recur (rest xs) (assoc m (keyword (subs k 2)) true))
            (recur more (assoc m (keyword (subs k 2)) v)))
          (recur (rest xs) m))))))

(defn parse-long-opt [v]
  (when (and v (not= true v))
    (Long/parseLong (str v))))

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
    "fake-source" ["```clojure\n(def x 42)\n(FINAL {:x x})\n```"]
    "fake-resume" ["```clojure\n(FINAL {:resumed x :plus-one (inc x)})\n```"]
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

(def quit-commands #{".quit" ":quit" "/quit" "/exit"})

(defn read-chat-message []
  (loop [lines []]
    (if-let [line (read-line)]
      (let [trimmed (str/trim line)]
        (cond
          (and (empty? lines) (contains? quit-commands trimmed)) :quit
          (= "/send" trimmed) (str/join "\n" lines)
          :else (recur (conj lines line))))
      (when (seq lines)
        (str/join "\n" lines)))))

(defn chat-command [opts]
  (let [s (chat-session opts)]
    (println "Session:" (str (:dir s)))
    (println "Enter a message, finish with /send on its own line. Use /exit on an empty prompt to quit.")
    (loop []
      (let [message (read-chat-message)]
        (cond
          (nil? message) (session/stop-session! s)
          (= :quit message) (session/stop-session! s)
          :else (do
                  (let [result (session/run-turn! s message)]
                    (println "Turn:" (:turn-id result))
                    (if (= :error (:status result))
                      (println "Error:" (pr-str (:error result)))
                      (println "Final:" (pr-str (:final-value result)))))
                  (recur)))))))

(defn resume-command [opts]
  (if (:chat opts)
    (chat-command opts)
    (print-result (resume/resume! (cfg-from-opts opts)
                                  (:dir opts)
                                  (or (:question opts) "Continue and call FINAL.")
                                  (cond-> {}
                                    (:turn opts) (assoc :turn (parse-long-opt (:turn opts)))
                                    (:session opts) (assoc :id (:session opts))
                                    (:new-dir opts) (assoc :dir (:new-dir opts)))))))

(defn inspect-command [opts]
  (if (:json opts)
    (do (prn (inspect/structured (:dir opts) {:tree (:tree opts)
                                              :snapshots (:snapshots opts)
                                              :handles (:handles opts)})))
    (print (inspect/summary-string (:dir opts) opts))))

(defn usage []
  (println "fractal-engine commands:")
  (println "  run --question TEXT [--provider openai --model MODEL] [--leaf-provider openai --leaf-model MODEL] [--child-provider openai --child-model MODEL] [--fake-script simple]")
  (println "  chat [--dir runs/session-id] [--fake-script multi-turn-chat]  # /send submits a message")
  (println "  inspect --dir runs/session-id [--tree --snapshots --handles --json]")
  (println "  resume --dir runs/session-id --question TEXT [--turn N] [--session session-id] [--fake-script resume-use]")
  (println "  fork --dir runs/session-id --new-dir runs/session-fork --question TEXT [--turn N]")
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
      "fork" (print-result (resume/fork! (cfg-from-opts opts)
                                         (:dir opts)
                                         (:new-dir opts)
                                         (or (:question opts) "Continue.")
                                         (cond-> {}
                                           (:turn opts) (assoc :turn (parse-long-opt (:turn opts))))))
      (usage))))
