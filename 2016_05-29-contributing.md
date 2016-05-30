
Some possible contribution tasks

 1. More substitutes for Future callback methods. (project = trackedfuture, level = easy)

  - look at Scala Future interface: https://github.com/scala/scala/blob/2.11.x/src/library/scala/concurrent/Future.scala and fine method which accept callback and not handled in https://github.com/rssh/trackedfuture/blob/master/agent/src/main/scala/trackedfuture/agent/TFMethodAdapter.scala.  Today this can be `onSuccess` or `onFailure`
  - add an implementation of debugging variant to https://github.com/rssh/trackedfuture/blob/master/agent/src/main/scala/trackedfuture/runtime/TrackedFuture.scala
  - add a test (analogically to other tests) in example subproject

 2. Write replacement for Await.result / Await.ready which in case of timeout will also show the state of the
 Future in Await argument if this is possible. (project = trackedfuture, level = middle)

  - Add to [StackTraces](https://github.com/rssh/trackedfuture/blob/master/agent/src/main/scala/trackedfuture/runtime/StackTraces.scala) field with current future, which will set on change of thread boundaries.
  - add setting of such field during the creation of appropriate thread-local 
  - in ClassVisitor add translator for Await/result & Array/ready
  - in translated Await runtime handle timeout exception and in this handler search for currently
  set Future in Thread-Local of all threads.  
  - If found - print stacktrace of this thread as additional information.

 3. Implement select.timeout (project = scala-gopher, level=expert)  

 4. Implement lifting async throught hight-order functions  (project = scala-gopher, level=expert, size=big )  

If you decide to do something, then select timeframe, create issue and contact me
