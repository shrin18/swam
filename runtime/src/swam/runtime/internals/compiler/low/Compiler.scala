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
package compiler
package low

import syntax._
import interpreter.low.Asm
import config.ConfiguredByteOrder

import cats.effect._

import fs2._

import java.nio.ByteBuffer

import scala.collection.mutable.ArrayBuilder

import scala.annotation.tailrec

import scala.language.higherKinds

import java.lang.{Float => JFloat, Double => JDouble}
import java.nio.ByteOrder

/** A stack of labels, each one tracking following information:
  *  - the parent label
  *  - its target on break
  *  - its arity (i.e. the number of return value to pop and return on break)
  *  - the number of pushed value so far for the current label
  * At top-level, the parent is `null` and target is `0`.
  * No checks need to be performed because the compiler is only called if the
  * program is correct, in which case all breaks target an existing label.
  * The top-level label is a special synthesized one, representing the top-level
  * block of the function body.
  */
case class LabelStack(parent: LabelStack, target: Target, arity: Int, pushed: Int) {

  @inline
  def push(n: Int): LabelStack =
    copy(pushed = pushed + n)

  @inline
  def pop(n: Int): LabelStack =
    copy(pushed = pushed - n)

  @inline
  def isToplevel: Boolean =
    parent == null

  // returns the target label and the rest to pop (after the returned label arity as been popped)
  @inline
  def break(lbl: Int): (LabelStack, Int) =
    breakLoop(lbl, this, 0)

  @tailrec
  private def breakLoop(lbl: Int, elem: LabelStack, pushed: Int): (LabelStack, Int) =
    if (lbl == 0)
      // we reached the label
      (elem, pushed + elem.pushed - elem.arity)
    else
      breakLoop(lbl - 1, elem.parent, pushed + elem.pushed)

}

/** Compilation context used when compiling instructions for a function.
  * The label stack represents the static trace of the program so far in
  * terms of stack modifications, the errata registers the label to fix
  * later, and the jump label offsets.
  * This allows to compile code in one pass even with forward jumps.
  *
  * The `errata` maps offset in the function code to label name.
  *
  * The `offsets` maps label names to their offset.
  *
  * Once all code has been generated, the placeholders pointed by the `errata`
  * map are replaced with real offsets from the `offsets` map.
  */
case class FunctionContext(labels: LabelStack,
                           errata: Map[Int, Target],
                           offsets: Map[Target, Int],
                           offset: Int,
                           nxt: Int) {

  @inline
  def isToplevel: Boolean =
    labels.isToplevel

  @inline
  def pop(n: Int): FunctionContext =
    copy(labels = labels.pop(n))

  @inline
  def push(n: Int): FunctionContext =
    copy(labels = labels.push(n))

}

object FunctionContext {
  def apply(arity: Int): FunctionContext =
    FunctionContext(LabelStack(null, 0, arity, 0), Map.empty, Map.empty, 0, 1)
}

/** Compiles a valid module to the low level [[swam.runtime.internals.interpreter.low.Asm$ assembly language]].
  * This is a one-pass assembler which generates typed breaks.
  *
  * This compiler executes the static part of the specification semantics, i.e. the one
  * dealing with labels and breaks. The static restrictions on the WebAssembly bytecodes
  * make it possible to statically compute the local stack size at any point in a function
  * body. Of course, it assumes that the module is [[swam.validation.Validator valid]]
  * otherwise it will break or generate undefined assembly code.
  *
  */
class Compiler[F[_]: Effect](engine: Engine[F]) extends compiler.Compiler[F] {

  private val dataOnHeap = engine.conf.data.onHeap
  private val byteOrder = engine.conf.compiler.low.byteOrder match {
    case ConfiguredByteOrder.BigEndian    => ByteOrder.BIG_ENDIAN
    case ConfiguredByteOrder.LittleEndian => ByteOrder.LITTLE_ENDIAN
    case ConfiguredByteOrder.Native       => ByteOrder.nativeOrder
  }

