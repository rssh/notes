---
title: small type-driven dependency injection in effect systems.
---

At first, detective history with Reddit moderation:  During the previous post form this series,  it was a comment about the effect systems.  I have give two answers:  one with a technical overview a second one with the note that it is possible to use static AppContextProvider.  Few days later I accidentally sow this discussion on the other computer, where I was not logged to reddit, and discover that my first reply was deleted by moderator. Interesting, that I don’t see this if I logged by itself.  Quite strange, I guess this is automatic moderation based on some pattern. 

Ok.  Let’s adjust our type-based injection framework to the effect systems.  This text is a result of a joint work with  Ivan Kyrylov during GSoC-2024.  The main work was not about dependency injection but abstract representations of effect, but static dependency injection was a starting point for Ivan's journey.  Our first attempt was based on another approach, then here (we trying to automatically assemble typemap of needed injections,  which as state of scala-3.x is impossible,  because we can’t return context function from macros),  but during this stage we receive some understanding, what should work.

At first, what make dependency injection different in the effect system environment?
- Types of dependencies are encoded into the type of enclosing monad.
- Retrieving of dependencies can be asynchronous: 

I.e., typical usage of  dependency injection in the effect environment looks like:

## Tagless-Final style

```Scala
def newSubscriber1[F[_]:InAppContext[(UserDatabase[F],EmailService)]:Effect](user: User):F[User] = {
  for{
    db <- InAppContext.get[F, UserDatabase[F]]
    u <- db.insert(user)
    emailService <- AppContextEffect.get[F, EmailService]
    _ <- emailService.sendEmail(u, "You have been subscribed")
  } yield u
```

Here, we assume tagless-final style and dependencies `(UserDatabase[F],EmailService)` are listed as properties of `F[_]`  I.e., exists 
`InAppContext[(UserDatabase[F],EmailService)][F]`  from which we can retrieve monadized references during computations.

We can define InAppContex as a reference to the list of providers:

```Scala
type InAppContext[D <: NonEmptyTuple] =  [F[_]] =>> AppContextAsyncProviders[F,D]
```
                                        
Where AppContextAsyncProviders is constructed in the same way as `AppContextProvider` for the core case.

Before diving into details, let’s speak about the second difference:  monadic (or asynchronous) retrieving of dependencies:

```
trait AppContextAsyncProvider[F[_],T] {

   def get: F[T]

}
```
Here, the async provider returns the value wrapped in the monad. This wrapping makes sense when a monad provides additional logic necessary for the dependency lifecycle, such as acquiring or releasing a resource. Note that this form is not strictly needed because we can achieve the same logic by changing the API form.  But let’s follow traditions.

Of course,  we can make Async provider from Sync:

```Scala
given fromSync[F[_] : AppContextPure, T](using syncProvider: AppContextProvider[T]): AppContextAsyncProvider[F, T] with
 def get: F[T] = summon[Pure[F]].pure(syncProvider.get)
```

