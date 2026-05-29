(ns build
  "Uberjar build for the `fractal` CLI. AOT-compiles the gen-class main and bundles
  all deps (lean — base engine + cheshire, no TUI/fulcro). Run: clojure -T:build uber"
  (:require [clojure.tools.build.api :as b]))

(def ^:private class-dir "target/classes")
(def ^:private uber-file "target/fractal.jar")
(defn- basis [] (b/create-basis {:project "deps.edn"}))

(defn clean [_] (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/compile-clj {:basis (basis) :ns-compile '[fractal-engine] :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis (basis)
           :main 'fractal-engine})
  (println "built" uber-file))
