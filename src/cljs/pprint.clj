
(ns clojure.pprint)


;; required the following changes:
;;  replace .ppflush with -ppflush to switch from Interface to Protocol

(defmacro with-pretty-writer [base-writer & body]
  `(let [base-writer# ~base-writer
         new-writer# (not (pretty-writer? base-writer#))]
     (binding [*out* (if new-writer#
                      (make-pretty-writer base-writer# *print-right-margin* *print-miser-width*)
                      base-writer#)]
       ~@body
       (-ppflush *out*))))


(defmacro getf
  "Get the value of the field a named by the argument (which should be a keyword)."
  [sym]
  `(~sym @@~'this))

;; change alter to swap!

(defmacro setf
  "Set the value of the field SYM to NEW-VAL"
  [sym new-val]
  `(swap! @~'this assoc ~sym ~new-val))