---
title: Solving the Implicit Search Priority Problem in AppContext
---

# Solving the Implicit Search Priority Problem

In the [first article about AppContext](https://github.com/rssh/notes/blob/master/2024_12_09_dependency-injection.md), we described a pitfall with implicit search order:

```scala
case class Dependency1(name: String)

object Dependency1 {
  given AppContextProvider[Dependency1] = AppContextProvider.of(Dependency1("dep1:module"))
}

class Component(using AppContextProviders[(Dependency1, Dependency2)]) {
  def doSomething(): String = {
    s"${AppContext[Dependency1].name}, ${AppContext[Dependency2].name}"
  }
}

val dep1 = Dependency1("dep1:local")
val dep2 = Dependency2("dep2:local")
val c = Component(using AppContextProviders.of(dep1, dep2))
println(c.doSomething())  // Prints "dep1:module, dep2:local" - not what we want!
```

The problem was that `AppContextProvider[Dependency1]` defined in `Dependency1`'s companion object takes priority over the one extracted from `AppContextProviders`, because Scala's implicit search gives high priority to the companion object of the result type.

We had a workaround - `AppContextProviders.checkAllAreNeeded` - to detect such issues at compile time. But now we can solve the problem properly. I don't know why I missed this during writing a first variant, now it looks trivial.

It turns out we can easy solve this problem by introducing an intermediate lookup type. If we search for a different type, which requere AppContextProvider[X],  Scala compiler won't look in the companion object.

We introduce `AppContextProviderLookup[T]`:

```scala
trait AppContextProviderLookup[T] {
  def get: T
}

trait AppContextProviderLookupLowPriority {
  // Low priority fallback: delegate to AppContextProvider[T]
  given fromProvider[T](using provider: AppContextProvider[T]): AppContextProviderLookup[T] with {
    def get: T = provider.get
  }
}

object AppContextProviderLookup extends AppContextProviderLookupLowPriority {
  // High priority: lookup from AppContextProviders in scope
  given fromProviders[Xs <: NonEmptyTuple, X, N <: Int](
    using providers: AppContextProvidersSearch[Xs],
    idx: TupleIndex.OfSubtype[Xs, X, N]
  ): AppContextProviderLookup[X] with {
    def get: X = providers.getProvider[X, N].get
  }
}
```

Then we change `AppContext.apply` to use this new type:

```scala
object AppContext {
  def apply[T](using AppContextProviderLookup[T]): T =
    summon[AppContextProviderLookup[T]].get
}
```

That's all.

When `AppContext[Dependency1]` is called inside a class with `AppContextProviders[(Dependency1, ...)]`:

1. Scala searches for `AppContextProviderLookup[Dependency1]`
2. It looks in `AppContextProviderLookup`'s companion object (not `Dependency1`'s!)
3. It finds `fromProviders` which requires `AppContextProvidersSearch[Xs]`
4. The `AppContextProviders[(Dependency1, ...)]` in scope satisfies this requirement
5. The value from `AppContextProviders` is used

When no `AppContextProviders` is in scope:

1. Scala searches for `AppContextProviderLookup[T]`
2. `fromProviders` doesn't apply (no `AppContextProvidersSearch` available)
3. Falls back to `fromProvider` which delegates to `AppContextProvider[T]`
4. The companion-defined provider is used as expected

Now it works as expected:

```scala
class Component(using AppContextProviders[(Dependency1, Dependency2)]) {
  def doSomething(): String = {
    s"${AppContext[Dependency1].name}, ${AppContext[Dependency2].name}"
  }
}

val dep1 = Dependency1("dep1:local")
val dep2 = Dependency2("dep2:local")
val c = Component(using AppContextProviders.of(dep1, dep2))
println(c.doSomething())  // Now prints "dep1:local, dep2:local"!
```

The values from `AppContextProviders` now take priority over companion-defined defaults, making dependency injection predictable.
`checkAllAreNeeded` is no longer needed and has been removed.

