/*
 * Copyright 2018 Lucas Satabin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package swam
package runtime
package internals
package interpreter

import cats._

import java.nio.ByteBuffer

import scala.language.higherKinds

abstract class Interpreter[F[_]](engine: Engine[F])(implicit F: MonadError[F, Throwable]) {

  def interpret(funcidx: Int, parameters: Vector[Long], instance: Instance[F]): F[Option[Long]]

  def interpret(func: Function[F], parameters: Vector[Long], instance: Instance[F]): F[Option[Long]]

  def interpretInit(tpe: ValType, code: ByteBuffer, instance: Instance[F]): F[Option[Long]]

}
