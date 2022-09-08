package effekt
package jit

import scala.collection.mutable
import effekt.symbols.{Symbol, ValueSymbol, BlockSymbol}

object BlockNumbering {

  def numberBlocks(prog: Program): Program = {
    val blockIndices: mutable.HashMap[BlockLabel, BlockIndex] = mutable.HashMap();
    for ((BasicBlock(id, _, _, _), index) <- prog.blocks.zipWithIndex) {
      blockIndices.addOne((id, BlockIndex(index)))
    };
    Program(prog.blocks.map(numberBlocks(blockIndices)), prog.datatypes, prog.frameSize)
  }
  def numberBlocks(blockIndices: mutable.HashMap[BlockLabel, BlockIndex])(block: BasicBlock): BasicBlock = block match {
    case BasicBlock(id, frameDescriptor, instructions, terminator) =>
      BasicBlock(id, frameDescriptor,
        instructions.map(numberBlocks(blockIndices)),
        numberBlocks(blockIndices)(terminator))
  }
  def numberBlocks(blockIndices: mutable.HashMap[BlockLabel, BlockIndex])(instruction: Instruction): Instruction = {
    instruction match
      case Push(target, args) => Push(blockIndices(target), args)
      case IfZero(arg, Clause(args, target)) => IfZero(arg, Clause(args, blockIndices(target)))
      case _ => instruction
  }
  def numberBlocks(blockIndices: mutable.HashMap[BlockLabel, BlockIndex])(terminator: Terminator): Terminator = {
    terminator match
      case Jump(target) => Jump(blockIndices(target))
      case Match(adt_type, scrutinee, clauses) => Match(adt_type, scrutinee, clauses.map({
        case Clause(args, target) => Clause(args, blockIndices(target))
      }))
      case _ => terminator
  }
}