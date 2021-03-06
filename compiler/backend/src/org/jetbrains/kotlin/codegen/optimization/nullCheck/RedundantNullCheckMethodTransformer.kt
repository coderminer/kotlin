/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.codegen.optimization.nullCheck

import org.jetbrains.kotlin.codegen.coroutines.withInstructionAdapter
import org.jetbrains.kotlin.codegen.inline.ReifiedTypeInliner
import org.jetbrains.kotlin.codegen.optimization.DeadCodeEliminationMethodTransformer
import org.jetbrains.kotlin.codegen.optimization.common.OptimizationBasicInterpreter
import org.jetbrains.kotlin.codegen.optimization.common.isInsn
import org.jetbrains.kotlin.codegen.optimization.fixStack.peek
import org.jetbrains.kotlin.codegen.optimization.fixStack.top
import org.jetbrains.kotlin.codegen.optimization.transformer.MethodTransformer
import org.jetbrains.kotlin.utils.SmartList
import org.jetbrains.kotlin.utils.addToStdlib.assertedCast
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter
import org.jetbrains.org.objectweb.asm.tree.*

class RedundantNullCheckMethodTransformer : MethodTransformer() {
    override fun transform(internalClassName: String, methodNode: MethodNode) {
        while (TransformerPass(internalClassName, methodNode).run()) {}
    }

    private class TransformerPass(val internalClassName: String, val methodNode: MethodNode) {
        private var changes = false

        private fun AbstractInsnNode.getIndex() =
                methodNode.instructions.indexOf(this)

        fun run(): Boolean {
            val checkedReferenceTypes = analyzeTypesAndRemoveDeadCode()
            eliminateRedundantChecks(checkedReferenceTypes)

            return changes
        }

        private fun analyzeTypesAndRemoveDeadCode(): Map<AbstractInsnNode, Type> {
            val insns = methodNode.instructions.toArray()
            val frames = analyze(internalClassName, methodNode, OptimizationBasicInterpreter())

            val checkedReferenceTypes = HashMap<AbstractInsnNode, Type>()
            for (i in insns.indices) {
                val insn = insns[i]
                val frame = frames[i]
                if (insn.isInstanceOfOrNullCheck()) {
                    checkedReferenceTypes[insn] = frame?.top()?.type ?: continue
                }
                else if (insn.isCheckParameterNotNull()) {
                    checkedReferenceTypes[insn] = frame?.peek(1)?.type ?: continue
                }
            }

            val dceResult = DeadCodeEliminationMethodTransformer().removeDeadCodeByFrames(methodNode, frames)
            if (dceResult.hasRemovedAnything()) {
                changes = true
            }

            return checkedReferenceTypes
        }

        private fun eliminateRedundantChecks(checkedReferenceTypes: Map<AbstractInsnNode, Type>) {
            val nullabilityAssumptions = injectNullabilityAssumptions(checkedReferenceTypes)

            val nullabilityMap = analyzeNullabilities()

            nullabilityAssumptions.revert()

            transformTrivialChecks(nullabilityMap)
        }

        private fun injectNullabilityAssumptions(checkedReferenceTypes: Map<AbstractInsnNode, Type>) =
                NullabilityAssumptionsBuilder(checkedReferenceTypes).injectNullabilityAssumptions()

        private fun analyzeNullabilities(): Map<AbstractInsnNode, Nullability> {
            val frames = analyze(internalClassName, methodNode, NullabilityInterpreter())
            val insns = methodNode.instructions.toArray()
            val nullabilityMap = HashMap<AbstractInsnNode, Nullability>()
            for (i in insns.indices) {
                val nullability = frames[i]?.top()?.getNullability() ?: continue
                if (nullability == Nullability.NULLABLE) continue

                val insn = insns[i]
                if (insn.isInstanceOfOrNullCheck()) {
                    nullabilityMap[insn] = nullability
                }
            }
            return nullabilityMap
        }

        private fun transformTrivialChecks(nullabilityMap: Map<AbstractInsnNode, Nullability>) {
            for ((insn, nullability) in nullabilityMap) {
                when (insn.opcode) {
                    Opcodes.IFNULL -> transformTrivialNullJump(insn as JumpInsnNode, nullability == Nullability.NULL)
                    Opcodes.IFNONNULL -> transformTrivialNullJump(insn as JumpInsnNode, nullability == Nullability.NOT_NULL)
                    Opcodes.INSTANCEOF -> transformInstanceOf(insn, nullability)
                }
            }
        }

