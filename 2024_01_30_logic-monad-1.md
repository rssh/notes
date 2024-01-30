
# Scala and logical monad programming.

  Here (https://github.com/rssh/dotty-cps-async/tree/master/logic) is a small library, 'dotty-cps-async-logic,' where a type class and default implementation are defined for logical programming with backtracking.


##  What is a logic monad for application developers?  

 From the name,  operations in this monad should allow us to perform logical operations over the logical computation wrapped in a monadic value.
 We can interpret the monad expression `M[A]` as a computation of some puzzle, which can have answers of type `A` 

 To use those answers, we should represent the execution result as an asynchronous stream,  which forces the computation of the following value when the previous value is consumed.  

 How to generate logical values: the puzzle has no solution, or we should sequentially review a few possible solutions.  
 Appropriative interface:

```Scala
def empty[A]: M[A]
def seqOr(x: M[A], y: M[A]): M[A]
```

The Haskell base library has two variants of standard interfaces for this:  the traditional interface is `MonadPlus`, a modern - `Alternative`[^1].

[^1]: The history behind these two names is that MonadPlus has an appropriate signature. Still, often, people think that a MonadPlust operation (seqOr in our case) should form a monoid, which is not true for the case logical search:. Details:  https://stackoverflow.com/questions/15722906/must-mplus-always-be-associative-haskell-wiki-vs-oleg-kiselyov 

In the traditional Haskell notation, `empty`  is `mzero` and `seqOr` is `mplus`.

We can represent other logical operations as operations on streams. The most useful are:

```Scala
    def filter[A](M[A])(p: A=>Boolean):M[A]
```
Or better, let us Scala3 extension syntax and get actual definitions:

```Scala
    extension[M[_]:CpsLogicMonad,A](ma: M[A])
        ....

       // processing only the value that satisfies the condition.
       def filter(p: A=>Boolean): M[A]   

       // interleaves the result streams from two choices.  Often, this operation is named `fair or`.
       def inteleave(mb: M[A]): M[A] 

       // retrieves only the first result.  This operation is often called 'cut' and associated with Prolog soft cut expression.
       def once: M[A]

       // combinator which continue evaluation via thenp if ma is not empty or return elsep
       def ifThenElse[B](thenp: a=>M[B], elsep: => M[B])

       // specify only the fail path for the computation
       def otherwise(elsep: =>M[A]) 

       .....

```

 The standard introduction of Haskell implementation is a `LogicT` article:  *Oleg Kiselyov, Chung-chieh Shan, Daniel P. Friedman, and Amr Sabry. 2005. Backtracking, interleaving, and terminating monad transformers: <https://okmij.org/ftp/papers/LogicT.pdf>*.  Sometimes it hard to understand, I reccomend to read at first *Ralf Hinze. 2000. Deriving backtracking monad transformers.  <https://doi.org/10.1145/357766.351258>* and then return to the LogicT article.

 In LogicT, all stream combination operations (i.e., `interleave`,  `once`, ) are built by using one function. `msplit`
 
 ```Scala
     def msplit: M[Option[A,M[A]]]
 ```

We can make some operations fancier by providing callable synonyms inside direct syntax. For example, we can offer the same functionality as 
 `filter` with `guard` statement.

```Scala
    inline def guard[M[_]](p: =>Boolean)(using CpsLogicMonadContext[M]): Unit
```

Note that not all logical operations are better represented as effects – for example, the monadic definition of `once` is simple and intuitive. Suppose we want to make an analog effect with a signature `def cut[M[_]](using CpsLogicContext[M]: Unit`. In that case, we will need to extend our monad with scope construction, and in all, this operation will not be intuitive and understandable without explanation.

Therefore,  both direct and monadic styles are helpful; it is better to have the ability to use both of these techniques when they are appropriate. It's why we have an `asynchronized` operator in direct style API for dotty-cps-async. 


##  Few examples?  

### List all primes:

```Scala
def primes: LogicStream[Int] = {
    eratosphen(2, TreeSet.empty[Int])
}


def eratosphen(c:Int, knownPrimes: TreeSet[Int]): LogicStream[Int] = reify[LogicStream]{
    guard(
        knownPrimes.takeWhile(x => x*x <= c).forall(x => c % x != 0)
    )
    c
} |+| eratosphen(c+1, knownPrimes + c)

```

## Eight queens:

```Scala
case class Pos(x:Int, y:Int)

def isBeat(p1:Pos, p2:Pos):Boolean =
    (p1.x == p2.x) || (p1.y == p2.y) || (p1.x - p1.y == p2.x - p2.y) || (p1.x + p1.y == p2.x + p2.y)


def isFree(p:Pos, prefix:IndexedSeq[Pos]):Boolean =
    prefix.forall(pp => !isBeat(p, pp))


def queens[M[_]:CpsLogicMonad](
   n:Int, prefix:IndexedSeq[Pos]=IndexedSeq.empty): M[IndexedSeq[Pos]] = reify[M] {
 if (prefix.length >= n) then
   prefix
 else
   val nextPos = (1 to n).map(Pos(prefix.length+1,_)).filter(pos => isFree(pos, prefix))
   reflect(queens(n, prefix :+ reflect(all(nextPos))))
}

```
# Q/A 

## What makes the monad logical and different from the other streaming monads?  

  - It should be lazy (in most cases, enumeration of all possible results will cause a combinatorial explosion).
  - It should be possible to define logical operators efficiently.

## Can we define logical monadic operations on top of the existing streaming framework? 

Yes, when the streaming library can efficiently implement concatenation. In practice – optimized `mplus` implementation is not trivial.  For example, for the synchronous variant, in the standard Scala library exists `LazyList``, where we can define all logic operations,  but running a long sequence of mplus will cause stack overflow errors. 

## But such logic programming is quite limited because it is applicable only to 'generate and apply'  algorithms.

True.   We need a notation of logical terms and unification for the beauty of an entirely logical programming environment,  which is not defined here.   Can we design a monad for this (?) –  Sure,  but this is the theme of the future blog post.