  def compile(sections: Stream[F, Section]): Stream[F, runtime.Module[F]] =
    sections
      .fold(Context()) {
        case (ctx, Section.Imports(is)) =>
          val runtimeis = is.map(toRuntime(ctx.types))
          runtimeis.foldLeft(ctx.copy(imports = runtimeis)) {
            case (ctx, runtime.Import.Function(_, _, tpeidx, tpe)) =>
              ctx.copy(funcs = ctx.funcs :+ tpeidx, code = ctx.code :+ compiler.Func.Imported(tpe))
            case (ctx, runtime.Import.Table(_, _, tpe)) =>
              ctx.copy(tables = ctx.tables :+ Tab.Imported(tpe))
            case (ctx, runtime.Import.Memory(_, _, tpe)) =>
              ctx.copy(mems = ctx.mems :+ Mem.Imported(tpe))
            case (ctx, runtime.Import.Global(_, _, tpe)) =>
              ctx.copy(globals = ctx.globals :+ Glob.Imported(tpe))
          }
        case (ctx, Section.Functions(funcs)) =>
          ctx.copy(funcs = ctx.funcs ++ funcs)
        case (ctx, Section.Tables(tables)) =>
          ctx.copy(tables = ctx.tables ++ tables.map(Tab.Compiled(_)))
        case (ctx, Section.Memories(mems)) =>
          ctx.copy(mems = ctx.mems ++ mems.map(Mem.Compiled(_)))
        case (ctx, Section.Globals(globals)) =>
          val cglobals =
            globals.map {
              case Global(tpe, init) =>
                val compiled = compile(0, init, FunctionContext(1), ctx.functions, ctx.types)._1
                val ccode = ByteBuffer.allocate(compiled.length)
                ccode.order(byteOrder)
                ccode.put(compiled)
                ccode.position(0)
                Glob.Compiled(CompiledGlobal(tpe, ccode))
            }
          ctx.copy(globals = ctx.globals ++ cglobals)
        case (ctx, Section.Exports(es)) =>
          ctx.copy(exports = es.map(toRuntime(ctx)))
        case (ctx, c @ Section.Custom(name, payload)) =>
          ctx.copy(customs = ctx.customs :+ toRuntime(c))
        case (ctx, Section.Types(types)) =>
          ctx.copy(types = types)
        case (ctx, Section.Code(codes)) =>
          val shift = ctx.funcs.size - codes.size
          val code =
            codes.zipWithIndex.map {
              case ((FuncBody(locals, code)), idx) =>
                val tpe = ctx.types(ctx.funcs(idx + shift))
                val compiled = compile(tpe.params.size + locals.map(_.count).sum,
                                       code,
                                       FunctionContext(tpe.t.size),
                                       ctx.functions,
                                       ctx.types)._1
                val ccode = ByteBuffer.allocate(compiled.length)
                ccode.order(byteOrder)
                ccode.put(compiled)
                ccode.position(0)
                val clocals = locals.flatMap(e => Vector.fill(e.count)(e.tpe))
                compiler.Func.Compiled(CompiledFunction(tpe, clocals, ccode))
            }
          ctx.copy(code = code)
        case (ctx, Section.Elements(elems)) =>
          val celems =
            elems.map {
              case Elem(_, offset, init) =>
                val compiled = compile(0, offset, FunctionContext(1), ctx.functions, ctx.types)._1
                val coffset = ByteBuffer.allocate(compiled.length)
                coffset.order(byteOrder)
                coffset.put(compiled)
                coffset.position(0)
                CompiledElem(coffset, init)
            }
          ctx.copy(elems = celems)
        case (ctx, Section.Datas(data)) =>
          val cdata =
            data.map {
              case Data(_, offset, bytes) =>
                val compiled = compile(0, offset, FunctionContext(1), ctx.functions, ctx.types)._1
                val coffset = ByteBuffer.allocate(compiled.length)
                coffset.order(byteOrder)
                coffset.put(compiled)
                coffset.position(0)
                val dataArray = bytes.toByteArray
                val data =
                  if (dataOnHeap)
                    ByteBuffer.allocate(dataArray.length)
                  else
                    ByteBuffer.allocateDirect(dataArray.length)
                data.order(ByteOrder.LITTLE_ENDIAN)
                data.put(dataArray)
                data.position(0)
                CompiledData(coffset, data)
            }
          ctx.copy(data = cdata)
        case (ctx, Section.Start(idx)) =>
          ctx.copy(start = Some(idx))
      }
      .map { ctx =>
        new runtime.Module(
          ctx.exports,
          ctx.imports,
          ctx.customs,
          ctx.types,
          engine,
          ctx.globals.collect {
            case Glob.Compiled(g) => g
          },
          ctx.tables.collect {
            case Tab.Compiled(t) => t
          },
          ctx.mems.collect {
            case Mem.Compiled(m) => m
          },
          ctx.start,
          ctx.code.collect {
            case compiler.Func.Compiled(f) => f
          },
          ctx.elems,
          ctx.data
        )
      }
      .handleErrorWith(t => Stream.raiseError[F](new CompileException("An error occurred during compilation", t)))

