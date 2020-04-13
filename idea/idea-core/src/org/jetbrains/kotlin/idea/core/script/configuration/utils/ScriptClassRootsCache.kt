/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script.configuration.utils

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.NonClasspathDirectoriesScope
import com.intellij.util.containers.ConcurrentFactoryMap
import com.intellij.util.containers.SLRUMap
import com.intellij.util.io.URLUtil
import org.jetbrains.kotlin.codegen.inline.getOrPut
import org.jetbrains.kotlin.idea.core.script.ScriptConfigurationManager
import org.jetbrains.kotlin.idea.util.getProjectJdkTableSafe
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper
import java.io.File
import java.nio.file.FileSystems

abstract class ScriptClassRootsCache(
    private val project: Project,
    private val roots: ScriptClassRootsStorage.Companion.ScriptClassRoots
) {
    abstract val fileToConfiguration: (VirtualFile) -> ScriptCompilationConfigurationWrapper?

    protected abstract val rootsCacheKey: ScriptClassRootsStorage.Companion.Key

    abstract fun contains(file: VirtualFile): Boolean

    class Fat(
        val scriptConfiguration: ScriptCompilationConfigurationWrapper,
        val classFilesScope: GlobalSearchScope
    )

    private val memoryCache = SLRUMap<VirtualFile, Fat>(MAX_SCRIPTS_CACHED, MAX_SCRIPTS_CACHED)

    fun get(key: VirtualFile): Fat? {
        return memoryCache.getOrPut(key) {
            val configuration = fileToConfiguration(key) ?: return null

            val scriptSdk = scriptSdk ?: ScriptConfigurationManager.getScriptDefaultSdk(project)
            return@getOrPut if (scriptSdk != null && !scriptSdk.isAlreadyIndexed(project)) {
                Fat(
                    configuration,
                    NonClasspathDirectoriesScope.compose(
                        scriptSdk.rootProvider.getFiles(OrderRootType.CLASSES).toList() + roots.classpathFiles.toVirtualFiles()
                    )
                )

            } else {
                Fat(
                    configuration,
                    NonClasspathDirectoriesScope.compose(roots.classpathFiles.toVirtualFiles())
                )
            }
        }
    }

    //todo
    private fun String.toVirtualFile(): VirtualFile {
        if (this.endsWith("!/")) {
            StandardFileSystems.jar()?.findFileByPath(this)?.let {
                return it
            }
        }
        StandardFileSystems.jar()?.findFileByPath("$this!/")?.let {
            return it
        }
        StandardFileSystems.local()?.findFileByPath(this)?.let {
            return it
        }


        // TODO: report this somewhere, but do not throw: assert(res != null, { "Invalid classpath entry '$this': exists: ${exists()}, is directory: $isDirectory, is file: $isFile" })

        return VfsUtil.findFile(FileSystems.getDefault().getPath(this), true)!!
    }

    fun Collection<String>.toVirtualFiles() =
        map { it.toVirtualFile() }


    val scriptSdk: Sdk? by lazy {
        return@lazy roots.sdks.firstOrNull()
    }

    abstract fun getScriptSdk(file: VirtualFile): Sdk?

    abstract val firstScriptSdk: Sdk?

    val allDependenciesClassFiles by lazy {
        ScriptClassRootsStorage.getInstance(project).loadClasspathRoots(rootsCacheKey)
    }

    val allDependenciesSources by lazy {
        ScriptClassRootsStorage.getInstance(project).loadSourcesRoots(rootsCacheKey)
    }

    val allDependenciesClassFilesScope by lazy {
        NonClasspathDirectoriesScope.compose(allDependenciesClassFiles)
    }

    val allDependenciesSourcesScope by lazy {
        NonClasspathDirectoriesScope.compose(allDependenciesSources)
    }

    fun getScriptDependenciesClassFilesScope(file: VirtualFile): GlobalSearchScope {
        return get(file)?.classFilesScope ?: GlobalSearchScope.EMPTY_SCOPE
    }

    fun hasNotCachedRoots(roots: ScriptClassRootsStorage.Companion.ScriptClassRoots): Boolean {
        return !ScriptClassRootsStorage.getInstance(project).containsAll(rootsCacheKey, roots)
    }

    fun saveClassRootsToStorage() {
        val rootsStorage = ScriptClassRootsStorage.getInstance(project)
        rootsStorage.save(rootsCacheKey, roots)
    }

    companion object {
        const val MAX_SCRIPTS_CACHED = 50

        fun toStringValues(prop: Collection<File>): Set<String> {
            return prop.mapNotNull {
                when {
                    it.isDirectory -> it.absolutePath
                    it.isFile -> it.absolutePath
                    else -> null
                }
            }.toSet()
        }

        fun getScriptSdkOfDefault(javaHomeStr: File?, project: Project): Sdk? {
            return getScriptSdk(javaHomeStr) ?: ScriptConfigurationManager.getScriptDefaultSdk(project)
        }

        fun getScriptSdk(javaHomeStr: File?): Sdk? {
            // workaround for mismatched gradle wrapper and plugin version
            val javaHome = try {
                javaHomeStr?.let { VfsUtil.findFileByIoFile(it, true) }
            } catch (e: Throwable) {
                null
            } ?: return null

            return getProjectJdkTableSafe().allJdks.find { it.homeDirectory == javaHome }
        }

        fun Sdk.isAlreadyIndexed(project: Project): Boolean {
            return ModuleManager.getInstance(project).modules.any { ModuleRootManager.getInstance(it).sdk == this }
        }

    }
}

