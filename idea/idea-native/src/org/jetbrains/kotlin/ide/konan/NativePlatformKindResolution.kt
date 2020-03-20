/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ide.konan

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.PersistentLibraryKind
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.PathUtil
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.analyzer.PlatformAnalysisParameters
import org.jetbrains.kotlin.analyzer.ResolverForModuleFactory
import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.functions.functionInterfacePackageFragmentProvider
import org.jetbrains.kotlin.builtins.konan.KonanBuiltIns
import org.jetbrains.kotlin.caches.resolve.IdePlatformKindResolution
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentProvider
import org.jetbrains.kotlin.descriptors.impl.CompositePackageFragmentProvider
import org.jetbrains.kotlin.descriptors.konan.DeserializedKlibModuleOrigin
import org.jetbrains.kotlin.descriptors.konan.KlibModuleOrigin
import org.jetbrains.kotlin.ide.konan.analyzer.NativeResolverForModuleFactory
import org.jetbrains.kotlin.idea.caches.project.LibraryInfo
import org.jetbrains.kotlin.idea.caches.project.SdkInfo
import org.jetbrains.kotlin.idea.caches.project.lazyClosure
import org.jetbrains.kotlin.idea.caches.resolve.BuiltInsCacheKey
import org.jetbrains.kotlin.idea.compiler.IDELanguageSettingsProvider
import org.jetbrains.kotlin.konan.library.KONAN_STDLIB_NAME
import org.jetbrains.kotlin.konan.util.KlibMetadataFactories
import org.jetbrains.kotlin.library.isInterop
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.impl.NativeIdePlatformKind
import org.jetbrains.kotlin.platform.konan.KonanPlatforms
import org.jetbrains.kotlin.resolve.CompilerDeserializationConfiguration
import org.jetbrains.kotlin.resolve.ImplicitIntegerCoercion
import org.jetbrains.kotlin.resolve.TargetEnvironment
import org.jetbrains.kotlin.library.metadata.NullFlexibleTypeDeserializer
import org.jetbrains.kotlin.serialization.konan.impl.KlibMetadataModuleDescriptorFactoryImpl
import org.jetbrains.kotlin.storage.StorageManager
import org.jetbrains.kotlin.idea.klib.getCompatibilityInfo
import org.jetbrains.kotlin.idea.klib.readSafe
import org.jetbrains.kotlin.idea.klib.AbstractKlibLibraryInfo
import org.jetbrains.kotlin.idea.klib.isKlibLibraryRootForPlatform

class NativePlatformKindResolution : IdePlatformKindResolution {

    override fun createLibraryInfo(project: Project, library: Library): List<LibraryInfo> {
        return library.getFiles(OrderRootType.CLASSES).mapNotNull { file ->
            if (!isLibraryFileForPlatform(file)) return@createLibraryInfo emptyList()
            val path = PathUtil.getLocalPath(file) ?: return@createLibraryInfo emptyList()
            NativeKlibLibraryInfo(project, library, path)
        }
    }

    override fun createPlatformSpecificPackageFragmentProvider(
        moduleInfo: ModuleInfo,
        storageManager: StorageManager,
        languageVersionSettings: LanguageVersionSettings,
        moduleDescriptor: ModuleDescriptor
    ): PackageFragmentProvider? =
        createLibraryPackageFragmentProvider(moduleInfo, storageManager, languageVersionSettings, moduleDescriptor)

    override fun isLibraryFileForPlatform(virtualFile: VirtualFile): Boolean =
        virtualFile.isKlibLibraryRootForPlatform(KonanPlatforms.defaultKonanPlatform)

    override fun createResolverForModuleFactory(
        settings: PlatformAnalysisParameters,
        environment: TargetEnvironment,
        platform: TargetPlatform
    ): ResolverForModuleFactory {
        return NativeResolverForModuleFactory(settings, environment, platform)
    }

    override val libraryKind: PersistentLibraryKind<*>?
        get() = NativeLibraryKind

    override val kind get() = NativeIdePlatformKind

    override fun getKeyForBuiltIns(moduleInfo: ModuleInfo, sdkInfo: SdkInfo?): BuiltInsCacheKey = NativeBuiltInsCacheKey

