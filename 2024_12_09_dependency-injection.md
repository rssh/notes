---
title: Relative simple and small type-driven dependency injection
---
# First, why is type-based injection better than name-based injection?


Find time to modernize dependency injection in some services. The previous version involved simply passing the context object with fields for services. 

```Scala
   trait AppContext  {
       def   service1(): Service1
       def   service2(): Service2 
         …. 
   }
```

 It has worked well, except for a few problems:
-  tests, where we need to create context objects with all fields,  even if we need only one.
- modularization: When we want to move part of functionality to the shared library, we should also create a library context, and our context should extend the library context.  
-   one AppContext gives us a ‘dependency loop’: nearly all services depend on AppContext, which depends on all services.  So, the recompilation of AppContext causes the recompilation of all services.

However, for relatively small applications, it is possible to live with.


If we switch to type-driven context resolving (i.e., use `AppContext[Service1]` instead of `appContext.service1` ),  we will solve the modularization problem.

The first look was at the approach described by @odersky in https://old.reddit.com/r/scala/comments/1eksdo2/automatic_dependency_injection_in_pure_scala/.
(code:  https://github.com/scala/scala3/blob/main/tests/run/Providers.scala )

Unfortunately, the proposed technique is not directly applicable to our cases. The main reason is that machinery with matсh types works only when all types in the tuple are distinct.  Therefore, this means all components participating in dependency injection should not be traits. (because if we have two traits (A, B),  we can't prove that A and B in general are distinct; therefore, Index[(A, B)] will not resolved.  The limitation not to use traits as components is too strict for practical usage.  Two other problems (absence of chaining of providers and absence of caching) are fixable and demonstrate a usual gap between 'proof of concept’ and real stuff.

We will use approaches very close to what sideEffffECt describe in https://www.reddit.com/r/scala/comments/1eqqng2/the_simplest_dependency_injection_pure_scala_no/ with few additional steps.


# Basic

We will think that component `C` is provided if we can find AppProvider[C]]

```Scala
trait AppContextProvider[T] {
 def get: T
}

...

object AppContext {


def apply[T](using AppContextProvider[T]): T =
        summon[AppContextProvider[T]].get

  ….

}
```

If we have an implicit instance of the object, we think it's provided:
   

```Scala
object AppContextProvider  {

 ....

 given ofGivem[T](using T): AppContextProvider[T] with {
   def get: T = summon[T]
 }


}   
```

Also, the usual practice for components is to define its implicit provider in the companion object.


Example of UserSubscription looks like:

```Scala
class UserSubscription(using AppContextProvider[EmailService],
                            AppContextProvider[UserDatabase]
                     ) {


 def subscribe(user: User): Unit =
   AppContext[EmailService].sendEmail(user, "You have been subscribed")
   AppContext[UserDatabase].insert(user)

 …. 

}

object UserSubscription {
   // boilerplate
   given (using AppContextProvider[EmailService],
                 AppContextProvider[UserDatabase]):      
                               AppContextProvider[UserSubscription] with
     def get: UserSubscription = new UserSubscription
}
```



Okay, this works, but we have to write some boilerplate. Can we have the same in a shorter form, for example, a List of provided types instead of a set of implicit variants?

# Shrinking boilerplate:

Sure,  we can pack the provided parameter types in the tuple and use macroses for extraction.

```Scala
class UserSubscription(using AppContextProviders[(EmailService,UserDatabase)]) {


 def subscribe(user: User): Unit =
   AppContext[EmailService].sendEmail(user, "You have been subscribed")
   AppContext[UserDatabase].insert(user)


 …. 


}
```

How to do this:  at first, we will need a type-level machinery, which will select a first supertype of `T`  from the tuple `Xs`:

```Scala
object TupleIndex {


 opaque type OfSubtype[Xs <: Tuple, T, N<:Int] = N


 extension [Xs <: Tuple, T, N<:Int](idx: TupleIndex.OfSubtype[Xs, T, N])
   def index: Int = idx


 inline given zeroOfSubtype[XsH, XsT <:Tuple, T<:XsH]: OfSubtype[XsH *: XsT, T, 0] = 0


 inline given nextOfSubtype[XsH, XsT <:NonEmptyTuple, T, N <: Int](using idx: OfSubtype[XsT, T, N]): OfSubtype[XsH *: XsT, T, S[N]] =
   constValue[S[N]]


}
```


Then, we can define a type for search in AppProviders:

```Scala
trait AppContextProvidersSearch[Xs<:NonEmptyTuple] {


 def getProvider[T,N<:Int](using TupleIndex.OfSubtype[Xs,T,N]): AppContextProvider[T]


 def get[T, N<:Int](using TupleIndex.OfSubtype[Xs,T, N]): T = getProvider[T,N].get


}


trait AppContextProviders[Xs <: NonEmptyTuple] extends AppContextProvidersSearch[Xs] 

```


and expression,  which will assemble the instance of the AppContextProvider from available context providers when it will be called.


```Scala
object AppContextProviders {

 inline given generate[T<:NonEmptyTuple]: AppContextProviders[T] = ${ generateImpl[T] }

  ……

}
```


(complete code is available in the repository: https://github.com/rssh/scala-appcontext ; permalink to generateImpl: https://github.com/rssh/scala-appcontext/blob/666a02e788aa57922104569541511a16431690fb/shared/src/main/scala/com/github/rssh/appcontext/AppContextProviders.scala#L52  )

We separate `AppContextProvidersSearch` and `AppContextProviders` because we don't want to trigger `AppContextProviders` implicit generation during implicit search outside of service instance generation.
  Note that Scala currently has no way to make a macro that generates a given instance to fail an implicit search silently. We can only make errors during the search, which will abandon the whole compilation.  

Can we also remove the boilerplate when defining the implicit AppContext provider?
I.e. 

```Scala
object UserSubscription {
 // boilerplate
 given (using AppContextProvider[EmailService],
        AppContextProvider[UserDatabase]): AppContextProvider[UserSubscription] with
   def get: UserSubscription = new UserSubscription
}
```

Will become 


```Scala
object UserSubscription {
 
 given (using AppContextProviders[(EmailService, UserDatabase)]): AppContextProvider[UserSubscription] with
   def get: UserSubscription = new UserSubscription
}
```

But this will still be boilerplate: We must enumerate dependencies twice and write trivial instance creation. On the other hand, this instance creation is not entirely meaningless: we can imagine the situation when it's not automatic.

To minimize this kind of boilerplate,  we can introduce a convention for `AppContextProviderModule`,  which defines its dependencies in type and automatic generation of instance providers:

```Scala
trait AppContextProviderModule[T] {


 /**
  * Dependencies providers: AppContextProviders[(T1,T2,...,Tn)], where T1,T2,...,Tn are dependencies.
  */
 type DependenciesProviders


 /**
  * Component type, which we provide.
  */
 type Component = T




 inline given provider(using dependenciesProvider: DependenciesProviders): AppContextProvider[Component] = ${
   AppContextProviderModule.providerImpl[Component, DependenciesProviders]('dependenciesProvider)
 }

  …

}
```


Now,  the definition of  UserSubscriber can look as described below:

```Scala
class UserSubscription(using UserSubscription.DependenciesProviders) 
  …


object UserSubscription extends AppContextProviderModule[UserSubscription] {
 type DependenciesProviders = AppContextProviders[(EmailService, UserDatabase)]
}
```


Is that all – not yet.

# Caching

Yet one facility usually needed from the dependency injection framework is caching. In all our examples,  `AppContextProvider` returns a new instance of services. However, some services have a state that should be shared between all service clients. An example is a connection pool or service that gathers internal statistics into the local cache.

Let’s add cache type to the AppContext:

```Scala
object AppContext  {


   …


  opaque type Cache = TrieMap[String, Any]


  opaque type CacheKey[T] = String


  inline def cacheKey[T] = ${ cacheKeyImpl[T] }


  extension  (c: Cache)
    inline def get[T]: Option[T] 
    inline def getOrCreate[T](value: => T): T
    inline def put[T](value: T): Unit


}
```
 

And let's deploy a simple convention:  if the service requires `AppContext.Cache`  as a dependency, then we consider this service cached.  I.e., with manual setup of `AppContextProvider` this should look like this:

```Scala
object FuelUsage {
 
 given (using AppContextProviders[(AppContext.Cache, Wheel, Rotor, Tail)]): AppContextProvider[FuelUsage] with
   def get: FuelUsage = AppContext[AppContext.Cache].getOrCreate(FuelUsage)
                                
}
```

Automatically generated providers follow the same convention.

The cache key now is just the name of a type.   But now we are facing a problem: if we have more than one service implementation (test/tangible), there are different types.  Usually, developers consider some ‘base type’ that the service should replace. Hoverer macroses can’t extract this information indirectly. So, let’s allow a developer to write this class in the annotation:

```Scala
class appContextCacheClass[T] extends scala.annotation.StaticAnnotation
```

Cache operations will follow that annotation when calculating cacheKey[T].
Typical usage:

```Scala
trait UserSubscription

@appContextCacheClass[UserSubscription]
class TestUserSubscription(using TestUserSubscription.DependenciesProviders) 

 ...
```

# Preventing pitfalls

Can this be considered a complete mini-framework? Still waiting.
Let’s look at the following code:

```Scala
case class Dependency1(name: String)


object Dependency1 {
 given AppContextProvider[Dependency1] =  AppContextProvider.of(Dependency1("dep1:module"))
}


case class Dependency2(name: String)

class Component(using AppContextProvider[Dependency1,Dependency2]) {
 def doSomething(): String = {
   s”${AppContext[Dependency1]}:${AppContext[Dependency2]}
 }
}

val dep1 = Dependency1("dep1:local")
val dep2 = Dependency2("dep2:local")
val c = Component(using AppContextProviders.of(dep1, dep2))
println(c3.doSomething())
```

What will be printed?

The correct answer is  `“dep1:module:dep2:local”`,  because resolving of `Dependency1` from the companion object will be preferred over resolving from the `AppContextProvider` companion object.   Unfortunately, I don’t know how to change this.  

We can add a check to determine whether supplied providers are needed. Again, unfortunately, we can’t add it ‘behind the scenes' by modifying the generator of `AppContextProvider` because the generator is inlined in the caller context for the component instance, where all dependencies should be resolved.
We can write a macro that should be called from the context inside a component definition.  This will require the developer to call it explicitly.

I.e., a typical component definition will look like this:

```Scala
class Component(using AppContextProviders[(Dependency1,Dependency2)]) {
   assert(AppContextProviders.allDependenciesAreNeeded)
   ….
}
```

Now, we can use our micro-framework without pitfalls. Automatic checking that all listed dependencies are actual is a good idea, which can balance the necessity of the extra line of code.


In the end, we have received something usable. After doing all the steps, I can understand why developers with the most expertise in another ecosystem can hate Scala.
With any other language, a developer can get the default most-known dependency injection framework for this language and use one.  But in Scala, we have no defaults. All alternatives are heavy or not type-driven.  Building our small library takes time, distracting developers from business-related tasks. And we can’t eliminate the library users' need to write boilerplate code. 
  On the other hand, things look good. Scala's flexibility allows one to quickly develop a ‘good enough’ solution despite the fragmented ecosystem.


The repository for this mini-framework can be found at https://github.com/rssh/scala-appcontext 

