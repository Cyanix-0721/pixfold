package com.cyanix.pixfold.ui

import org.junit.Assume
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

/**
 * 实时 UI 预览出图配置与按方法过滤辅助。
 *
 * 仅当显式传入 `-Dpixfold.preview.outDir=...` 时，将 `VisualRegressionSnapshotTest`
 * 的输出目录重定向至构建临时目录并按 `<outDir>/methods.txt` 过滤用例；
 * 未传入该系统属性时，始终维持默认黄金图目录 `src/test/snapshots` 且放行全部用例。
 */
internal object LivePreviewConfig {
    const val PROP_OUT_DIR = "pixfold.preview.outDir"
    const val DEFAULT_SNAPSHOT_DIR = "src/test/snapshots"
    const val METHODS_FILE_NAME = "methods.txt"

    fun resolveOutputDir(rawOverride: String?): String {
        val trimmed = rawOverride?.trim().orEmpty()
        if (trimmed.isEmpty()) return DEFAULT_SNAPSHOT_DIR
        val normalized = trimmed.trimEnd('/', '\\')
        return normalized.ifEmpty { DEFAULT_SNAPSHOT_DIR }
    }

    fun snapshotPath(
        name: String,
        outDirOverride: String? = System.getProperty(PROP_OUT_DIR),
    ): String {
        val dir = resolveOutputDir(outDirOverride)
        return "$dir/$name.png"
    }

    fun readMethodFilter(
        outDirOverride: String? = System.getProperty(PROP_OUT_DIR),
    ): Set<String> {
        val raw = outDirOverride?.trim().orEmpty()
        if (raw.isEmpty()) return emptySet()
        val methodsFile = File(resolveOutputDir(raw), METHODS_FILE_NAME)
        if (!methodsFile.isFile) return emptySet()
        return methodsFile.readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    }

    fun shouldRunMethod(
        methodName: String,
        outDirOverride: String? = System.getProperty(PROP_OUT_DIR),
        allowedMethods: Set<String> = readMethodFilter(outDirOverride),
    ): Boolean {
        if (outDirOverride.isNullOrBlank()) return true
        if (allowedMethods.isEmpty()) return true
        return methodName in allowedMethods
    }
}

/**
 * 实时预览模式下按 `methods.txt` 跳过未选中用例的 JUnit 4 规则。
 *
 * 需配合 `@get:Rule(order = Int.MIN_VALUE)` 挂载在测试类最外层，
 * 确保跳过时不会初始化 Robolectric / Compose 规则。
 */
class PreviewFilterRule(
    private val outDirProvider: () -> String? = { System.getProperty(LivePreviewConfig.PROP_OUT_DIR) },
    private val methodsProvider: (String?) -> Set<String> = { LivePreviewConfig.readMethodFilter(it) },
) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val outDir = outDirProvider()
            val allowed = methodsProvider(outDir)
            val methodName = description.methodName.orEmpty()
            Assume.assumeTrue(
                "Skipped by ${LivePreviewConfig.PROP_OUT_DIR} methods filter: $methodName",
                LivePreviewConfig.shouldRunMethod(methodName, outDir, allowed),
            )
            base.evaluate()
        }
    }
}