  private def compile(nbLocals: Int,
                      insts: Vector[Inst],
                      ctx: FunctionContext,
                      functions: Vector[FuncType],
                      types: Vector[FuncType]): (Array[Byte], FunctionContext) = {

    val builder = ArrayBuilder.make[Byte]

    @tailrec
    def loop(instIdx: Int, ctx: FunctionContext, hasReturn: Boolean): (Boolean, FunctionContext) =
      if (instIdx >= insts.size)
        (hasReturn, ctx)
      else
        insts(instIdx) match {
          case Block(tpe, is) =>
            // when entering a new block, push a label with the same
            // arity as the block and a target on break pointing right
            // after the block body.
            val label = LabelStack(ctx.labels, ctx.nxt, tpe.arity, 0)
            val (compiled, ctx1) = compile(nbLocals, is, ctx.copy(labels = label, nxt = ctx.nxt + 1), functions, types)
            builder ++= compiled
            // the label target is then the offset right after the body has been compiled
            loop(instIdx + 1,
                 ctx1.copy(labels = ctx.labels, offsets = ctx1.offsets.updated(ctx.nxt, ctx1.offset)).push(tpe.arity),
                 false)
          case Loop(tpe, is) =>
            // when entering a new loop, push a label with arity 0
            // and a target on break pointing at the loop body start
            val label = LabelStack(ctx.labels, ctx.nxt, 0, 0)
            val (compiled, ctx1) = compile(nbLocals, is, ctx.copy(labels = label, nxt = ctx.nxt + 1), functions, types)
            builder ++= compiled
            loop(instIdx + 1,
                 ctx1.copy(labels = ctx.labels, offsets = ctx1.offsets.updated(ctx.nxt, ctx.offset)).push(tpe.arity),
                 false)
          case If(tpe, tis, eis) =>
            // when entering a new if, push a label with the same
            // arity as the if and a target on break pointing right
            // after the end of the if body
            // add a conditional jump to the then part, the else part comes first
            val ctx1 = ctx.pop(1 /* the if condition */ )
            val label = LabelStack(ctx1.labels, ctx1.nxt, tpe.arity, 0)
            val thenTarget = ctx1.nxt + 1
            builder += Asm.JumpIf.toByte
            storeInt(builder, -1 /* placeholder */ )
            val (ecompiled, ctx2) =
              compile(nbLocals,
                      eis,
                      ctx1.copy(labels = label, nxt = ctx1.nxt + 2, offset = ctx1.offset + 5 /* jumpif + label */ ),
                      functions,
                      types)
            builder ++= ecompiled
            // jump right after the then part in the end
            builder += Asm.Jump.toByte
            storeInt(builder, -1 /* placeholder */ )
            val (tcompiled, ctx3) =
              compile(nbLocals, tis, ctx2.copy(offset = ctx2.offset + 5, labels = label), functions, types)
            builder ++= tcompiled
            loop(
              instIdx + 1,
              ctx3
                .copy(
                  labels = ctx1.labels,
                  errata = ctx3.errata.updated(ctx1.offset + 1, thenTarget).updated(ctx2.offset + 1, ctx.nxt),
                  offsets = ctx3.offsets.updated(thenTarget, ctx2.offset + 5).updated(ctx.nxt, ctx3.offset)
                )
                .push(tpe.arity),
              false
            )
          case Br(lbl) =>
            // break to the given label
            // the instruction parameters are (in order) the arity, the rest to drop, and the target
            val (label, toDrop) = ctx.labels.break(lbl)
            builder += Asm.Br.toByte
            storeInt(builder, label.arity)
            storeInt(builder, toDrop)
            storeInt(builder, -1 /* placeholder */ )
            // save the placeholder offset to be fixed-up with the real label address in the end and skip the rest of the block
            (false, ctx.copy(offset = ctx.offset + 13, errata = ctx.errata.updated(ctx.offset + 9, label.target)))
          case BrIf(lbl) =>
            // break to the given label if condition is true
            // the instruction parameters are (in order) the arity, the rest to drop, and the target
            val ctx1 = ctx.pop(1 /* the break condition */ )
            val (label, toDrop) = ctx1.labels.break(lbl)
            builder += Asm.BrIf.toByte
            storeInt(builder, label.arity)
            storeInt(builder, toDrop)
            storeInt(builder, -1 /* placeholder */ )
            // save the placeholder offset to be fixed-up with the real label address in the end
            loop(instIdx + 1,
                 ctx1.copy(offset = ctx1.offset + 13, errata = ctx1.errata.updated(ctx1.offset + 9, label.target)),
                 false)
          case BrTable(lbls, lbl) =>
            // break to the appropriate label depending on top-level stack value
            val ctx1 = ctx.pop(1 /* the break value */ )
            // the instruction parameters are (in order) `lbls.size`, `lbls.size + 1` times the arity, the rest to drop, and the target
            builder += Asm.BrTable.toByte
            storeInt(builder, lbls.size)
            val ctx2 = lbls.foldLeft(ctx1.copy(offset = ctx1.offset + 5 /* opcode + size */ )) { (ctx1, lbl) =>
              val (label, toDrop) = ctx1.labels.break(lbl)
              storeInt(builder, label.arity)
              storeInt(builder, toDrop)
              storeInt(builder, -1 /* placeholder */ )
              // save the placeholder offset to be fixed-up with the real label address in the end
              ctx1.copy(offset = ctx1.offset + 12, errata = ctx1.errata.updated(ctx1.offset + 8, label.target))
            }
            val (label, toDrop) = ctx2.labels.break(lbl)
            storeInt(builder, label.arity)
            storeInt(builder, toDrop)
            storeInt(builder, -1 /* placeholder */ )
            // save the placeholder offset to be fixed-up with the real label address in the end and skip the rest of the block
            val ctx3 =
              ctx2.copy(offset = ctx2.offset + 12, errata = ctx2.errata.updated(ctx2.offset + 8, label.target))
            (false, ctx3)
          case Return =>
            builder += Asm.Return.toByte
            // skip the rest of the block
            (true, ctx.copy(offset = ctx.offset + 1))
          case op @ Unop(_) =>
            builder += op.opcode.toByte
            loop(instIdx + 1, ctx.copy(offset = ctx.offset + 1), false)
          case op @ Binop(_) =>
            builder += op.opcode.toByte
            loop(instIdx + 1, ctx.pop(1 /* pop 2 and push 1 */ ).copy(offset = ctx.offset + 1), false)
          case op @ Testop(_) =>
            builder += op.opcode.toByte
            loop(instIdx + 1, ctx.copy(offset = ctx.offset + 1), false)
          case op @ Relop(_) =>
            builder += op.opcode.toByte
            loop(instIdx + 1, ctx.pop(1 /* pop 2 and push 1 */ ).copy(offset = ctx.offset + 1), false)
          case op @ Convertop(_, _) =>
            builder += op.opcode.toByte
            loop(instIdx + 1, ctx.copy(offset = ctx.offset + 1), false)
          case op @ Load(_, align, offset) =>
            builder += op.opcode.toByte
            storeInt(builder, align)
            storeInt(builder, offset)
            loop(instIdx + 1, ctx.copy(offset = ctx.offset + 9), false)
          case op @ LoadN(_, _, align, offset) =>
            builder += op.opcode.toByte
            storeInt(builder, align)
            storeInt(builder, offset)
            loop(instIdx + 1, ctx.copy(offset = ctx.offset + 9), false)
          case op @ Store(_, align, offset) =>
            builder += op.opcode.toByte
            storeInt(builder, align)
            storeInt(builder, offset)
            loop(instIdx + 1, ctx.pop(2).copy(offset = ctx.offset + 9), false)
          case op @ StoreN(_, _, align, offset) =>
            builder += op.opcode.toByte
            storeInt(builder, align)
            storeInt(builder, offset)
            loop(instIdx + 1, ctx.pop(2).copy(offset = ctx.offset + 9), false)
          case MemoryGrow =>
            builder += Asm.MemoryGrow.toByte
            loop(instIdx + 1, ctx.copy(offset = ctx.offset + 1), false)
          case MemorySize =>
            builder += Asm.MemorySize.toByte
            loop(instIdx + 1, ctx.push(1).copy(offset = ctx.offset + 1), false)
          case Drop =>
            builder += Asm.Drop.toByte
            storeInt(builder, 1)
            loop(instIdx + 1, ctx.pop(1).copy(offset = ctx.offset + 5), false)
          case Select =>
            builder += Asm.Select.toByte
            loop(instIdx + 1, ctx.pop(2 /* pop 3 push 1 */ ).copy(offset = ctx.offset + 1), false)
          case LocalGet(idx) =>
            builder += Asm.LocalGet.toByte
            storeInt(builder, nbLocals - idx)
            loop(instIdx + 1, ctx.push(1).copy(offset = ctx.offset + 5), false)
          case LocalSet(idx) =>
            builder += Asm.LocalSet.toByte
            storeInt(builder, nbLocals - idx)
            loop(instIdx + 1, ctx.pop(1).copy(offset = ctx.offset + 5), false)
          case LocalTee(idx) =>
            builder += Asm.LocalTee.toByte
            storeInt(builder, nbLocals - idx)
            loop(instIdx + 1, ctx.copy(offset = ctx.offset + 5), false)
          case GlobalGet(idx) =>
            builder += Asm.GlobalGet.toByte
            storeInt(builder, idx)
            loop(instIdx + 1, ctx.push(1).copy(offset = ctx.offset + 5), false)
          case GlobalSet(idx) =>
            builder += Asm.GlobalSet.toByte
            storeInt(builder, idx)
            loop(instIdx + 1, ctx.pop(1).copy(offset = ctx.offset + 5), false)
          case Nop =>
            builder += Asm.Nop.toByte
            loop(instIdx + 1, ctx.copy(offset = ctx.offset + 1), false)
          case Unreachable =>
            builder += Asm.Unreachable.toByte
            // we can skip the rest of the block
            (false, ctx.copy(offset = ctx.offset + 1))
          case Call(idx) =>
            builder += Asm.Call.toByte
            storeInt(builder, idx)
            val tpe = functions(idx)
            loop(instIdx + 1, ctx.pop(tpe.params.size).push(tpe.t.size).copy(offset = ctx.offset + 5), false)
          case CallIndirect(idx) =>
            builder += Asm.CallIndirect.toByte
            storeInt(builder, idx)
            val tpe = types(idx)
            loop(instIdx + 1, ctx.pop(1 + tpe.params.size).push(tpe.t.size).copy(offset = ctx.offset + 5), false)
          case i32.Const(v) =>
            builder += Asm.I32Const.toByte
            storeInt(builder, v)
            loop(instIdx + 1, ctx.push(1).copy(offset = ctx.offset + 5), false)
          case i64.Const(v) =>
            builder += Asm.I64Const.toByte
            storeLong(builder, v)
            loop(instIdx + 1, ctx.push(1).copy(offset = ctx.offset + 9), false)
          case f32.Const(v) =>
            builder += Asm.F32Const.toByte
            storeInt(builder, JFloat.floatToRawIntBits(v))
            loop(instIdx + 1, ctx.push(1).copy(offset = ctx.offset + 5), false)
          case f64.Const(v) =>
            builder += Asm.F64Const.toByte
            storeLong(builder, JDouble.doubleToRawLongBits(v))
            loop(instIdx + 1, ctx.push(1).copy(offset = ctx.offset + 9), false)
        }

    val (hasReturn, ctx1) = loop(0, ctx, false)

    val ctx2 =
      if (ctx.isToplevel) {
        if (!hasReturn) {
          // if this is the top-level and no explicit return has been provided in bytecode, add one
          builder += Asm.Return.toByte
          ctx1.copy(offsets = ctx1.offsets.updated(0, ctx1.offset))
        } else {
          ctx1.copy(offsets = ctx1.offsets.updated(0, ctx1.offset - 1))
        }
      } else {
        // otherwise go back to the parent label
        ctx1.copy(labels = ctx1.labels.parent)
      }

    val bytes = builder.result()
    if (ctx.isToplevel) {
      // fixup the targets
      for ((offset, target) <- ctx2.errata)
        storeInt(bytes, offset, ctx2.offsets(target))
    }
    (bytes, ctx2)
  }