    override fun createBuiltIns(moduleInfo: ModuleInfo, projectContext: ProjectContext, sdkDependency: SdkInfo?) =
        createKotlinNativeBuiltIns(moduleInfo, projectContext)

    object NativeBuiltInsCacheKey : BuiltInsCacheKey

    companion object {
        private val NativeFactories = KlibMetadataFactories(::KonanBuiltIns, NullFlexibleTypeDeserializer)

        fun createLibraryPackageFragmentProvider(
            moduleInfo: ModuleInfo,
            storageManager: StorageManager,
            languageVersionSettings: LanguageVersionSettings,
            moduleDescriptor: ModuleDescriptor
        ): PackageFragmentProvider? {
            val library = (moduleInfo as? NativeKlibLibraryInfo)?.resolvedKotlinLibrary ?: return null
            if (!library.getCompatibilityInfo().isCompatible) return null

            val libraryProto = CachingIdeKonanLibraryMetadataLoader.loadModuleHeader(library)
            val deserializationConfiguration = CompilerDeserializationConfiguration(languageVersionSettings)

            return NativeFactories.DefaultDeserializedDescriptorFactory.createPackageFragmentProvider(
                library,
                CachingIdeKonanLibraryMetadataLoader,
                libraryProto.packageFragmentNameList,
                storageManager,
                moduleDescriptor,
                deserializationConfiguration,
                null
            )
        }

        private fun createKotlinNativeBuiltIns(moduleInfo: ModuleInfo, projectContext: ProjectContext): KotlinBuiltIns {
            val stdlibInfo = moduleInfo.findNativeStdlib() ?: return DefaultBuiltIns.Instance

            val project = projectContext.project
            val storageManager = projectContext.storageManager

            val builtInsModule = NativeFactories.DefaultDescriptorFactory.createDescriptorAndNewBuiltIns(
                KotlinBuiltIns.BUILTINS_MODULE_NAME,
                storageManager,
                DeserializedKlibModuleOrigin(stdlibInfo.resolvedKotlinLibrary),
                stdlibInfo.capabilities
            )

            val languageVersionSettings = IDELanguageSettingsProvider.getLanguageVersionSettings(
                stdlibInfo,
                project,
                isReleaseCoroutines = false
            )

            val stdlibPackageFragmentProvider = createLibraryPackageFragmentProvider(
                stdlibInfo,
                storageManager,
                languageVersionSettings,
                builtInsModule
            ) ?: return DefaultBuiltIns.Instance

            builtInsModule.initialize(
                CompositePackageFragmentProvider(
                    listOf(
                        stdlibPackageFragmentProvider,
                        functionInterfacePackageFragmentProvider(storageManager, builtInsModule),
                        (NativeFactories.DefaultDeserializedDescriptorFactory as KlibMetadataModuleDescriptorFactoryImpl)
                            .createForwardDeclarationHackPackagePartProvider(storageManager, builtInsModule)
                    )
                )
            )

            builtInsModule.setDependencies(listOf(builtInsModule))

            return builtInsModule.builtIns
        }

        private fun ModuleInfo.findNativeStdlib(): NativeKlibLibraryInfo? =
            dependencies().lazyClosure { it.dependencies() }
                .filterIsInstance<NativeKlibLibraryInfo>()
                .firstOrNull { it.isStdlib && it.compatibilityInfo.isCompatible }
    }
}

class NativeKlibLibraryInfo(project: Project, library: Library, libraryRoot: String) :
    AbstractKlibLibraryInfo(project, library, libraryRoot) {

    val isStdlib get() = libraryRoot.endsWith(KONAN_STDLIB_NAME)

    override val capabilities: Map<ModuleDescriptor.Capability<*>, Any?>
        get() {
            val capabilities = super.capabilities.toMutableMap()
            capabilities += KlibModuleOrigin.CAPABILITY to DeserializedKlibModuleOrigin(resolvedKotlinLibrary)
            capabilities += ImplicitIntegerCoercion.MODULE_CAPABILITY to resolvedKotlinLibrary.readSafe(false) { isInterop }
            return capabilities
        }

    override val platform: TargetPlatform
        get() = KonanPlatforms.defaultKonanPlatform

    override fun toString() = "Native" + super.toString()
}