But note that for this, we should have defined somewhere `Pure` typeclass (which is absent in the Scala standard library).  Also, in theory, `syncProvider.get` can produce side effects, but developers who prefer pure functional style will choose to delay potentially effectful invocation… Yet one issue – `pure`  in cats is eager…, so maybe better wording exists…  Let’s define our generic typeclass, [AppContextPure](https://github.com/rssh/scala-appcontext/blob/59014c7aecacf81ea3fb6f9415ed603001032248/tagless-final/shared/src/main/scala/com/github/rssh/appcontext/util/AppContextPure.scala#L5) and provide implementation based on dotty-cps-async (which becomes an optional dependency). 
 If you have an idea for better wording, please write a comment. 

Ok, now return to constructing providers.
Let we have a method with signature:

```Scala
def newSubscriber1[F[_]:InAppContext[(UserDatabase[F],EmailService)]:Effect](user: User):F[User] = ...
```

When we call this method from upside of `newSubscriber` scope,  we should synthesize `AppContextProviders[F,(UserDatabase[F],EmailService)]` by searching providers for the tuple elements.
When we call this method from inside, we should resolve services if they are in the AppContextProviders tuple. At first glance, we can build macros that build AppContextProviders like in the core (described in the previous post).
But wait, there is one difference: AppContextAsyncProviders are always in the lexical scope inside the `newSubscriber` function. This means that a search for an AsyncProvider can trigger the creation of an implicit instance of `AppContextAsyncProviders`.

Let’s look at the next block of code:

```Scala
trait ConnectionPool {
   def get[F[_]:Effect]():F[Connection]
 }

 trait UserDatabase[F[_]:Effect:InAppContext[ConnectionPool *: EmptyTuple]] {
   def insert(user: User):F[User]
 }

 object UserDatabase {
   given [F[_]:Effect:InAppContext[ConnectionPool *: EmptyTuple]]
                                : AppContextAsyncProvider[F,UserDatabase[F]] 
 }

 def newSubscriber1[F[_]:InAppContext[(UserDatabase[F],EmailService)]:Effect](user: User):F[User] = {
   ...
 }


 def main(): Unit = {
    given EmailService = new MyLocalEmailService 
    given ConnectionPool = new MyLocalConnectionPool
    val user = User("John", "john@example.com")
    val r = newSubscriber1[ToyMonad](user)
    val fr = ToyMonad.run(r)
 }


```
(assuming minimal [ToyMonad](https://github.com/rssh/scala-appcontext/blob/59014c7aecacf81ea3fb6f9415ed603001032248/tagless-final/shared/src/test/scala/com/github/rssh/toymonad/ToyMonad.scala#L15) )

Here, new subscriber bounds will trigger search for `UserDatabase`(1) which will trigger search for `ConnectionPool`(3) which at first will be searched in the `InAppContext[..][F]` scope which will trigger building of `AppContextProviders[ConnectionPool*:EmptyTuple]`(4) which will be called because `InAppContext[(ConnectionPool *: EmptyTuple])` is a type parameter of enclosing function and then will start searching in enclosing scope (5).

The problem is that if step (4) triggers our macro and the macro produces an error, we will report an error, not be able to continue a search, and never reach step (5).

At the core, we escape this problem by defining the class `AppContextProvidersSearch`.  But now we can’t do this. 

Let’s think about how we make a macro for implicit search,  which will fail the search without producing an error.  For this, our macro should also return some result (success or failure), with type determined by our macro, and use evidence to success in the implicit search for value:

```Scala
object AppContextAsyncProviders {

 trait TryBuild[F[_], Xs<:NonEmptyTuple]
 case class TryBuildSuccess[F[_],Xs<:NonEmptyTuple](providers:AppContextAsyncProviders[F,Xs]) extends TryBuild[F,Xs]
 case class TryBuildFailure[F[_],Xs<:NonEmptyTuple](message: String) extends TryBuild[F,Xs]

 transparent inline given tryBuild[F[_],Xs <:NonEmptyTuple]: TryBuild[F,Xs] = ${
   tryBuildImpl[F,Xs]
 }

 inline given build[F[_]:CpsMonad, Xs <: NonEmptyTuple, R <: TryBuild[F,Xs]](using inline trb: R, inline ev: R <:< TryBuildSuccess[F,Xs]): AppContextAsyncProviders[F,Xs] = {
   trb.providers
 }

 def tryBuildImpl[F[_]:Type, Xs <: NonEmptyTuple:Type](using Quotes): Expr[TryBuild[F,Xs]] = {
   //  
 }

 ..

}


```

Full code: [AppContextProviders](https://github.com/rssh/scala-appcontext/blob/main/tagless-final/shared/src/main/scala/com/github/rssh/appcontext/AppContextAsyncProviders.scala).

Now, let’s port the standard example to the monadic case: [see example 3](https://github.com/rssh/scala-appcontext/blob/59014c7aecacf81ea3fb6f9415ed603001032248/tagless-final/shared/src/test/scala/com/github/rssh/appcontext/Example3Test.scala#L12).   Next block of code instantiate and pass `UserDatabase` to the `newSubscrber`, under the hood.

```Scala
given EmailService ..
given ConnectionPool = ..


val user = User("John", "john@example.com")
val r = newSubscriber1[ToyMonad](user)
```

Hmm... actually we don't use `AppContextAsyncProvider`.

Let’s make model example close to reality:  use real IO  and async Connection created in resource:

See [Example 5](https://github.com/rssh/scala-appcontext/blob/main/tagless-final/jvm/src/test/scala/com/github/rssh/appcontexttest/Example5Test.scala)


## Concrete monad style

Yet one popular style is using a concrete monads,  for example `IO` instead `F[_]`.  In such case we don’t need `InAppContext`  and can pass providers as in core case, as context parameters.  What providers to use:  `AppContextProvider or AppContextAsyncProviders`  become a question of taste.  You even can use `AppContextProviderModule` with async dependencies.  

[Example](https://github.com/rssh/scala-appcontext/blob/main/tagless-final/jvm/src/test/scala/com/github/rssh/appcontexttest/Example7Test.scala)

## Environment effects.

If we open the theme of using type-driven dependency injection in the effect systems,  maybe we should say few words about libraries like zio or kyo, which provides own implementation of dependency injection. 
All them based on concept,  that types needed for computation are encoded in it signature (similar to our tagless-final approach). In theory, our approach cah simplicity interaction points with such libraries (i.e. we can assemble needed computation environmemt from providers). 


That’s all for today.  Tagless final part are published as subproject in appcontext with name “appcontext-tf”, 
(github: https://github.com/rssh/scala-appcontext )
You can try it using  `“com.github.rssh” %%% “appcontext-tf” % “0.2.0”` as dependency.  (maybe it should be joined with core ?) Will be grateful about problem reports and suggestions for better names.  






  