  private def storeInt(builder: ArrayBuilder[Byte], i: Int): ArrayBuilder[Byte] = {
    // store integers in configured endianness
    byteOrder match {
      case ByteOrder.BIG_ENDIAN =>
        builder += ((i >> 24) & 0xff).toByte
        builder += ((i >> 16) & 0xff).toByte
        builder += ((i >> 8) & 0xff).toByte
        builder += (i & 0xff).toByte
      case ByteOrder.LITTLE_ENDIAN =>
        builder += (i & 0xff).toByte
        builder += ((i >> 8) & 0xff).toByte
        builder += ((i >> 16) & 0xff).toByte
        builder += ((i >> 24) & 0xff).toByte
    }
  }

  private def storeInt(a: Array[Byte], idx: Int, i: Int): Unit = {
    // store integers in configured endianness
    byteOrder match {
      case ByteOrder.BIG_ENDIAN =>
        a(idx) = ((i >> 24) & 0xff).toByte
        a(idx + 1) = ((i >> 16) & 0xff).toByte
        a(idx + 2) = ((i >> 8) & 0xff).toByte
        a(idx + 3) = (i & 0xff).toByte
      case ByteOrder.LITTLE_ENDIAN =>
        a(idx) = (i & 0xff).toByte
        a(idx + 1) = ((i >> 8) & 0xff).toByte
        a(idx + 2) = ((i >> 16) & 0xff).toByte
        a(idx + 3) = ((i >> 24) & 0xff).toByte
    }
  }

