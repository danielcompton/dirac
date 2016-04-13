(ns dirac.automation)

(defmacro without-transcript [& body]
  `(dirac.automation.transcript-host/without-transcript-work (fn [] ~@body)))

(defmacro go-task [& steps]
  (let [first-arg (first steps)
        config (if (map? first-arg) first-arg)
        commands (if config (rest steps) steps)]
    `(let [test-thunk# (fn []
                         (cljs.core.async.macros/go
                           (dirac.automation.task/task-started!)
                           ~@commands
                           (dirac.automation.task/task-finished!)
                           (dirac.automation.task/task-teardown!)))]
       (dirac.automation.launcher/register-task! test-thunk#)
       (dirac.automation.task/task-setup! ~config))))

(defmacro doto-devtools [devtools-id & body]
  (let [devtools-id-sym (gensym)
        sync-commands (map (fn [command] `(cljs.core.async/<! (~(first command) ~devtools-id-sym ~@(rest command)))) body)]
    `(let [~devtools-id-sym ~devtools-id]
       ~@sync-commands)))