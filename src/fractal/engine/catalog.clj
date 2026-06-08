(ns fractal.engine.catalog
  "L0 · engine-free wrapper over the SDK's STATIC model catalog (01 manifest
   note, 05 §6). These are metadata lookups against the bundled models.dev
   snapshot — they work offline and are explicitly allowed OUTSIDE the adapter
   seam (only `complete` — an actual provider call — is confined to the
   adapter). `config` resolves the model + context-window, `compaction` reads
   the window, `start-session!` resolves the provider."
  (:require [llm.sdk :as sdk]))

(defn provider-from-model-id
  "Resolve `{:model <id> :provider <keyword-or-nil>}` for a concrete model id.
   `:provider` is nil when the catalog doesn't recognise the model — callers
   (start-session!) treat the fake adapter / unknown models specially (05/07)."
  [model-id]
  {:model    model-id
   :provider (:model/provider (sdk/model-info model-id))})

(defn context-window
  "The model's context length in tokens, or nil when unknown. A nil window is
   tolerated: compaction falls back to `:unknown-window-chars` (07 §4)."
  [model-id]
  (sdk/model-context-length model-id))
