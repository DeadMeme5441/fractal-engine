(ns fractal-engine.cli
  (:require [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.inspect :as inspect]
            [fractal-engine.process :as process]
            [fractal-engine.resume :as resume]
            [fractal-engine.scripts :as scripts]
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

(defn cfg-from-opts [opts]
  (let [provider (keyword (or (:provider opts) "scripted"))
        model (or (:model opts) "scripted")
        leaf-provider (keyword (or (:leaf-provider opts) (name provider)))
        leaf-model (or (:leaf-model opts) model)
        child-provider (keyword (or (:child-provider opts) (name provider)))
        child-model (or (:child-model opts) model)
        script-name (:fake-script opts)
        response-fn (scripts/response-fn-for script-name)
        script (when (and script-name (nil? response-fn))
                 (atom (vec (scripts/script-for script-name))))]
    (process/config
     (cond-> {:runs-dir (or (:runs-dir opts) "runs")
              :models {:root {:provider provider :model model}
                       :leaf {:provider leaf-provider :model leaf-model}
                       :child {:provider child-provider :model child-model}}}
       (:max-turns opts) (assoc :max-turns (parse-long-opt (:max-turns opts)))
       (:max-fanout opts) (assoc :max-fanout (parse-long-opt (:max-fanout opts)))
       (:call-timeout-ms opts) (assoc :call-timeout-ms (parse-long-opt (:call-timeout-ms opts)))
       response-fn (assoc :scripted/response-fn response-fn)
       script (assoc :scripted/responses script)))))

(defn session-start-opts [cfg opts]
  (if-let [sid (:session opts)]
    {:id sid
     :dir (artifacts/path (:runs-dir cfg) sid)}
    {}))

(defn usage-line
  "A compact, always-visible spend summary read from the materialized usage.edn —
  the answer to runaway worry is seeing the numbers, not capping them."
  [dir]
  (when dir
    (let [u (artifacts/read-edn-file (artifacts/path dir "usage.edn") nil)
          tree (:usage/total-tree u)
          cost (get-in u [:cost/total-tree :cost/usd])
          kn (fn [m] (if (= :known (:status m)) (:known m) "?"))]
      (when tree
        (format "Usage: %s calls  tokens in=%s out=%s total=%s  cost=%s"
                (str (:call/total-tree-count tree (:call/count tree)))
                (str (kn (:tokens/input tree)))
                (str (kn (:tokens/output tree)))
                (str (kn (:tokens/total tree)))
                (if (= :known (:status cost))
                  (format "$%.4f" (double (:known cost)))
                  (str "(" (name (get-in u [:cost/total-tree :cost/status] :unknown)) ")")))))))

(defn print-result [result]
  (println "Session:" (str (:dir result)))
  (when (:turn-id result)
    (println "Turn:" (:turn-id result)))
  (println "Status:" (:status result))
  (when (contains? result :final-value)
    (println "Final:" (pr-str (:final-value result))))
  (when-let [line (usage-line (:dir result))]
    (println line)))

(defn run-command [opts]
  (let [cfg (cfg-from-opts opts)
        s (session/start-session! cfg (session-start-opts cfg opts))
        result (session/run-turn! s (or (:question opts) "Return a compact final value."))]
    (session/stop-session! s)
    (print-result result)))

(defn chat-session [opts]
  (let [cfg (cfg-from-opts opts)]
    (if-let [dir (:dir opts)]
      (session/resume-session! cfg dir)
      (session/start-session! cfg (session-start-opts cfg opts)))))

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
                      (println "Final:" (pr-str (:final-value result))))
                    (when-let [line (usage-line (:dir result))]
                      (println line)))
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
  (println "  run --question TEXT [--session ID] [--runs-dir DIR] [--provider openai --model MODEL] [--leaf-provider openai --leaf-model MODEL] [--child-provider openai --child-model MODEL] [--max-turns N] [--max-fanout N] [--fake-script simple]")
  (println "  chat [--session ID] [--runs-dir DIR] [--dir runs/session-id] [--fake-script multi-turn-chat]  # /send submits a message")
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
