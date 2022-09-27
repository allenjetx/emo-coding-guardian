/*
 * Copyright 1999-2017 Alibaba Group.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.p3c.idea.extentsions.inspection

import com.alibaba.p3c.idea.extentsions.config.P3cConfig
import com.alibaba.p3c.idea.native.inspection.AliMissingOverrideAnnotationInspection
import com.alibaba.p3c.idea.native.inspection.MapOrSetKeyShouldOverrideHashCodeEqualsInspection
import com.alibaba.p3c.idea.native.inspection.*
import com.alibaba.p3c.pmd.I18nResources
import com.alibaba.smartfox.idea.common.util.getService
import com.beust.jcommander.internal.Lists
import com.beust.jcommander.internal.Maps
import com.intellij.codeInspection.InspectionToolProvider
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiImportList
import com.intellij.psi.PsiJavaFile
import javassist.CannotCompileException
import javassist.ClassClassPath
import javassist.ClassPool
import javassist.CtField
import javassist.NotFoundException
import net.sourceforge.pmd.*
import javax.annotation.Generated

/**
 * @author caikang
 * @date 2016/12/16
 */
class EmoLocalInspectionToolProvider : InspectionToolProvider {

    override fun getInspectionClasses(): Array<Class<out LocalInspectionTool>> {
        return CLASS_LIST.toTypedArray()
    }

    interface ShouldInspectChecker {
        /**
         * check inspect whether or not

         * @param file file to inspect
         * @return true or false
         */
        fun shouldInspect(file: PsiFile): Boolean
    }
    /* RuleInfo is build a relation rule and should checker.*/
    class RuleInfo(var rule: Rule, var shouldInspectChecker: ShouldInspectChecker)

    companion object {

        val ruleInfoMap: MutableMap<String, RuleInfo> = Maps.newHashMap()
        private val LOGGER = Logger.getInstance(EmoLocalInspectionToolProvider::class.java)
        val ruleNames: MutableList<String> = Lists.newArrayList()!!
        private val CLASS_LIST = Lists.newArrayList<Class<LocalInspectionTool>>()

        // 本地实现的一些检查工具
        private val nativeInspectionToolClass = arrayListOf<Class<out LocalInspectionTool>>(
            AliMissingOverrideAnnotationInspection::class.java,
            AliAccessStaticViaInstanceInspection::class.java,
            AliDeprecationInspection::class.java,
            MapOrSetKeyShouldOverrideHashCodeEqualsInspection::class.java,
            AliArrayNamingShouldHaveBracketInspection::class.java,
            AliControlFlowStatementWithoutBracesInspection::class.java,
            AliEqualsAvoidNullInspection::class.java,
            AliLongLiteralsEndingWithLowercaseLInspection::class.java,
            AliWrapperTypeEqualityInspection::class.java
        )

        val javaShouldInspectChecker = object : ShouldInspectChecker {
            override fun shouldInspect(file: PsiFile): Boolean {
                // Represents a Java, JSP or .class file.
                val basicInspect = file is PsiJavaFile && file !is PsiCompiledFile
                if (!basicInspect) {
                    return false
                }
                // 有效性检查
                if (!validScope(file)) {
                    return false
                }

                val importList = file.children.firstOrNull {
                    it is PsiImportList
                } as? PsiImportList ?: return true

                return !importList.allImportStatements.any {
                    it.text.contains(Generated::class.java.name)
                }
            }

            private fun validScope(file: PsiFile): Boolean {
                val virtualFile = file.virtualFile
                val index = ProjectRootManager.getInstance(file.project).fileIndex
                return index.isInSource(virtualFile)
                        && !index.isInTestSourceContent(virtualFile)
                        && !index.isInLibraryClasses(virtualFile)
                        && !index.isInLibrarySource(virtualFile)
            }
        }

        init {
            I18nResources.changeLanguage(P3cConfig::class.java.getService().locale)
            Thread.currentThread().contextClassLoader = EmoLocalInspectionToolProvider::class.java.classLoader
            //初始化 pmd 检测器
            initPmdInspection()
            //初始化 native检测器
            initNativeInspection()
        }

        private fun initNativeInspection() {
            val pool = ClassPool.getDefault()
            pool.insertClassPath(ClassClassPath(DelegateLocalInspectionTool::class.java))
            nativeInspectionToolClass.forEach {
                pool.insertClassPath(ClassClassPath(it))
                val cc = pool.get(DelegateLocalInspectionTool::class.java.name)
                cc.name = "Delegate${it.simpleName}"
                val ctField = cc.getField("forJavassist")
                cc.removeField(ctField)
                val itClass = pool.get(it.name)
                val toolClass = pool.get(LocalInspectionTool::class.java.name)
                val newField = CtField(toolClass, "forJavassist", cc)
                cc.addField(newField, CtField.Initializer.byNew(itClass))
                CLASS_LIST.add(cc.toClass() as Class<LocalInspectionTool>)
            }
        }

        private fun getRuleSet(ruleSetName: String): RuleSet {
            val factory = RuleSetFactory()
            return factory.createRuleSet(ruleSetName.replace("/", "-"))
        }

        fun getRuleSets(): RuleSets {
            return RuleSets().also { rs ->
                for (ruleSet in listOf(getRuleSet("java/ali-pmd"))) {
                    rs.addRuleSet(ruleSet)
                }
            }
        }

        private fun processForRuleSet(ruleSetName: String, shouldInspectChecker: ShouldInspectChecker): List<RuleInfo> {
            val result = Lists.newArrayList<RuleInfo>()
            try {
                val ruleSet = getRuleSet(ruleSetName)
                ruleSet.rules.mapTo(result) {
                    RuleInfo(it, shouldInspectChecker)
                }
            } catch (e: RuleSetNotFoundException) {
                LOGGER.error(String.format("rule set %s not found for", ruleSetName))
            }

            return result
        }

        private fun initPmdInspection() {
            // 初始化 ruleName 和 ruleInfo Map
            for (ri in processForRuleSet("java/ali-pmd", javaShouldInspectChecker)) {
                ruleNames.add(ri.rule.name)
                ruleInfoMap[ri.rule.name] = ri
            }

            //批量构建 PMD的 LocalInspectionTool类
            val pool = ClassPool.getDefault()
            pool.insertClassPath(ClassClassPath(DelegatePmdInspection::class.java))
            try {
                for (ruleInfo in ruleInfoMap.values) {
                    val cc = pool.get(DelegatePmdInspection::class.java.name)
                    cc.name = ruleInfo.rule.name + "Inspection"
                    val ctField = cc.getField("ruleName")
                    cc.removeField(ctField)
                    val value = "\"" + ruleInfo.rule.name + "\""
                    val newField = CtField.make("private String ruleName = $value;", cc)
                    cc.addField(newField, value)
                    CLASS_LIST.add(cc.toClass() as Class<LocalInspectionTool>)
                }

            } catch (e: NotFoundException) {
                LOGGER.error(e)
            } catch (e: CannotCompileException) {
                LOGGER.error(e)
            }
        }
    }
}