  private def storeLong(builder: ArrayBuilder[Byte], l: Long): ArrayBuilder[Byte] = {
    // store integers in configured endianness
    byteOrder match {
      case ByteOrder.BIG_ENDIAN =>
        builder += ((l >> 56) & 0xff).toByte
        builder += ((l >> 48) & 0xff).toByte
        builder += ((l >> 40) & 0xff).toByte
        builder += ((l >> 32) & 0xff).toByte
        builder += ((l >> 24) & 0xff).toByte
        builder += ((l >> 16) & 0xff).toByte
        builder += ((l >> 8) & 0xff).toByte
        builder += (l & 0xff).toByte
      case ByteOrder.LITTLE_ENDIAN =>
        builder += (l & 0xff).toByte
        builder += ((l >> 8) & 0xff).toByte
        builder += ((l >> 16) & 0xff).toByte
        builder += ((l >> 24) & 0xff).toByte
        builder += ((l >> 32) & 0xff).toByte
        builder += ((l >> 40) & 0xff).toByte
        builder += ((l >> 48) & 0xff).toByte
        builder += ((l >> 56) & 0xff).toByte
    }
  }

  private def toRuntime(types: Vector[FuncType])(i: Import): runtime.Import =
    i match {
      case Import.Function(mod, fld, tpe) => runtime.Import.Function(mod, fld, tpe, types(tpe))
      case Import.Table(mod, fld, tpe)    => runtime.Import.Table(mod, fld, tpe)
      case Import.Memory(mod, fld, tpe)   => runtime.Import.Memory(mod, fld, tpe)
      case Import.Global(mod, fld, tpe)   => runtime.Import.Global(mod, fld, tpe)
    }

  private def toRuntime(ctx: Context)(e: Export): runtime.Export =
    e match {
      case Export(fld, ExternalKind.Function, idx) => runtime.Export.Function(fld, ctx.types(ctx.funcs(idx)), idx)
      case Export(fld, ExternalKind.Table, idx)    => runtime.Export.Table(fld, ctx.tables(idx).tpe, idx)
      case Export(fld, ExternalKind.Memory, idx)   => runtime.Export.Memory(fld, ctx.mems(idx).tpe, idx)
      case Export(fld, ExternalKind.Global, idx)   => runtime.Export.Global(fld, ctx.globals(idx).tpe, idx)
    }

  private def toRuntime(c: Section.Custom): runtime.Custom =
    runtime.Custom(c.name, c.payload)

}