        private fun transformTrivialNullJump(insn: JumpInsnNode, alwaysTrue: Boolean) {
            changes = true

            methodNode.instructions.run {
                popReferenceValueBefore(insn)
                if (alwaysTrue) {
                    set(insn, JumpInsnNode(Opcodes.GOTO, insn.label))
                }
                else {
                    remove(insn)
                }
            }
        }

        private fun transformInstanceOf(insn: AbstractInsnNode, nullability: Nullability) {
            if (nullability != Nullability.NULL) return
            if (ReifiedTypeInliner.isOperationReifiedMarker(insn.previous)) return

            changes = true

            val nextOpcode = insn.next?.opcode
            if (nextOpcode == Opcodes.IFEQ || nextOpcode == Opcodes.IFNE)
                transformNullInstanceOfWithJump(insn)
            else
                transformNullInstanceOf(insn)
        }

        private fun transformNullInstanceOf(insn: AbstractInsnNode) {
            methodNode.instructions.run {
                popReferenceValueBefore(insn)
                set(insn, InsnNode(Opcodes.ICONST_0))
            }
        }

        private fun transformNullInstanceOfWithJump(insn: AbstractInsnNode) {
            methodNode.instructions.run {
                popReferenceValueBefore(insn)
                val jump = insn.next.assertedCast<JumpInsnNode> { "JumpInsnNode expected" }
                remove(insn)
                if (jump.opcode == Opcodes.IFEQ) {
                    set(jump, JumpInsnNode(Opcodes.GOTO, jump.label))
                }
                else {
                    remove(jump)
                }
            }
        }

        private inner class NullabilityAssumptionsBuilder(val checkedReferenceTypes: Map<AbstractInsnNode, Type>) {

            private val checksDependingOnVariable = HashMap<Int, MutableList<AbstractInsnNode>>()

            fun injectNullabilityAssumptions(): NullabilityAssumptions {
                collectVariableDependentChecks()
                return injectAssumptions()
            }

            private fun collectVariableDependentChecks() {
                for (insn in methodNode.instructions) {
                    if (insn.isInstanceOfOrNullCheck()) {
                        val previous = insn.previous ?: continue
                        if (previous.opcode == Opcodes.ALOAD) {
                            addDependentCheck(insn, previous as VarInsnNode)
                        }
                        else if (previous.opcode == Opcodes.DUP) {
                            val previous2 = previous.previous ?: continue
                            if (previous2.opcode == Opcodes.ALOAD) {
                                addDependentCheck(insn, previous2 as VarInsnNode)
                            }
                        }
                    }
                    else if (insn.isCheckParameterNotNull()) {
                        val ldcInsn = insn.previous ?: continue
                        if (ldcInsn.opcode != Opcodes.LDC) continue
                        val aLoadInsn = ldcInsn.previous ?: continue
                        if (aLoadInsn.opcode != Opcodes.ALOAD) continue
                        addDependentCheck(insn, aLoadInsn as VarInsnNode)
                    }
                }
            }

            private fun addDependentCheck(insn: AbstractInsnNode, aLoadInsn: VarInsnNode) {
                checksDependingOnVariable.getOrPut(aLoadInsn.`var`) {
                    SmartList<AbstractInsnNode>()
                }.add(insn)
            }

            private fun injectAssumptions(): NullabilityAssumptions {
                val nullabilityAssumptions = NullabilityAssumptions()
                for ((varIndex, dependentChecks) in checksDependingOnVariable) {
                    for (checkInsn in dependentChecks) {
                        val varType = checkedReferenceTypes[checkInsn]
                                      ?: throw AssertionError("No var type @${checkInsn.getIndex()}")
                        nullabilityAssumptions.injectAssumptionsForCheck(varIndex, checkInsn, varType)
                    }
                }
                return nullabilityAssumptions
            }

            private fun NullabilityAssumptions.injectAssumptionsForCheck(varIndex: Int, insn: AbstractInsnNode, varType: Type) {
                when (insn.opcode) {
                    Opcodes.IFNULL,
                    Opcodes.IFNONNULL ->
                        injectAssumptionsForNullCheck(varIndex, insn as JumpInsnNode, varType)
                    Opcodes.INVOKESTATIC -> {
                        assert(insn.isCheckParameterNotNull()) { "Expected non-null parameter check @${insn.getIndex()}"}
                        injectAssumptionsForParameterNotNullCheck(varIndex, insn, varType)
                    }
                    Opcodes.INSTANCEOF ->
                        injectAssumptionsForInstanceOfCheck(varIndex, insn, varType)
                }
            }

            private fun NullabilityAssumptions.injectAssumptionsForNullCheck(varIndex: Int, insn: JumpInsnNode, varType: Type) {
                //  ALOAD v
                //  IFNULL L
                //  <...>   -- v is not null here
                // L:
                //  <...>   -- v is null here

                val jumpsIfNull = insn.opcode == Opcodes.IFNULL
                val originalLabel = insn.label
                originalLabels[insn] = originalLabel
                insn.label = synthetic(LabelNode(Label()))

                val insertAfterNull = if (jumpsIfNull) insn.label else insn
                val insertAfterNonNull = if (jumpsIfNull) insn else insn.label

                methodNode.instructions.run {
                    add(insn.label)

                    insert(insertAfterNull, listOfSynthetics {
                        aconst(null)
                        store(varIndex, varType)
                        if (jumpsIfNull) {
                            goTo(originalLabel.label)
                        }
                    })

                    insert(insertAfterNonNull, listOfSynthetics {
                        anew(varType)
                        store(varIndex, varType)
                        if (!jumpsIfNull) {
                            goTo(originalLabel.label)
                        }
                    })
                }
            }

            private fun NullabilityAssumptions.injectAssumptionsForParameterNotNullCheck(varIndex: Int, insn: AbstractInsnNode, varType: Type) {
                //  ALOAD v
                //  LDC param_name
                //  INVOKESTATIC checkParameterIsNotNull
                //  <...>   -- v is not null here (otherwise an exception was thrown)

                methodNode.instructions.insert(insn, listOfSynthetics {
                    anew(varType)
                    store(varIndex, varType)
                })
            }

            private fun NullabilityAssumptions.injectAssumptionsForInstanceOfCheck(varIndex: Int, insn: AbstractInsnNode, varType: Type) {
                //  ALOAD v
                //  INSTANCEOF T
                //  IFEQ L
                //  <...>  -- v is not null here (because it is an instance of T)
                // L:
                //  <...>  -- v is something else here (maybe null)

                val next = insn.next ?: return
                if (next.opcode != Opcodes.IFEQ && next.opcode != Opcodes.IFNE) return
                if (next !is JumpInsnNode) return
                val jumpsIfInstance = next.opcode == Opcodes.IFNE

                val originalLabel: LabelNode?
                val insertAfterNotNull: AbstractInsnNode
                if (jumpsIfInstance) {
                    originalLabel = next.label
                    originalLabels[next] = next.label
                    val newLabel = synthetic(LabelNode(Label()))
                    methodNode.instructions.add(newLabel)
                    next.label = newLabel
                    insertAfterNotNull = newLabel
                }
                else {
                    originalLabel = null
                    insertAfterNotNull = next
                }

                methodNode.instructions.run {
                    insert(insertAfterNotNull, listOfSynthetics {
                        anew(varType)
                        store(varIndex, varType)
                        if (originalLabel != null) {
                            goTo(originalLabel.label)
                        }
                    })
                }
            }

        }

