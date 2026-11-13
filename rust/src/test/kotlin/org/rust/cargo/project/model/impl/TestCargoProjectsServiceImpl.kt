package org.rust.cargo.project.model.impl

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.rust.cargo.CfgOptions
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.FeatureState
import org.rust.cargo.project.workspace.PackageFeature
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.sequences.Sequence
import kotlin.sequences.emptySequence

/**
 * Test-only stand-in for the Rust plugin's own test service. The distributed plugin names
 * `org.rust.cargo.project.model.impl.TestCargoProjectsServiceImpl` as the project service implementation in
 * unit-test mode, but it does not ship that test class. Parsing a Rust file from text needs no Cargo project, so
 * this stand-in reports none. Remove it when a Rust plugin release ships the test class or a test-only artifact.
 */
class TestCargoProjectsServiceImpl(private val projectRef: Project) : CargoProjectsService {

    override val project: Project
        get() = projectRef

    override val allProjects: Collection<CargoProject>
        get() = emptyList()

    override val hasAtLeastOneValidProject: Boolean
        get() = false

    override val initialized: Boolean
        get() = true

    override val isRefreshInProgress: Boolean
        get() = false

    override fun findProjectForFile(file: VirtualFile): CargoProject? = null

    override fun findPackageForFile(file: VirtualFile): CargoWorkspace.Package? = null

    override fun attachCargoProject(manifestPath: Path): CompletableFuture<List<CargoProject>> =
        CompletableFuture.completedFuture(emptyList())

    override fun attachCargoProjects(manifestPaths: Collection<Path>) {
        // No Cargo projects in this test environment.
    }

    override fun detachCargoProject(project: CargoProject) {
        // No Cargo projects in this test environment.
    }

    override fun refreshAllProjects(isExplicitReload: Boolean): CompletableFuture<List<CargoProject>> =
        CompletableFuture.completedFuture(emptyList())

    override fun discoverAndRefresh(): CompletableFuture<List<CargoProject>> =
        CompletableFuture.completedFuture(emptyList())

    override fun suggestManifests(): Sequence<VirtualFile> = emptySequence()

    override fun modifyFeatures(project: CargoProject, features: Set<PackageFeature>, state: FeatureState) {
        // No Cargo projects in this test environment.
    }

    override fun modifyCfgOptions(project: CargoProject, action: () -> CompletableFuture<CfgOptions?>) {
        // No Cargo projects in this test environment.
    }
}
