package x

import scala.concurrent._
import scala.concurrent.duration._

sealed trait Nat
{
 type Current <: Nat
 type Next <: Nat
 type Prev <: Nat

 type Plus[X <: Nat] <: Nat
 type Mult[X <: Nat] <: Nat
 type Minus[X <: Nat] <: Nat
 type Ack[X <: Nat] <: Nat
 type Ack2[X <: Nat] <: Nat

 type IfZero[X<:Nat,Y<:Nat] <: Nat
 type IfOne[X<:Nat,Y<:Nat] <: Nat
 type IfLess[Y<:Nat,Z<:Nat,W<:Nat] <: Nat

 type IsPrime <: Nat
 type EmptyDivides[X<:Nat] <: Nat
 type EmptyDivides2[X<:Nat] <: Nat
 type DivideModule[X<:Nat] <: Nat
}


class Zero extends Nat
{
  type Current = Zero
  type Next = Succ[Zero]
  type Prev = Zero

  type Plus[X<:Nat] = X
  type Mult[X<:Nat] = Nat._0
  type Minus[X<:Nat] = Zero
  type Ack[X <: Nat] = Succ[X]
  type Ack2[Y <: Nat] = Y#Ack[Nat._1]

  type IfZero[X<:Nat,Y<:Nat] = X
  type IfOne[X<:Nat,Y<:Nat] = Y
  type IfLess[Y<:Nat,Z<:Nat,W<:Nat] = Y#IfZero[W,Z]

  type IsPrime = Nat._1
  type EmptyDivides[X<:Nat] = Nat._1
  type EmptyDivides2[X<:Nat] = Nat._1
  type DivideModule[Y<:Nat] = Zero

}


class Succ[X<:Nat] extends Nat
{
  type Current = Succ[X]
  type Next = Succ[Succ[X]]
  type Prev = X

  type Plus[Y<:Nat] = Succ[X#Plus[Y]]
  type Mult[Y<:Nat] = X#IfZero[X,Y#Plus[Prev#Mult[Y]]]§
  type Minus[Y<:Nat] = Y#IfZero[X,X#Minus[Y#Prev]]
  type Ack[Y <: Nat] = Y#Ack2[X]
  type Ack2[Y <: Nat] = Y#Ack[Succ[Y]#Ack[X]]

  type IfZero[Y<:Nat,Z<:Nat] = Z
  type IfOne[Y<:Nat,Z<:Nat] = Z#IfZero[Y,Z]
  type IfLess[Y<:Nat,Z<:Nat,W<:Nat] = IfZero[Y#IfZero[W,Z],X#IfLess[Y#Prev,Z,W]]

  type IsPrime = EmptyDivides[X]
  type EmptyDivides[Y<:Nat] = Y#IfZero[Nat._1,
                               Y#IfOne[Nat._1,
                                   DivideModule[Y]#IfZero[Nat._0,Y#Prev#EmptyDivides2[Succ[X]]]]]
  type EmptyDivides2[Y<:Nat] = Y#EmptyDivides[X]
  type DivideModule[Y<:Nat] = IfLess[Y,Y,X#DivideModule[X#Minus[Y]]]
  

}

object Nat
{
 
  type _0 = Zero
  type _1 = Succ[_0]
  type _2 = Succ[_1]
  type _3 = Succ[_2]
  type _4 = _2#Plus[_2]
  type _16 = _4#Mult[_4]
  type _256 = _16#Mult[_16]

}



object Test
{
  import Nat._

  def longfun()(implicit evidence: Succ[_3]#IsPrime =:= Succ[_256]#IsPrime){
    System.out.println("Hi!")
  }

  //def sofun()(implicit evidence: _4#Ack[_2] =:= _3#Ack[_4]){
  //  System.out.println("Hi!")
  //}

  def main(args: Array[String]):Unit =
  {
    val x = new _2() {}
    System.out.println(s"x=$x")
    longfun()
  }
  

}