        inner class NullabilityAssumptions {
            val originalLabels = HashMap<JumpInsnNode, LabelNode>()
            val syntheticInstructions = ArrayList<AbstractInsnNode>()

            fun <T : AbstractInsnNode> synthetic(insn: T): T {
                syntheticInstructions.add(insn)
                return insn
            }

            inline fun listOfSynthetics(block: InstructionAdapter.() -> Unit): InsnList {
                val insnList = withInstructionAdapter(block)
                for (insn in insnList) {
                    synthetic(insn)
                }
                return insnList
            }

            fun revert() {
                methodNode.instructions.run {
                    syntheticInstructions.forEach { remove(it) }
                }
                for ((jumpInsn, originalLabel) in originalLabels) {
                    jumpInsn.label = originalLabel
                }
            }
        }
    }
}

internal fun AbstractInsnNode.isInstanceOfOrNullCheck() =
        opcode == Opcodes.INSTANCEOF || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL

internal fun AbstractInsnNode.isCheckParameterNotNull() =
        isInsn<MethodInsnNode>(Opcodes.INVOKESTATIC) {
            owner == "kotlin/jvm/internal/Intrinsics" &&
            name == "checkParameterIsNotNull" &&
            desc == "(Ljava/lang/Object;Ljava/lang/String;)V"
        }

internal fun InsnList.popReferenceValueBefore(insn: AbstractInsnNode) {
    val prev = insn.previous
    when (prev?.opcode) {
        Opcodes.ACONST_NULL,
        Opcodes.DUP,
        Opcodes.ALOAD ->
            remove(prev)
        else ->
            insertBefore(insn, InsnNode(Opcodes.POP))
    }
}
