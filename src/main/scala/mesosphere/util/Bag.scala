package mesosphere.util

import scala.collection.mutable

class Bag[T](val map: mutable.Map[T, Int] = mutable.Map[T, Int]().withDefaultValue(0)) {

  def add(key: T, count: Int = 1): Unit = {
    map += key -> (count + map(key))
  }

  def count(key: T): Int = {
    map.getOrElse(key, 0)
  }

  def delete(key: T, count: Int = 1): Boolean = {
    val actualVal = map(key)
    if (actualVal > count) {
      map += key -> (actualVal - count)
      true
    }
    else if (actualVal == count) {
      map.remove(key)
      true
    }
    else {
      false
    }
  }

  def allKeys: collection.Set[T] = map.keySet

  def deepClone: Bag[T] = new Bag[T](map.clone())
}

object Bag {
  def empty[A]: Bag[A] = new Bag[A]()
}
