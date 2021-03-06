(ns dirac.automation.runtime
  (:require-macros [dirac.settings :refer [get-browser-tests-dirac-agent-port]])
  (:require [chromex.logging :refer-macros [log warn error info]]
            [dirac.runtime :as runtime]
            [dirac.runtime.prefs :as runtime-prefs]))

(defn init-runtime! [& [config]]
  (runtime/set-pref! :agent-port (get-browser-tests-dirac-agent-port))
  (when-let [runtime-prefs (:runtime-prefs config)]                                                                           ; override runtime prefs
    (warn "dirac runtime override: set prefs " runtime-prefs)
    (runtime-prefs/merge-prefs! runtime-prefs))
  (if-not (:do-not-install-runtime config)                                                                                    ; override devtools features/installation
    (let [features-to-enable (cond-> []
                               (not (:do-not-enable-repl config)) (conj :repl))]
      (runtime/install! features-to-enable))
    (warn "dirac runtime override: do not install")))