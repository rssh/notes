## Structured concurrenty with Scala Future
  
### FutureScope

I sketched a [small API](https://github.com/rssh/dotty-cps-async/tree/master/jvm/src/test/scala/futureScope), how structured on top of plain Scala Futures can look.
The idea is to have a scope context, which can be injected into an async block, and a set of structured operations defined for scope, automatically canceled when the main execution flow is terminated.

```Scala
def  doSomething = async[Future].in(Scope){

    val firstWin = Promise[String]()
    firstWin completeWith FutureScope.spawn{
           firstActivity()
    }
    firstWin completeWith FutureScope.spawn{
          secondActivity()
    } 

    await(firstWin.future)
      //  unfinished activity will be canceled here.
}
```

`FutureScope.spawn`  is a main structured concurrency operator, fibers created by spawns is cancelled when the main flow is finished; when unhandled exception is raised inside child, wrapped exception is propagated to the main flow. 

### EventFlow

When organizing concurrent execution, we also need some mechanism for message passing between execution flows. 

`EventFlow` is an event queue that provides API for concurrent writing and linearized sequenced reading via an async iterator.  The idea of linearized representation is inspired by joinads (see http://tryjoinads.org/ ); unlike joinads, we here do not maintain the execution state but provide a linearized API for reading that allows us to restore the state.

The signature of the EventFlow follows:
```Scala
trait EventFlow[E] {

   def events: AsyncIterator[Future, E]

   def post(e: E): Unit =
      postTry(Success(e))

   def postFailure(e: Throwable): Unit =
      postTry(Failure(e))

   def postTry(v: Try[E]): Unit

   def finish(): Unit

}
```

A classical example, with the parallel search in the binary tree [], which should be finished after first success looks like:

```Scala
enum BinaryTree[+T:Ordering] {
  case Empty extends BinaryTree[Nothing]
  case Node[T:Ordering](value: T, left: BinaryTree[T], right: BinaryTree[T]) extends BinaryTree[T]

}

object BinaryTree {

  import scala.concurrent.ExecutionContext.Implicits.global

  def findFirst[T:Ordering](tree: BinaryTree[T], p: T=>Boolean): Future[Option[T]] = async[Future].in(Scope) {
    val eventFlow = EventFlow[T]()
    val runner = findFirstInContext(tree,eventFlow,p,0)
    await(eventFlow.events.next)
  }


  def findFirstInContext[T:Ordering](tree: BinaryTree[T], events: EventFlow[T], p: T=> Boolean, level: Int)(using FutureScopeContext): Future[Unit] = 
   async[Future]{
      tree match
        case BinaryTree.Empty => 
        case BinaryTree.Node(value, left, right) =>
          if (p(value)) then
            events.post(value)
          else 
            val p1 = FutureScope.spawn( findFirstInContext(left, events, p, level+1) )
            val p2 = FutureScope.spawn( findFirstInContext(right, events, p, level+1) )
            await(p1)
            await(p2)
      if (level == 0) then
        events.finish()
    }

}
```

Here, we send to events found element.  The search stops after reading the first found element (or exhausting the search tree).

### FutureGroup

Yet one practical construction is a FutureGroup, which can be built from a collection of context functions, returning Future, and an EventFlow.  When one of Future is finished, the result is sent to event flow.  Also, we can cancel or join with the execution of all futures in the group.
Consider next toy example: let we have a list of urls and want to fetch 10 pages which are readed first:

```Scala
  def readFirstN(networkApi: NetworkApi, urls: Seq[String], n:Int)(using ctx:FutureScopeContext): Future[Seq[String]] = 
    async[Future].in(Scope.child(ctx)) {
      val all = FutureGroup.collect( urls.map(url =>  networkApi.fetch(url)) )
      val successful = all.events.inTry.filter(_.isSuccess).take[Seq](n)
      await(successful).map(_.get)
    }
```

Here - FutureGroup.collect create a future group, then we read ten successful results from this group's events.  Finishing the main flow will cancel all remaining processes in the group.

### Happy Eyeball

The classical illustration of structured concurrency is an implementation of some subset of RFC8305 [Happy Eyeball](https://datatracker.ietf.org/doc/html/rfc8305), which specifies requirements for an algorithm of opening a connection to a host, which can be resolved to multiple IP addresses in multiply address families. We should open a client socket, preferring an IP6 address family (if one is available) and minimizing visual delay.

Here are implementations of two subsets:
- [HappyEyeball](https://github.com/rssh/dotty-cps-async/blob/master/jvm/src/test/scala/futureScope/examples/HappyEyeballs2.scala).  here we model choosing of address family and opening connection.
- [LiteHappyEyeball](https://github.com/rssh/dotty-cps-async/blob/master/jvm/src/test/scala/futureScope/examples/LiteHappyEyeballs.scala) – here we model only connection opening. It's interesting because we can compare it with [ZIO-based implementation of Adam Warski:](https://blog.softwaremill.com/happy-eyeballs-algorithm-using-zio-120997ba5152)
(Here, I don't want to say that one style is better than the other: my view of programming styles is not a strong preference of one over another but rather a view of the landscape, where the difference between different places is a part of beauty). 

### Sad part - why I'm writing this now:

Currently, this is just a directory inside JVM tests in dotty-cps-async.  To make this a usable library, we need to do some work:  document API, add tests, implement obvious extensions, port to js/native, etc.    
Also exists a set of choices, which can be configured and is interesting to discuss: 
- should we cancel() child context implicitly (as now), or join or ask user finish with cancel or join ?
- should we propagate exceptions or leave this duty to the user and issue a warning when see possible unhandled exception?

[Discussion](https://github.com/rssh/dotty-cps-async/discussions/57)

Now I have a minimal time budget because my attention is focused on other issues due  to well-known factors. From the other side, I see that possibility of structured concurrency with Futures can be have practical impact for choosing Scala language for a set of the projects.  So, I am throwing this into the air, hoping that somebody will find this helpful (and maybe create an open-source library, which I will be happy to contribute).  I'm planning to return to this later, but can't say when.  

P.S.  You can bring this time closer, by [donating to charity supporting Ukrainian army](https://aerorozvidka.xyz/). 


