(ns dirac.tests.tasks.suite01.dirac-eval
  (:require [cljs.core.async :refer [<! timeout]]
            [dirac.automation :as auto :refer-macros [go-task with-devtools]]))

(def dirac-object "Object {automation: Object, tests: Object, runtime: Object, project: Object}")

(go-task
  (<! (auto/open-tab-with-scenario! "normal"))
  (with-devtools (<! (auto/open-dirac-devtools!))
    (auto/wait-switch-to-console)
    (auto/switch-to-dirac-prompt!)
    (auto/wait-for-prompt-edit)
    (auto/enable-console-feedback!)
    ; ---
    (auto/console-exec-and-wait-for-match! "(+ 1 2)" "log> 3")
    (auto/console-exec-and-wait-for-match! "(range 200)" "log> (0 1 2 3 4 …)")
    (auto/console-exec-and-wait-for-match! "(doc filter)" "log> null")
    (auto/console-exec-and-wait-for-match! "js/dirac" (str "log> " dirac-object))
    (auto/console-exec-and-wait-for-match! "(x)" ["wrn> Use of undeclared Var cljs.user/x at line 1 <dirac repl>"
                                                  "err> TypeError: Cannot read property 'call' of undefined(…)"])
    ; TODO: test this after implementing transcript filtering
    ;(auto/console-enter-and-wait! "(in-ns)" "log> 3")
    (auto/console-exec-and-wait-for-match! "(in-ns 'my.ns)" "setDiracPromptNS('my.ns')")))