(ns dirac.tests.tasks.suite01.disabled-parinfer
  (:require [cljs.core.async :refer [<! timeout]]
            [dirac.automation :as auto :refer-macros [go-task with-devtools]]))

(go-task
  (<! (auto/open-tab-with-scenario! "normal"))
  (<! (auto/set-option! :enable-parinfer false))
  (with-devtools (<! (auto/open-dirac-devtools!))
    (auto/switch-to-console-and-wait-for-it)
    (auto/switch-to-dirac-prompt!))
  (<! (auto/set-option! :enable-parinfer true)))