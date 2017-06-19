# text-unquote

[![Clojars Project](https://img.shields.io/clojars/v/com.hypirion/text-unquote.svg)](https://clojars.org/com.hypirion/text-unquote)

A small Clojure library which can be used for inline evaluation or as a basis
for a templating library/other tool.

**The examples provided are _not_ secure for general purpose input. Please read**
**the section about security before exposing this to potentially unsafe input.**

## Quickstart

text-unquote consists of a single namespace, which you can use as follows:

```clj
(require '[com.hypirion.text-unquote :as tu])
```

How you would use the library depends on what the templating is designed to do,
and whether there's any need for security or not. For extremely basic (and
unsafe!) use, you can use `render-string`:

```clj
user=> (tu/render-string "1 + 2 = ~(+ 1 2)" eval)
"1 + 2 = 3"
user=> (tu/render-string "~@(map #(format \"%03d\" %) (range 1 4))" eval)
"001002003"
user=> (defn remove-space [s] (.replace s " " ""))
#'user/remove-space
user=> (tu/render-string "~#(remove-space this is an 'inlined' string)" eval)
"thisisan'inlined'string"
```

`render-string` takes a string and an evaluator function, and returns the
rendered output string. The evaluator function is given different forms and is
asked to evaluate it. For example, in the previous examples, we had the forms
`(+ 1 2)`, `(map #(format "%03d" %) (range 1 4))` and
`(remove-space "this is an 'inlined' string")`.

There are three different ways to signal to the reader that the upcoming piece
of text is a form: Through `~`, `~@` and `~#`. `~` tells the reader that
whatever comes next is treated as if read through `clojure.core/read`. `~@`
performs the same action, but the result will be considered a splice-unquote
instead (Usually `~@` is not that useful unless it's used to define new
variables and the like. See below for more info.).

Finally, we have `~#`. It is a special form which give you the option to treat
the upcoming form as a "recursive" form: That is, if you do
```clj
(tu/render-string "~#(foo ...)" eval)
```
then (in this context) that can be considered like performing
```clj
(tu/render-string "~(foo (tu/render-string \"...\" eval))" eval)
```

This may look weird at first, but can be useful for libraries that build upon
text-unquote. For example, if you have a document rendering library, you could
do

```
And as we can see from this, the performance decreases considerably with larger
inputs~#(footnote It's ~#(emph slightly) unfortunate, but there are ways around
this issue. See for example ~#(link http://www.example.com/bar)), ...
```

## Escaping

There are three escape forms in text-unquote:

* `~~`, which escapes a tilde
* `~)`, which escapes a closing paren
* `~]`, which escapes a closing bracket

The rationale for escaping closing parens and brackets is to support easy ways
to include them in recursive forms:

```
~#(whisper And then the (rather large~) spider came along)
```

That being said, escaping works anywhere:

```
user=> (tu/render-string "~) works fine outside recursive forms,
  #_=>   but you may just as well use )" eval)
") works fine outside recursive forms,\n  but you may just as well use )"
```

Since both `~@` and `~#` start with symbols that are legal in the beginning of
Clojure forms, you can put whitespace between the tilde and the symbol to get
derefs and tagged literals inside your expression:

```clj
user=> (def counter (atom 2))
#'user/counter
user=> (tu/render-string "There are ~ @counter remaining task(s)" eval)
"There are 2 remaining task(s)"
user=> (tu/render-string "The time is ~ #inst \"1985-04-12T23:20:50.52Z\"" eval)
"The time is #inst \"1985-04-12T23:20:50.520-00:00\""
;; This is a silly example in this case, but can be useful in markup
;; libraries. Read the section "Using text-unquote as a markup basis"
```

You can also use `deref` by just calling `deref` directly:

```clj
user=> (tu/render-string "There are ~(deref counter) remaining task(s)" eval)
"There are 2 remaining task(s)"
```

## Changing Special Symbols

The special symbols can be changed by binding the dynamic variables
`*unquote-char*` (`~`), `*splice-char*` (`@`) and `*recur-char*` (`#`):

```clj
user=> (binding [tu/*unquote-char* \$
  #_=>           tu/*recur-char* \&]
  #_=>   (tu/render-string "$&(str $$ $(+ 1 2))" eval))
"$ 3"
```

This comes with some caveats: The symbols should be distinct and should not be
Clojure delimiters. Havoc will occur if you attempt to do something funny around
this.

## Namespacing

In certain cases, it may be useful to run the evaluated forms in temporary
namespaces. text-unquote provides a macro and a function for doing just
that: `with-tmp-ns` and `eval-in-ns`. They can be used as follows:

```clj
(tu/with-tmp-ns [s]
  (tu/eval-in-ns s '(clojure.core/refer-clojure))
  ;; ^ To let clojure.core definitions be easily accessible
  (tu/render-string "
~@(do (defonce counter (atom 0))
      (defn inc-ref! [] (swap! counter inc))
      nil)
We can count, starting at ~(inc-ref!) and moving up to ~(inc-ref!)"
                    #(tu/eval-in-ns s %)))
```

which will return the string `"\n\nWe can count, starting at 1 and moving up to 2"`

While this doesn't completely isolate evaluation, it allows the rendered
text to define temporary values via def/defn and use them.

## Security

If you need to handle unknown input from potentially malicious users, use
[clojail](https://github.com/Raynes/clojail). Here's a minimal working snippet
on how you can use text-unquote with a sandbox:

```clj
(require '[clojail.core :as jail]
         '[clojail.testers :as testers])

(defn evaluate-markup [the-string]
  (tu/with-tmp-ns [s]
    (let [sb (jail/sandbox testers/secure-tester :namespace s)]
      (tu/render-string the-string sb))))
```

Please read through the Clojail documentation and set it up properly if you need
to expose text-unquote to unknown input.

## Using text-unquote as a markup basis

TODO

## License

Copyright © 2016-2017 Jean Niklas L'orange

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
