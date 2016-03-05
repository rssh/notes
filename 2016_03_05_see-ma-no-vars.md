
# idiomatic Go <=> Idiomatic Scala

While preparing to [Wix R&D meetup](http://www.meetup.com/Wix-Ukraine-Meetup-Group/events/229042251/) found a way to improve 
[scala-gopher](https://github.com/rssh/scala-gopher) API.

Using the previous versions of scala-gopher is very easy to translate Go code into Scala; readability of result code is near the same as origin, but it yet not looks 'right'  for functional Scala programmer:  idiomatic Go heavy use mutable variables.

## Fold over selector

One of the typical patterns in Go program looks next:

```
<set of vars as state> 
for(;; ) {
   select {
      case v <- ch1 ... 
      case ...
   }
}
```

Natural analog in Scala contains select loop and state in mutable vars.  But from next version of the scala-gopher we will have more functional alternative:  fold over selector,

```
select.fold(...) ((state, select) => select match {
    case v <- ch .=> ...
 })
```
Exists fluent syntax for case, when state consists from few variables:

```
def fib(out:Output[Long],quit:Future[Boolean]):Future[(Long,Long)] =
  select.afold((0L,1L)){ case ((x,y),s) => 
     s match {
       case x: out.write => (y,x+y)
       case q: quit.read => CurrentFlowTermination.exit((x,y))
     }
  }
```

See ma, no mutable variables.

## Effectized input

Another common pattern in Go programming: modifying of channels when reading/writing one.  An immutable scala-gopher alternative will be EffectedInput, which holds channel, can participate in selector read and have an operation, which applies effect to the current state. 

let's look at an example:
```
 def generate(n:Int, quit:Promise[Boolean]):Channel[Int] =
  {
    val channel = makeChannel[Int]()
    channel.awriteAll(2 to n) andThen (_ => quit success true)
    channel
  }

 def filter(in:Channel[Int]):Input[Int] =
  {
     val filtered = makeChannel[Int]()
     val sieve = makeEffectedInput(in)
     sieve.aforeach { prime =>
            sieve <<= (_.filter(_ % prime != 0))
            filtered <~ prime
      }
    filtered
  }

```

Here in 'filter', we generate a set of prime numbers, and make a sieve of Eratosthenes by sequentially applying 'filter'.

-----------------------
  [index](https://github.com/rssh/notes)
