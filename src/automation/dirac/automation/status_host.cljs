(ns dirac.automation.status-host
  (:require [chromex.support :refer-macros [oget oset ocall oapply]]
            [chromex.logging :refer-macros [log warn error info]]
            [dirac.automation.helpers :as helpers]
            [dirac.automation.status :as status]))

(defonce current-status (atom nil))

(defn set-status! [text]
  (status/set-status! @current-status text))

(defn init-status! [id]
  (let [status-el (status/create-status! (helpers/get-el-by-id id))]
    (reset! current-status status-el)
    (set-status! "ready to run")))