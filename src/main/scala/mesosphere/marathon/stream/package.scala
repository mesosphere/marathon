package mesosphere.marathon

package object stream extends StreamConversions with JavaConversions {
  /*implicit def breakOut[From, T, To](implicit b: CanBuildFrom[Nothing, T, To]): CanBuildFrom[From, T, To] =
    new CanBuildFrom[From, T, To] {
      def apply(from: From) = b.apply()
      def apply() = b.apply()
    }*/
}
