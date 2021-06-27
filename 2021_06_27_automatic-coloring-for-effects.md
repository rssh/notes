## Problem:  automatic coloring of effect monads in [dotty-cps-async](https://github.com/rssh/dotty-cps-async)


 What is it at all, you can read https://rssh.github.io/dotty-cps-async/Features.html#automatic-coloring 
<details>
  <summary> 
     (or a quick recap here)
  </summary>
    
 If we have some async/await structure, then we should split our code technically into two parts (colors):  one works with async expressions (i.e.,, F[T]) and one - sync. (T without F).

 If we want to put asynchronous expression into synchronous function, we should write `await(expr)` instead `expr`,  for transforming asynchronous call into synchronous: 

 ```
   val c = async[F]{
        val url = "http://www.example.com"
        val data = await(api.fetchUrl("http://www.example.com"))
        val theme = api.classifyText(data)
        val dmpInfo: String = await(api.retrieveDMPInfo(url, await(theme), "1"))
        dmpInfo
     }
```

Note that setting async/await is not adding something to business logic. Let's hide awaits which can be inserted automatically:

 ```
   import cps.automaticColoring{*,given}
 
    val c = async[F]{
        val url = "http://www.example.com"
        val data = api.fetchUrl("http://www.example.com")
        val theme = api.classifyText(data)
        val dmpInfo: String = api.retrieveDMPInfo(url, theme, "1")
        dmpInfo
     }
 ```

We see - code is much more cleaner. 


</details>

Automatic coloring is easy for caching monads, like futures.  But what to do with effects monads like cats or scalaz IO, monix Task, or ziverge ZIO?

### Attempt 0: no coloring at all.

The problem -- notation becomes impractical because in programming with effect, near any action is effectful, we need to place `await` on each line of the code.

Look at the next block at code, 

```scala
    val c = async[PureEffect] {
      val logger = await(PEToyLogger.make())
      val counter = await(PEIntRef.make(-1))
      while {
        val v = await(counter.increment())
        await(logger.log(v.toString))
        if (v % 3 == 0) then
           await(logger.log("Fizz"))
        if (v % 5 == 0) then
           await(logger.log("Buzz"))
        v < 10
      } do ()
      await(logger.all())
    }
```

The ergonomic of such code style is worse than the ergonomic of `for loops,` so we need to search for the solution.

Note that automatic coloring for immediate monads (like futures) uses implicit conversions to transform `t: F[T]` into `await(t): T.`    But this will not work for effect monads since multiple awaits will trigger multiple effects evaluation.  So, code with awaits wilth multiple conversion will looks like:

```scala
    val c = async[PureEffect] {
      val logger = PEToyLogger.make()
      val counter = PEIntRef.make(-1)
      while {
        val v = await(counter).increment()
        logger.log(v.toString)
        if (await(v) % 3 == 0) then
           await(await(logger)).log("Fizz")
        if (await(v) % 5 == 0) then
           await(await(logger).log)("Buzz")
        await(v) < 10
      } do ()
      await(logger.all())
    }
```

Will increment v three times during each loop, and each time it will point to the new counter.

### Attempt 1 (published in 0.8.1)

Let us memorize all values.  If the user defines a value inside the async block, then this value will be evaluated once, and later usages of this value will not trigger reevaluation.  This works in simple cases and not hard to remember,  our FizBuff now can look as:

```scala
    val c = async[PureEffect] {
      val logger = PEToyLogger.make()
      val counter = PEIntRef.make(-1)
      while {
        val v = counter.increment()
        logger.log(await(v).toString)
        if (v % 3 == 0) then
           logger.log("Fizz")
        if (v % 5 == 0) then
           logger.log("Buzz")
        v < 10
      } do ()
      await(logger.all())
    }
``` 

But when we try to use this approach in scenarios where effects are passed to construct other effects, we start to see problematic cases.

While the semantics of `val f = effect(ref,logger); doTenTimes(v)` is different from the semantics of `doTenTimes(effect(ref,logger))`?
Can we create a solution, which will intuitively work in most cases?

### Attempt 2 (failure, implemented, not published)

When we memoize effect, we created two values each time, one for the original and the other for the memoized.  Can we use the first value for 'presence of awaits' and the second - for async construction of the other effects?

 This approach works, but the main problem -- we can't explain the type of the variable when looking at code. Each variable has two semantics, and we should deduce the kind of semantics applied from usage.  Looks good, but too much type information is hidden.

### Attempt 3: (failure, partially implemented but not published)
  
   Ok, can we create a specialized monad with the semantics of  'already memoized effect'  and using  `async[Cached[PureEffect]]` instead `async[PureEffect]` for automatically translating instances of effects into caching effect monads.  Interesting that the building of such a monad is not trivial.  Problem - when we have an expression like `val x = pureEffect(..)`, the compiler already typed variable, and we can't change this type easily.  So, we should wrap Cached[PureEffect[X]]  back into PureEffect[X].  Potentially this can be interesting, but now I have stopped when the resulting construction becomes too heavy. 

### Attempt 4: current

Let us return to a relatively lightweight solution. We can define rules for variable memoization with the help of additional preliminary analysis.   If some variable is used only in a synchronous context (i.e., via await), it should be colored as synchronous (i.e., cached). If some variable is passed to other functions as effect - it should be colored as asynchronous (i.e., uncached).   If the variable is used in both synchronous and asynchronous contexts, we can't deduce the programmer’s intention and report an error. 

   These rules are relatively intuitive and straightforward. However, as a side-effect, we catch typical errors when developers forget to specify the proper context where both synchronous and asynchronous cases are possible.

Look at the line 6 of our auto-coloer fizz-buzz:

```scala
    val c = async[PureEffect] {
      val logger = PEToyLogger.make()
      val counter = PEIntRef.make(-1)
      while {
        val v = counter.increment()
        logger.log(v.toString) // [error here]
        if (v % 3 == 0) then
           logger.log("Fizz")
        if (v % 5 == 0) then
           logger.log("Buzz")
        v < 10
      } do ()
      await(logger.all())
    }
```

Here, toString is possible for both `PureEffect[X]` and `X`, so the compiler will not insert `await` here, and the program will print the internal string representation of effect. Coloring macro will report the error here.

Also, preliminary analysis allows us to catch a situation where the variable, defined outside of the async block, is used in synchronous context more than once.	

Have ideas - let's discuss in https://github.com/rssh/dotty-cps-async/discussions/43

----------
[index](https://github.com/rssh/notes)
