(ns cognitect.aws.jolt.provides
  "Re-declares jolt-lang/xml's javax.xml.stream shims under this project's
   :jolt/provides. aws-api's cognitect.aws.util.xml is compiled from ~/.m2,
   and jolt resolves its class references against the provides table before
   that namespace compiles, which is earlier than any require here can run.
   jolt-lang/xml registers the classes but declares none, so without this
   table the load fails with 'No dependency provides
   javax.xml.stream.XMLStreamConstants'. Delete once xml declares its own."
  (:require [jolt.xml]))