internal class DefaultClassRootsCache(
    project: Project,
    private val all: Map<VirtualFile, ScriptCompilationConfigurationWrapper>
) : ScriptClassRootsCache(project, extractRoots(all, project)) {

    override fun contains(file: VirtualFile): Boolean = file in all

    override val fileToConfiguration: (VirtualFile) -> ScriptCompilationConfigurationWrapper?
        get() = { all[it] }

    override val rootsCacheKey: ScriptClassRootsStorage.Companion.Key = ScriptClassRootsStorage.Companion.Key("default")

    private val scriptsSdksCache: Map<VirtualFile, Sdk?> =
        ConcurrentFactoryMap.createWeakMap { file ->
            return@createWeakMap getScriptSdk(all[file]?.javaHome) ?: ScriptConfigurationManager.getScriptDefaultSdk(project)
        }

    override fun getScriptSdk(file: VirtualFile): Sdk? = scriptsSdksCache[file]

    override val firstScriptSdk: Sdk? by lazy {
        val firstCachedScript = all.keys.firstOrNull() ?: return@lazy null
        return@lazy getScriptSdk(firstCachedScript)
    }

    companion object {
        fun extractRoots(
            project: Project,
            configuration: ScriptCompilationConfigurationWrapper
        ): ScriptClassRootsStorage.Companion.ScriptClassRoots {
            val scriptSdk = getScriptSdkOfDefault(configuration.javaHome, project)
            if (scriptSdk != null && !scriptSdk.isAlreadyIndexed(project)) {
                return ScriptClassRootsStorage.Companion.ScriptClassRoots(
                    toStringValues(configuration.dependenciesClassPath),
                    toStringValues(configuration.dependenciesSources),
                    setOf(scriptSdk)
                )
            }

            return ScriptClassRootsStorage.Companion.ScriptClassRoots(
                toStringValues(configuration.dependenciesClassPath),
                toStringValues(configuration.dependenciesSources),
                emptySet()
            )
        }

        fun extractRoots(
            all: Map<VirtualFile, ScriptCompilationConfigurationWrapper>,
            project: Project
        ): ScriptClassRootsStorage.Companion.ScriptClassRoots {
            val classpath = mutableSetOf<File>()
            val sources = mutableSetOf<File>()
            val sdks = mutableSetOf<Sdk>()

            for ((_, configuration) in all) {
                val scriptSdk = getScriptSdk(configuration.javaHome)
                if (scriptSdk != null && !scriptSdk.isAlreadyIndexed(project)) {
                    sdks.add(scriptSdk)
                }

                classpath.addAll(configuration.dependenciesClassPath)
                sources.addAll(configuration.dependenciesSources)
            }

            return ScriptClassRootsStorage.Companion.ScriptClassRoots(
                toStringValues(classpath),
                toStringValues(sources),
                sdks
            )
        }
    }
}